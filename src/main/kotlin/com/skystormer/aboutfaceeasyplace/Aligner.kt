package com.skystormer.aboutfaceeasyplace

import net.minecraft.client.player.LocalPlayer
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.InteractionHand
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.context.BlockPlaceContext
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.Property
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.Vec3

/**
 * Works out how to make a vanilla server place the block the schematic actually asks for.
 *
 * Litematica's own answer to orientation is to write the wanted block state into the fractional
 * part of the click position and have the server decode it. That is a fine answer on a server
 * running Carpet or Servux, which know to look; on an unmodified server it is not, because since
 * 1.18.2 vanilla rejects a click that lands outside the block it claims to be on, which is exactly
 * what the encoded position is. The click is thrown away and the player is left with a ghost block.
 *
 * So Easy Place on a vanilla server falls back to a protocol that encodes nothing, and the server
 * decides orientation the ordinary way — from where the player is looking and which face they
 * clicked. Litematica sets the face for the handful of blocks that read it, and never touches the
 * look direction at all, which is why stairs come out backwards, hoppers feed the wrong way and
 * every furnace faces the player.
 *
 * This works the other way round. Nothing is encoded and nothing is smuggled: the placement is an
 * ordinary one, and what changes is the two inputs a vanilla server reads to decide orientation —
 * where the player appears to be looking, and where on the block they appear to have clicked. Both
 * stay inside what a real click could have been, so there is nothing for the server to reject.
 *
 * The combination to use is not guessed at. Candidates are tried by asking the block itself what
 * it would place, which is the same question the server will answer, so a combination that would
 * not really produce the wanted state is never claimed. A block that cannot be steered this way is
 * reported as such rather than placed wrongly.
 */
object Aligner {

    /** A yaw/pitch pair, in Minecraft's own convention: yaw 0 faces south, pitch -90 looks up. */
    data class Rotation(val yaw: Float, val pitch: Float)

    /** What to claim: where the player appears to be looking, and what they appear to have clicked. */
    data class Placement(val hit: BlockHitResult, val rotation: Rotation)

    sealed interface Outcome {
        /**
         * Leave Litematica's placement exactly as it is.
         *
         * Either the block has no orientation to get wrong, or it already lands the right way
         * round, or it is not something this mod understands well enough to touch.
         */
        data object LeaveAlone : Outcome

        /** Place it, but claim [placement] first. */
        data class Aligned(val placement: Placement) : Outcome

        /**
         * This block cannot be made to land the way the schematic asks for.
         *
         * Reported rather than placed anyway. A missing block shows up in Litematica's overlay as
         * something still to do; a wrong one has to be found and broken first.
         */
        data class Unsolvable(val wanted: BlockState) : Outcome
    }

    /**
     * How many placements to simulate before settling for the best answer found so far.
     *
     * Reached only by a block with an unusually large number of steerable properties. The sweep
     * runs on the client thread in the middle of a placement, and a bounded wrong-ish answer is
     * worth more here than an unbounded right one.
     */
    private const val SIMULATION_BUDGET = 4096

    /**
     * Properties that a placement decides, named rather than listed.
     *
     * Naming them is what lets this work on blocks the mod has never heard of, including modded
     * ones: these are the names Minecraft's own block states use, and they do not change between
     * versions the way the constants holding them can. Anything whose value is a [Direction] or an
     * axis is included whatever it is called, which covers the rest.
     *
     * `type` is here for slabs, and picks up chests and piston heads as collateral. That is
     * harmless — a property no candidate placement can change is dropped before matching, so a
     * chest half that only a neighbouring chest can decide never stops a chest being placed.
     *
     * `hanging` is the one that is easy to miss: a lantern is the only common block whose
     * orientation is stored as a boolean rather than as a direction, so neither the direction rule
     * nor the rest of these names would catch it.
     */
    private val PLACEMENT_PROPERTIES = setOf(
        "facing", "axis", "half", "type", "face", "hinge",
        "orientation", "rotation", "attachment", "vertical_direction", "hanging",
    )

    /**
     * Properties worth matching but never worth failing over.
     *
     * A rail's shape is chosen by the placement and a staircase's is chosen by its neighbours, and
     * both are called `shape`. Preferring a candidate that matches gets the rail right; not
     * requiring it keeps the staircase from being declared impossible.
     */
    private val PREFERRED_PROPERTIES = setOf("shape")

    /** Points on a clicked face, as fractions across it, most ordinary first. */
    private val FACE_POINTS = listOf(
        0.5 to 0.5,
        0.5 to 0.25, 0.5 to 0.75,
        0.25 to 0.5, 0.75 to 0.5,
        0.25 to 0.25, 0.75 to 0.75,
    )

