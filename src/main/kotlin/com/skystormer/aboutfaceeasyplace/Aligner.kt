package com.skystormer.aboutfaceeasyplace

import net.minecraft.client.player.LocalPlayer
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.player.Input
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.context.BlockPlaceContext
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.block.BaseRailBlock
import net.minecraft.world.level.block.DoorBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.Property
import net.minecraft.world.level.block.state.properties.RailShape
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.Vec3
import kotlin.math.abs

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
 * The combination to use is not guessed at. Candidates are tried by asking the item itself what it
 * would place, which is the same question the server will answer, so a combination that would not
 * really produce the wanted state is never claimed. A block that cannot be steered this way is
 * reported as such rather than placed wrongly.
 *
 * **The player has two yaws, and the server moves them at different times.** Most blocks read the
 * body's (stairs, furnaces, doors), which the server takes straight from each rotation packet.
 * Pistons, observers, dispensers, droppers, crafters and barrels read the head's, which the server
 * only brings level with the body once per tick. A claim made and withdrawn within one placement
 * never reaches the head. So the search is run twice: first as the server will really see it with
 * the head where it already is, and only if that cannot produce the block, again with the head
 * turned too — an answer the hook then delivers by holding the claim across a server tick.
 */
object Aligner {

    /** A yaw/pitch pair, in Minecraft's own convention: yaw 0 faces south, pitch -90 looks up. */
    data class Rotation(val yaw: Float, val pitch: Float)

    /**
     * What to claim: where the player appears to be looking, what they appear to have clicked, and
     * whether they appear to be crouching.
     */
    data class Placement(val hit: BlockHitResult, val rotation: Rotation, val sneak: Boolean)

    /** Why a placement was left alone. Carried so that the log can answer "why did nothing happen". */
    enum class Skip {
        /** The hand is not holding a block. */
        NOT_A_BLOCK,

        /** The schematic wants something the held item cannot place — Litematica is doing something else. */
        WRONG_BLOCK,

        /** The block has no orientation to get wrong, and Litematica's own click is a sound one. */
        NO_ORIENTATION,

        /** No click could be claimed that still lands the block where Litematica meant it to. */
        NO_CLICKS,

        /** Every candidate produced the same thing, so there was never anything to steer. */
        NOTHING_STEERABLE,

        /** No candidate produced a block state at all: this block cannot go here, facing any way. */
        NOTHING_PLACEABLE,
    }

    sealed interface Outcome {
        /** Leave Litematica's placement exactly as it is. */
        data class LeaveAlone(val why: Skip) : Outcome

        /** Place it, claiming [placement]. */
        data class Aligned(
            val placement: Placement,
            val pos: BlockPos,
            val wanted: BlockState,
            /** What the claimed placement was shown to produce, before the server sees it. */
            val expected: BlockState,
            /** What would have landed without the claim, which is what makes the log worth reading. */
            val baseline: BlockState?,
            /**
             * The claim has to reach the player's head as well as their body, which only a claim
             * held across a server tick does. See [Aligner].
             */
            val turnsHead: Boolean,
            /** Litematica's own click and the player's own rotation: nothing is being claimed. */
            val untouched: Boolean,
        ) : Outcome {
            val claimsRotation: Boolean get() = !untouched || turnsHead
        }

        /**
         * This block cannot be made to land the way the schematic asks for.
         *
         * Reported rather than placed anyway. A missing block shows up in Litematica's overlay as
         * something still to do; a wrong one has to be found and broken first.
         */
        data class Unsolvable(
            val pos: BlockPos,
            val wanted: BlockState,
            /** The nearest any candidate got, which is usually the whole explanation. */
            val closest: BlockState?,
            /** The only thing out of reach is a door's hinge, which its neighbours decide. */
            val hingeOnly: Boolean,
        ) : Outcome
    }

    /**
     * How many placements to simulate before settling for the best answer found so far.
     *
     * The sweep runs on the client thread in the middle of a placement, and a bounded wrong-ish
     * answer is worth more here than an unbounded right one.
     */
    private const val SIMULATION_BUDGET = 8192

    /** How far a click may sit from the centre of the block it names before vanilla rejects it. */
    private const val CLICK_TOLERANCE = 1.0000001

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
        "orientation", "rotation", "attachment", "vertical_direction", "hanging", "attached",
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

    /** Straight up and straight down, which is what decides the up-or-down blocks. */
    private val STEEP_PITCHES = listOf(-90f, 90f)

    private val CARDINAL_YAWS = listOf(0f, 90f, 180f, -90f)

    /**
     * @param schematic the world Litematica is building from, which is the only thing here that
     *   knows what the block is supposed to look like. Passed in rather than fetched so that the
     *   search can be exercised against a world of one's own choosing.
     * @param stack what is being placed. The item in [hand] when Litematica is placing; the item the
     *   schematic calls for when this is only looking ahead, before Litematica has picked it.
     * @param believed the rotation the server currently believes the player has — their own, or a
     *   claim already standing. A placement made at this rotation claims nothing.
     * @param heads every yaw the server's copy of the player's head might hold right now. More than
     *   one when the player has just turned, because the server only updates it once a tick; a
     *   placement is only trusted to the body-only claim if it comes out the same for all of them.
     */
    fun plan(
        player: LocalPlayer,
        hand: InteractionHand,
        stack: ItemStack,
        original: BlockHitResult,
        schematic: BlockGetter,
        believed: Rotation,
        heads: Collection<Float>,
    ): Outcome {
        val item = stack.item as? BlockItem ?: return Outcome.LeaveAlone(Skip.NOT_A_BLOCK)

        val targetPos = BlockPlaceContext(player, hand, stack, original).clickedPos
        val wanted = schematic.getBlockState(targetPos)

        // Litematica decides what goes where; this mod only decides which way round it goes. If
        // the held item cannot place what the schematic wants here, Litematica is doing something
        // this mod does not understand, and the safe move is to stay out of it. The item is the
        // test rather than the block because one item places several: a skull item places a wall
        // skull, a torch item a wall torch.
        if (wanted.isAir || wanted.block.asItem() !== item) return Outcome.LeaveAlone(Skip.WRONG_BLOCK)

        val properties = wanted.block.stateDefinition.properties
        val steerable = properties.filter { steerable(wanted, it) }

        // `shape` is only treated as orientation for a block that has no other orientation to go
        // on. A rail's shape is chosen by the placement and is the only thing worth matching; a
        // staircase's is chosen by its neighbours, and requiring it would mean no stair ever
        // matched everything asked of it — which would be correct but would also mean sweeping
        // every candidate, every time, to arrive at the answer the first few would have given.
        val preferred =
            if (steerable.isEmpty()) properties.filter { it.name in PREFERRED_PROPERTIES } else emptyList()
        val required = steerable + preferred
        val target = searchTarget(wanted)

        val originalUsable = usable(player, hand, stack, original, targetPos)
        // Nothing to orient, nothing to set by using it afterwards, and nothing wrong with the
        // click: the cheap way out, which is most blocks in most builds.
        if (required.isEmpty() && originalUsable && !Adjustments.hasAdjustable(wanted)) {
            return Outcome.LeaveAlone(Skip.NO_ORIENTATION)
        }

        val hits = hitCandidates(player, hand, stack, original, originalUsable, targetPos, wanted, properties)
        if (hits.isEmpty()) return Outcome.LeaveAlone(Skip.NO_CLICKS)
        val rotations = rotationCandidates(believed, wanted, properties)
        val baseline = if (originalUsable) Placement(original, believed, player.lastSentInput.shift()) else null

        // Everything tried that produced the wanted block at all, for settling on the nearest
        // thing when nothing matches outright. Only body-only answers go in, because those are
        // the ones that can be placed without a hold.
        val tried = ArrayList<Pair<Placement, BlockState>>(64)
        var producedAnything = false
        var simulations = 0

        val realYaw = player.yRot
        val realPitch = player.xRot
        val realInput = player.input.keyPresses
        var found: Pair<Placement, BlockState>? = null
        var turnsHead = false
        var baselineState: BlockState? = null

        // Sneaking as the server last heard of it comes first, and the other way second: a
        // hanging sign only hangs from its chains when placed crouching, and nothing else here
        // cares, so the second pass is almost never reached.
        val sneakingNow = player.lastSentInput.shift()
        val sneaks = if (required.isEmpty()) listOf(sneakingNow) else listOf(sneakingNow, !sneakingNow)

        fun simulate(hit: BlockHitResult, rotation: Rotation, sneak: Boolean, head: Float): BlockState? {
            simulations++
            player.yRot = rotation.yaw
            player.xRot = rotation.pitch
            Placing.headOverride = head
            if (sneak != realInput.shift()) player.input.keyPresses = withShift(realInput, sneak)
            else player.input.keyPresses = realInput
            return Placing.stateFor(item, BlockPlaceContext(player, hand, stack, hit))
        }

        try {
            search@ for (sneak in sneaks) {
                // Clicks outer, rotations inner. Litematica chose its click for reasons of its
                // own — a particular half for a slab, a face for a hopper — and a rotation claim
                // overrides nothing, so every rotation is tried against Litematica's own click
                // before any other click is considered.
                for (hit in hits) {
                    for (rotation in rotations) {
                        if (simulations >= SIMULATION_BUDGET) break@search
                        // The claim only reaches the body, so the head stays wherever the server
                        // might have it — and the answer has to hold for every one of those.
                        val placed = simulate(hit, rotation, sneak, heads.first()) ?: continue
                        producedAnything = true
                        if (heads.size > 1 && heads.drop(1).any { simulate(hit, rotation, sneak, it) != placed }) continue
                        if (placed.block !== wanted.block) continue

                        val placement = Placement(hit, rotation, sneak)
                        tried += placement to placed
                        if (placement == baseline) baselineState = placed

                        // An exact answer is worth stopping for: nothing later in the sweep can
                        // beat matching every property the placement has any say over.
                        if (matches(placed, target, required)) {
                            found = placement to placed
                            break@search
                        }
                    }
                }

                if (required.isEmpty()) continue
                // Only now is turning the head worth it: it means a claim that has to stand for a
                // tick, made ahead of time if the block was seen coming, or waited for if not.
                for (hit in hits) {
                    for (rotation in rotations) {
                        if (simulations >= SIMULATION_BUDGET) break@search
                        if (heads.all { it == rotation.yaw }) continue
                        val placed = simulate(hit, rotation, sneak, rotation.yaw) ?: continue
                        producedAnything = true
                        if (placed.block === wanted.block && matches(placed, target, required)) {
                            found = Placement(hit, rotation, sneak) to placed
                            turnsHead = true
                            break@search
                        }
                    }
                }
            }
        } finally {
            player.yRot = realYaw
            player.xRot = realPitch
            player.input.keyPresses = realInput
            Placing.headOverride = null
        }

        if (!producedAnything) {
            // Not one candidate produced a state. That is a block which cannot go here at all
            // rather than one facing the wrong way, and it is Litematica's business, not this
            // mod's: its own placement will fail in the same way and say so in its own terms.
            return Outcome.LeaveAlone(Skip.NOTHING_PLACEABLE)
        }

        if (found == null && tried.isNotEmpty()) {
            // No candidate got everything. Settle, in order of how much is being given up — and
            // work out what was ever really on offer rather than assuming, because a property no
            // candidate managed to vary was never this placement's to decide. That is what keeps a
            // block whose orientation is fixed, or decided by a neighbour, from being declared
            // impossible: a chest half only a neighbouring chest can settle, a bed's head, the
            // upper half of a door.
            val reachableSteerable = reachable(steerable, tried)
            val tiers = listOf(
                steerable,
                reachableSteerable + reachable(preferred, tried),
                reachableSteerable,
            ).filter { it.isNotEmpty() }.distinct()

            for (tier in tiers) {
                found = tried.firstOrNull { matches(it.second, target, tier) }
                if (found != null) break
            }

            if (found == null && reachableSteerable.isEmpty()) {
                // There was never anything steerable to get right, so the first sound candidate is
                // already as close as this can get. Refusing it would be refusing a block for a
                // reason that has nothing to do with orientation.
                found = if (baselineState != null) baseline!! to baselineState else tried.first()
                if (found.first == baseline) {
                    return Outcome.LeaveAlone(Skip.NOTHING_STEERABLE)
                }
            }
        }


        val chosen = found ?: run {
            val closest = closest(tried, wanted, steerable)
            // Anything that got everything but the hinge settles it: the rest was reachable, and
            // the hinge never is once a neighbouring door has decided it.
            val hingeOnly = wanted.block is DoorBlock && tried.any { (_, placed) ->
                orientationMismatch(placed, wanted).let { it.isNotEmpty() && it.all { m -> m.startsWith("hinge=") } }
            }
            return Outcome.Unsolvable(targetPos, wanted, closest, hingeOnly)
        }
        return Outcome.Aligned(
            placement = chosen.first,
            pos = targetPos,
            wanted = wanted,
            expected = chosen.second,
            baseline = baselineState,
            turnsHead = turnsHead,
            untouched = chosen.first == baseline && !turnsHead,
        )
    }