    /**
     * @param schematic the world Litematica is building from, which is the only thing here that
     *   knows what the block is supposed to look like. Passed in rather than fetched so that the
     *   search can be exercised against a world of one's own choosing.
     */
    fun plan(
        player: LocalPlayer,
        hand: InteractionHand,
        original: BlockHitResult,
        schematic: BlockGetter,
    ): Outcome {
        val stack = player.getItemInHand(hand)
        val block = (stack.item as? BlockItem)?.block ?: return Outcome.LeaveAlone

        val targetPos = BlockPlaceContext(player, hand, stack, original).clickedPos
        val wanted = schematic.getBlockState(targetPos)

        // Litematica decides what goes where; this mod only decides which way round it goes. If
        // the schematic does not want the block being held, Litematica is doing something this mod
        // does not understand, and the safe move is to stay out of it.
        if (wanted.block !== block) return Outcome.LeaveAlone

        val properties = block.stateDefinition.properties
        val steerable = properties.filter { steerable(wanted, it) }
        val preferred = properties.filter { it.name in PREFERRED_PROPERTIES }
        if (steerable.isEmpty() && preferred.isEmpty()) return Outcome.LeaveAlone

        val hits = hitCandidates(player, hand, original, targetPos)
        if (hits.isEmpty()) return Outcome.LeaveAlone
        val rotations = rotationCandidates(player, wanted, properties)

        val baseline = Placement(hits.first(), rotations.first())
        val tried = ArrayList<Pair<Placement, BlockState>>(minOf(hits.size * rotations.size, SIMULATION_BUDGET))

        val realYaw = player.yRot
        val realPitch = player.xRot
        val realHead = player.yHeadRot
        try {
            // Clicks outer, rotations inner. Litematica chose its click for reasons of its own —
            // a supporting block for a torch, a particular half for a slab — and a rotation claim
            // overrides nothing, so every rotation is tried against Litematica's own click before
            // any other click is considered.
            outer@ for (hit in hits) {
                for (rotation in rotations) {
                    player.yRot = rotation.yaw
                    player.yHeadRot = rotation.yaw
                    player.xRot = rotation.pitch

                    val placed = block.getStateForPlacement(BlockPlaceContext(player, hand, stack, hit))
                        ?: continue
                    val placement = Placement(hit, rotation)
                    tried += placement to placed

                    // An exact answer is worth stopping for: nothing later in the sweep can beat
                    // matching every property the placement has any say over.
                    if (matches(placed, wanted, steerable) && matches(placed, wanted, preferred)) {
                        return decide(placement, baseline)
                    }
                    if (tried.size >= SIMULATION_BUDGET) break@outer
                }
            }
        } finally {
            player.yRot = realYaw
            player.xRot = realPitch
            player.yHeadRot = realHead
        }

        if (tried.isEmpty()) return Outcome.LeaveAlone

        // No candidate got everything. Settle, in order of how much is being given up — and work
        // out what was ever really on offer rather than assuming, because a property no candidate
        // managed to vary was never this placement's to decide. That is what keeps a block whose
        // orientation is fixed, or decided by a neighbour, from being declared impossible.
        val reachableSteerable = reachable(steerable, tried)
        val reachablePreferred = reachable(preferred, tried)
        val tiers = listOf(
            steerable,
            reachableSteerable + reachablePreferred,
            reachableSteerable,
        ).filter { it.isNotEmpty() }.distinct()

        for (tier in tiers) {
            tried.firstOrNull { matches(it.second, wanted, tier) }?.let { return decide(it.first, baseline) }
        }

        // Nothing matched. If there was never anything steerable to get right, Litematica's own
        // placement is already as close as this can get, so let it through; the alternative is
        // refusing to place a block for a reason that has nothing to do with orientation.
        if (reachableSteerable.isEmpty()) return Outcome.LeaveAlone
        return Outcome.Unsolvable(wanted)
    }

    private fun decide(chosen: Placement, baseline: Placement): Outcome =
        if (chosen == baseline) Outcome.LeaveAlone else Outcome.Aligned(chosen)

    private fun steerable(wanted: BlockState, property: Property<*>): Boolean {
        val value = BlockStates.read(wanted, property)
        return value is Direction || value is Direction.Axis || property.name in PLACEMENT_PROPERTIES
    }

    private fun matches(placed: BlockState, wanted: BlockState, properties: List<Property<*>>): Boolean =
        properties.all { BlockStates.agree(placed, wanted, it) }

    /** The properties out of [properties] that the candidates actually managed to change. */
    private fun reachable(
        properties: List<Property<*>>,
        tried: List<Pair<Placement, BlockState>>,
    ): List<Property<*>> = properties.filter { property ->
        val first = BlockStates.read(tried.first().second, property)
        tried.any { BlockStates.read(it.second, property) != first }
    }