    /** [input] with only the sneak key changed. */
    fun withShift(input: Input, shift: Boolean) =
        Input(input.forward(), input.backward(), input.left(), input.right(), input.jump(), shift, input.sprint())

    /**
     * What the search should aim at, where that differs from what the schematic holds.
     *
     * A sloped rail only slopes because of the rail one block up beside it; no click makes one on
     * its own. The most a placement can do is lie along the right axis, so that the slope forms
     * the moment the rail above is there — which vanilla then does by itself.
     */
    private fun searchTarget(wanted: BlockState): BlockState {
        val rail = wanted.block as? BaseRailBlock ?: return wanted
        val property = rail.shapeProperty
        val flat = when (wanted.getValue(property)) {
            RailShape.ASCENDING_EAST, RailShape.ASCENDING_WEST -> RailShape.EAST_WEST
            RailShape.ASCENDING_NORTH, RailShape.ASCENDING_SOUTH -> RailShape.NORTH_SOUTH
            else -> return wanted
        }
        return if (property.possibleValues.contains(flat)) wanted.setValue(property, flat) else wanted
    }

    /**
     * The state that got closest, for the benefit of anyone reading the log after a decline.
     *
     * "Cannot place this" is not a useful thing to be told on its own. Which properties could not
     * be satisfied, and what the nearest attempt produced, usually is.
     */
    private fun closest(
        tried: List<Pair<Placement, BlockState>>,
        wanted: BlockState,
        steerable: List<Property<*>>,
    ): BlockState? = tried.minByOrNull { (_, placed) ->
        steerable.count { !BlockStates.agree(placed, wanted, it) }
    }?.second