    /**
     * Clicks worth claiming, all of which put the block in the same place.
     *
     * Litematica's own click comes first, so a block that is already going down correctly is left
     * completely alone. The rest are ordinary points on each face of the same block, which is what
     * reaches the blocks that read the face they were clicked on — a hopper's spout, a log's axis,
     * a slab's half — and the blocks that read where on the face the click landed, such as a
     * door's hinge.
     *
     * Every candidate is checked against where the block would actually land, which is what makes
     * this safe to do blind. Where Litematica has clicked a neighbouring block to support what it
     * is placing, as it does for torches and wall signs, the face is carrying the position and
     * changing it would move the block; those candidates fail the check and drop out on their own.
     */
    private fun hitCandidates(
        player: LocalPlayer,
        hand: InteractionHand,
        original: BlockHitResult,
        targetPos: BlockPos,
    ): List<BlockHitResult> {
        val clickPos = original.blockPos
        val stack = player.getItemInHand(hand)
        val candidates = ArrayList<BlockHitResult>()

        candidates += original

        // Litematica's own block first, then — where that was a neighbour rather than the target
        // itself — the target. Clicking the space a block is going into is a click a vanilla
        // server accepts as readily as any other, and without it a hopper or a log would have no
        // face left to choose from whenever Litematica had clicked something else to reach it,
        // which is what `easyPlaceClickAdjacent` makes it do for every block.
        val positions = if (clickPos == targetPos) listOf(clickPos) else listOf(clickPos, targetPos)

        for (position in positions) {
            for (face in facesFrom(original.direction)) {
                for ((across, up) in FACE_POINTS) {
                    candidates += BlockHitResult(pointOnFace(position, face, across, up), face, position, false)
                }
            }
        }

        // The invariant that makes all of this safe to do blind: whatever is claimed, the block
        // still lands exactly where Litematica meant it to.
        return candidates.filter {
            BlockPlaceContext(player, hand, stack, it).clickedPos == targetPos
        }
    }

    /** Every face, starting with the one Litematica chose. */
    private fun facesFrom(first: Direction): List<Direction> =
        listOf(first) + Direction.entries.filter { it != first }

    /**
     * A point on [face] of the block at [pos], given as fractions across the face.
     *
     * `up` is the vertical fraction on a side face, which is what decides a stair's or a slab's
     * half; on a top or bottom face there is no vertical to speak of and it runs along the block
     * instead, which is harmless and occasionally useful.
     */
    private fun pointOnFace(pos: BlockPos, face: Direction, across: Double, up: Double): Vec3 {
        val flush = if (face.axisDirection == Direction.AxisDirection.POSITIVE) 1.0 else 0.0
        return when (face.axis) {
            Direction.Axis.X -> Vec3(pos.x + flush, pos.y + up, pos.z + across)
            Direction.Axis.Y -> Vec3(pos.x + across, pos.y + flush, pos.z + up)
            Direction.Axis.Z -> Vec3(pos.x + across, pos.y + up, pos.z + flush)
        }
    }

    /**
     * Rotations worth claiming, least disruptive first.
     *
     * The player's own rotation leads, so that a block already facing correctly costs nothing and
     * claims nothing. After that come the directions the wanted state actually names and their
     * opposites — a furnace faces the player, a piston faces away, and trying both means neither
     * has to be known in advance — then every cardinal look for the blocks that read the nearest
     * direction rather than the horizontal one.
     *
     * The sixteen-step sweep is only generated for blocks that have somewhere to put it. Signs,
     * banners and skulls store a rotation far finer than six directions, and nothing else does.
     */
    private fun rotationCandidates(
        player: LocalPlayer,
        wanted: BlockState,
        properties: Collection<Property<*>>,
    ): List<Rotation> {
        val real = Rotation(player.yRot, player.xRot)
        val candidates = LinkedHashSet<Rotation>()
        candidates += real

        for (property in properties) {
            val direction = BlockStates.read(wanted, property) as? Direction ?: continue
            for (option in listOf(direction, direction.opposite)) {
                val look = lookingAlong(option)
                // Pitch is not private to the facing question — the server believes it for the
                // whole placement, and a claim of staring at the ceiling changes what a block that
                // reads the click height decides. So a horizontal answer is offered without
                // touching pitch before the version that does.
                if (option.axis.isHorizontal) candidates += Rotation(look.yaw, real.pitch)
                candidates += look
            }
        }

        for (direction in Direction.entries) candidates += lookingAlong(direction)

        if (properties.any { it.name == "rotation" }) {
            for (step in 0 until 16) candidates += Rotation(step * 22.5f - 180f, real.pitch)
        }

        return candidates.toList()
    }

    /**
     * The rotation of a player looking along [direction], in Minecraft's convention: yaw runs from
     * south through west, and pitch is negative looking up.
     */
    private fun lookingAlong(direction: Direction): Rotation = when (direction) {
        Direction.SOUTH -> Rotation(0f, 0f)
        Direction.WEST -> Rotation(90f, 0f)
        Direction.NORTH -> Rotation(180f, 0f)
        Direction.EAST -> Rotation(-90f, 0f)
        Direction.UP -> Rotation(0f, -90f)
        Direction.DOWN -> Rotation(0f, 90f)
    }
}