    /**
     * The orientation properties on which two states of the same block disagree.
     *
     * Deliberately narrow: a stair's `shape` and a block's `waterlogged` are settled by neighbours
     * after the placement, and listing those as disagreements would bury the one line that matters
     * under noise that is not wrong.
     */
    fun orientationMismatch(actual: BlockState, wanted: BlockState): List<String> {
        if (actual.block !== wanted.block) {
            return listOf("block=${actual.block.descriptionId} (wanted ${wanted.block.descriptionId})")
        }
        return wanted.block.stateDefinition.properties
            .filter { steerable(wanted, it) && !BlockStates.agree(actual, wanted, it) }
            .map { "${it.name}=${BlockStates.read(actual, it)} (wanted ${BlockStates.read(wanted, it)})" }
    }

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
     * Whether the server would accept [hit] as a placement at [targetPos], and nothing else.
     *
     * Three things a click has to be. It has to land the block where Litematica meant it to. It
     * has to be within reach, and sit on the block it names — vanilla discards a click whose
     * position is further than a block from that block's centre, and Litematica's own click for
     * a skull, a banner or a sign is one of those. And it must not be on a block that does
     * something when clicked, because the server would use that block instead of placing: a
     * dropper would open.
     */
    fun usable(
        player: LocalPlayer,
        hand: InteractionHand,
        stack: ItemStack,
        hit: BlockHitResult,
        targetPos: BlockPos,
    ): Boolean {
        val center = Vec3.atCenterOf(hit.blockPos)
        val location = hit.location
        if (abs(location.x - center.x) >= CLICK_TOLERANCE ||
            abs(location.y - center.y) >= CLICK_TOLERANCE ||
            abs(location.z - center.z) >= CLICK_TOLERANCE
        ) return false
        if (!player.isWithinBlockInteractionRange(hit.blockPos, 1.0)) return false
        if (hit.blockPos != targetPos && Interaction.isInteractive(player.level(), hit.blockPos)) return false
        return BlockPlaceContext(player, hand, stack, hit).clickedPos == targetPos
    }

    /**
     * Clicks worth claiming, all of which put the block in the same place.
     *
     * Litematica's own click comes first when it is a sound one, so a block that is already going
     * down correctly is left completely alone. The rest are ordinary points on each face — both
     * as a click on the block next door, which is how a player mounts something on a wall, and
     * as a click on the target's own empty space, which a vanilla server accepts as readily and
     * which needs nothing next door at all. Between them they reach the blocks that read the face
     * they were clicked on — a hopper's spout, a log's axis, a slab's half, a skull on a wall —
     * and the blocks that read where on the face the click landed, such as a door's hinge.
     *
     * Every candidate is put through [usable], which is what makes this safe to do blind: a click
     * that would move the block, be rejected, or open something is never offered.
     */
    private fun hitCandidates(
        player: LocalPlayer,
        hand: InteractionHand,
        stack: ItemStack,
        original: BlockHitResult,
        originalUsable: Boolean,
        targetPos: BlockPos,
        wanted: BlockState,
        properties: Collection<Property<*>>,
    ): List<BlockHitResult> {
        val candidates = ArrayList<BlockHitResult>()
        if (originalUsable) candidates += original

        for (face in facesFor(original.direction, wanted, properties)) {
            // The block this face would be clicked on, if it were a neighbour's face rather than
            // the target's own. Same plane, same point, same face named; only the block differs.
            val neighbour = targetPos.relative(face.opposite)
            for ((across, up) in FACE_POINTS) {
                candidates += BlockHitResult(pointOnFace(neighbour, face, across, up), face, neighbour, false)
            }
            for ((across, up) in FACE_POINTS) {
                candidates += BlockHitResult(pointOnFace(targetPos, face, across, up), face, targetPos, false)
            }
        }

        return candidates.filterIndexed { index, hit ->
            (index == 0 && originalUsable) || usable(player, hand, stack, hit, targetPos)
        }
    }

    /**
     * Every face, in the order most likely to be the answer.
     *
     * Litematica's own leads, because a placement that needs nothing changed should cost nothing.
     * After it come the directions the wanted state names and their opposites, for the same reason
     * the rotations are ordered that way: a hopper points away from the face that was clicked, a
     * log lies along it, and trying the two faces that relate to the wanted direction before the
     * other four is the difference between a handful of simulations and a few hundred.
     */
    private fun facesFor(
        first: Direction,
        wanted: BlockState,
        properties: Collection<Property<*>>,
    ): List<Direction> {
        val ordered = LinkedHashSet<Direction>()
        ordered += first
        for (property in properties) {
            val direction = BlockStates.read(wanted, property) as? Direction ?: continue
            ordered += direction
            ordered += direction.opposite
        }
        ordered += Direction.entries
        return ordered.toList()
    }

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
     * has to be known in advance — then every cardinal look, then each of those turned to look
     * straight up and straight down, which is what a crafter needs to face down with its top
     * pointing somewhere in particular.
     *
     * The sixteen-step sweep is only generated for blocks that have somewhere to put it. Signs,
     * banners and skulls store a rotation far finer than six directions, and whether they go on
     * the floor, the ceiling or a wall is decided by the pitch, so the sweep is made at the
     * player's own pitch and at both extremes.
     */
    private fun rotationCandidates(
        real: Rotation,
        wanted: BlockState,
        properties: Collection<Property<*>>,
    ): List<Rotation> {
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
        for (pitch in STEEP_PITCHES) for (yaw in CARDINAL_YAWS) candidates += Rotation(yaw, pitch)

        if (properties.any { it.name == "rotation" }) {
            for (pitch in listOf(real.pitch) + STEEP_PITCHES) {
                for (step in 0 until 16) candidates += Rotation(step * 22.5f - 180f, pitch)
            }
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
