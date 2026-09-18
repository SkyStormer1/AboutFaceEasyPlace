package com.skystormer.aboutfaceeasyplace.verification

import com.skystormer.aboutfaceeasyplace.Aligner
import com.skystormer.aboutfaceeasyplace.BlockStates
import com.skystormer.aboutfaceeasyplace.Config
import com.skystormer.aboutfaceeasyplace.Log
import com.skystormer.aboutfaceeasyplace.PlacementAudit
import org.slf4j.RecordingLogger
import net.minecraft.client.player.LocalPlayer
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.InteractionHand
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.context.BlockPlaceContext
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.level.block.state.properties.Property
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.Vec3

/**
 * Exercises the search against stand-in blocks that place themselves the way vanilla's do.
 *
 * This runs against the stubs in `verification/stubs`, not against Minecraft, so what it proves is
 * that the search finds the rotation and click that produce a wanted state — not that any
 * particular Minecraft version behaves as the stand-ins do. The stand-ins are written from
 * vanilla's own placement rules and are worth keeping honest if they are ever edited.
 *
 * Run it with `verification/run.sh`.
 */
object SearchVerification {

    private val FACING = Property<Direction>("facing")
    private val AXIS = Property<Direction.Axis>("axis")
    private val HALF = Property<Half>("half")
    private val HANGING = Property<Boolean>("hanging")
    private val SHAPE = Property<RailShape>("shape")

    enum class Half { TOP, BOTTOM }

    enum class RailShape { NORTH_SOUTH, EAST_WEST }

    private fun stateOf(block: Block, vararg values: Pair<Property<*>, Comparable<*>>) =
        BlockState(block, mapOf(*values))

    /** Stairs face where the player is looking, and take their half from the click height. */
    private object Stairs : Block() {
        override fun getStateDefinition() = StateDefinition<Block, BlockState>(listOf(FACING, HALF))
        override fun getStateForPlacement(context: BlockPlaceContext): BlockState {
            val face = context.clickedFace
            val relativeY = context.clickLocation.y - context.clickedPos.y
            val half = if (face != Direction.DOWN && (face == Direction.UP || relativeY <= 0.5)) Half.BOTTOM else Half.TOP
            return stateOf(this, FACING to context.horizontalDirection, HALF to half)
        }
    }

    /** A hopper points away from the face that was clicked, and can never point up. */
    private object Hopper : Block() {
        override fun getStateDefinition() = StateDefinition<Block, BlockState>(listOf(FACING))
        override fun getStateForPlacement(context: BlockPlaceContext): BlockState {
            var facing = context.clickedFace.opposite
            if (facing == Direction.UP) facing = Direction.DOWN
            return stateOf(this, FACING to facing)
        }
    }

    /** A furnace faces the player, which is the opposite of where they are looking. */
    private object Furnace : Block() {
        override fun getStateDefinition() = StateDefinition<Block, BlockState>(listOf(FACING))
        override fun getStateForPlacement(context: BlockPlaceContext) =
            stateOf(this, FACING to context.horizontalDirection.opposite)
    }

    /** An observer faces away along whichever axis the player is nearest to looking down. */
    private object Observer : Block() {
        override fun getStateDefinition() = StateDefinition<Block, BlockState>(listOf(FACING))
        override fun getStateForPlacement(context: BlockPlaceContext) =
            stateOf(this, FACING to context.nearestLookingDirection.opposite)
    }

    /**
     * A log lies along the axis of the face that was clicked.
     *
     * Named `Pillar` rather than `Log` because a stand-in block called `Log` shadows the mod's own
     * logger inside this file, which is a confusing way to spend an afternoon.
     */
    private object Pillar : Block() {
        override fun getStateDefinition() = StateDefinition<Block, BlockState>(listOf(AXIS))
        override fun getStateForPlacement(context: BlockPlaceContext) =
            stateOf(this, AXIS to context.clickedFace.axis)
    }

    /** A lantern hangs when the face clicked was the underside of the block above. */
    private object Lantern : Block() {
        override fun getStateDefinition() = StateDefinition<Block, BlockState>(listOf(HANGING))
        override fun getStateForPlacement(context: BlockPlaceContext) =
            stateOf(this, HANGING to (context.clickedFace == Direction.DOWN))
    }

    /**
     * A rail lies across the way the player is facing, and `shape` is the only property it has.
     *
     * The awkward case: `shape` is also what a staircase stores, where it is decided by neighbours
     * rather than by the placement. A rail has nothing else to go on, so here it has to be matched;
     * a staircase has a facing and a half, so there it must not be required.
     */
    private object Rail : Block() {
        override fun getStateDefinition() = StateDefinition<Block, BlockState>(listOf(SHAPE))
        override fun getStateForPlacement(context: BlockPlaceContext) = stateOf(
            this,
            SHAPE to if (context.horizontalDirection.axis == Direction.Axis.Z) RailShape.NORTH_SOUTH
            else RailShape.EAST_WEST,
        )
    }

    /** Stone: nothing to get wrong. */
    private object Plain : Block() {
        override fun getStateForPlacement(context: BlockPlaceContext) = stateOf(this)
    }

    private val TARGET = BlockPos(10, 64, 20)

    private var failures = 0

    @JvmStatic
    fun main(args: Array<String>) {
        // A placement Litematica would have made: clicked the top face of the target itself, with
        // the player looking south, which is what leaves everything facing the wrong way.
        check(
            "stairs facing north, top half",
            Stairs, stateOf(Stairs, FACING to Direction.NORTH, HALF to Half.TOP),
            lookingYaw = 0f, expected = Expect.ALIGNED,
        )
        check(
            "stairs already correct",
            Stairs, stateOf(Stairs, FACING to Direction.SOUTH, HALF to Half.BOTTOM),
            lookingYaw = 0f, expected = Expect.LEAVE_ALONE,
        )
        check(
            "hopper facing east",
            Hopper, stateOf(Hopper, FACING to Direction.EAST),
            lookingYaw = 0f, expected = Expect.ALIGNED,
        )
        check(
            "hopper facing up, which vanilla has no value for",
            Hopper, stateOf(Hopper, FACING to Direction.UP),
            lookingYaw = 0f, expected = Expect.UNSOLVABLE,
        )
        check(
            "furnace facing west",
            Furnace, stateOf(Furnace, FACING to Direction.WEST),
            lookingYaw = 0f, expected = Expect.ALIGNED,
        )
        check(
            "observer facing up",
            Observer, stateOf(Observer, FACING to Direction.UP),
            lookingYaw = 0f, expected = Expect.ALIGNED,
        )
        check(
            "log lying east to west",
            Pillar, stateOf(Pillar, AXIS to Direction.Axis.X),
            lookingYaw = 0f, expected = Expect.ALIGNED,
        )
        check(
            "hanging lantern, whose orientation is a boolean",
            Lantern, stateOf(Lantern, HANGING to true),
            lookingYaw = 0f, expected = Expect.ALIGNED,
        )
        check(
            "hopper facing east, reached by clicking the block below",
            Hopper, stateOf(Hopper, FACING to Direction.EAST),
            lookingYaw = 0f, expected = Expect.ALIGNED, clickNeighbourBelow = true,
        )
        check(
            "rail running east to west, whose only property is shape",
            Rail, stateOf(Rail, SHAPE to RailShape.EAST_WEST),
            lookingYaw = 0f, expected = Expect.ALIGNED,
        )
        check(
            "rail already running the right way",
            Rail, stateOf(Rail, SHAPE to RailShape.NORTH_SOUTH),
            lookingYaw = 0f, expected = Expect.LEAVE_ALONE,
        )
        check(
            "stone, which has no orientation",
            Plain, stateOf(Plain),
            lookingYaw = 0f, expected = Expect.LEAVE_ALONE,
        )

        // Every horizontal facing from every starting rotation, which is where an off-by-one in
        // the yaw convention would show up and a single spot check would not.
        for (wantedFacing in listOf(Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST)) {
            for (yaw in listOf(-180f, -90f, 0f, 45f, 90f, 179f)) {
                check(
                    "stairs facing ${wantedFacing.name.lowercase()} from yaw $yaw",
                    Stairs, stateOf(Stairs, FACING to wantedFacing, HALF to Half.BOTTOM),
                    lookingYaw = yaw, expected = Expect.ANY_CORRECT,
                )
            }
        }

        checkLogging()

        println(if (failures == 0) "\nAll checks passed." else "\n$failures check(s) FAILED.")
        if (failures != 0) throw AssertionError("$failures check(s) failed")
    }

    /**
     * Runs the search and the audit with logging on, and renders every line that comes out.
     *
     * A log line with the wrong number of placeholders is invisible until the moment it fires,
     * which is the moment someone is already trying to work out why a block went down crooked.
     * This makes that a failure here instead.
     */
    private fun checkLogging() {
        println()
        RecordingLogger.reset()
        Config.verboseLogging = true
        try {
            // A search that finds an answer, and one that cannot.
            plan(Stairs, stateOf(Stairs, FACING to Direction.NORTH, HALF to Half.TOP), 0f)
            val unsolvable = plan(Hopper, stateOf(Hopper, FACING to Direction.UP), 0f)

            // The audit, on both the agreeing and the disagreeing paths.
            val aligned = plan(Hopper, stateOf(Hopper, FACING to Direction.EAST), 0f)
                    as? Aligner.Outcome.Aligned
            if (aligned == null) {
                failures++
                println("logging: expected an aligned hopper to audit")
            } else {
                PlacementAudit.expect(aligned)
                repeat(10) { PlacementAudit.tick { _ -> aligned.wanted } }
                PlacementAudit.expect(aligned)
                repeat(10) { PlacementAudit.tick { _ -> stateOf(Hopper, FACING to Direction.WEST) } }
            }

            Log.info("the unsolvable case reported: {}", unsolvable)
        } finally {
            Config.verboseLogging = false
        }

        RecordingLogger.LINES.forEach { println("  $it") }
        if (RecordingLogger.PROBLEMS.isEmpty()) {
            println(String.format("%-52s %s", "log lines render with matching placeholders", "ok"))
        } else {
            failures += RecordingLogger.PROBLEMS.size
            RecordingLogger.PROBLEMS.forEach { println("  FAILED: $it") }
        }
    }

    private fun plan(block: Block, wanted: BlockState, yaw: Float): Aligner.Outcome {
        val player = LocalPlayer()
        player.setYRot(yaw)
        player.setXRot(0f)
        player.held = ItemStack(BlockItem(block))
        BlockPlaceContext.SOLID.clear()
        val hit = BlockHitResult(
            Vec3(TARGET.x + 0.5, TARGET.y + 1.0, TARGET.z + 0.5), Direction.UP, TARGET, false
        )
        return Aligner.plan(player, InteractionHand.MAIN_HAND, hit) { pos ->
            if (pos == TARGET) wanted else stateOf(Plain)
        }
    }

    private enum class Expect { ALIGNED, LEAVE_ALONE, UNSOLVABLE, ANY_CORRECT }

    private fun check(
        name: String,
        block: Block,
        wanted: BlockState,
        lookingYaw: Float,
        expected: Expect,
        clickNeighbourBelow: Boolean = false,
    ) {
        val player = LocalPlayer()
        player.setYRot(lookingYaw)
        player.setXRot(0f)
        player.held = ItemStack(BlockItem(block))

        val hand = InteractionHand.MAIN_HAND

        // Litematica does not always click the target itself. With `easyPlaceClickAdjacent`, and
        // for blocks that need something to stand on, it clicks a neighbour and lets the block
        // land in the space beyond — which leaves the clicked face carrying the position rather
        // than the orientation.
        BlockPlaceContext.SOLID.clear()
        val original = if (clickNeighbourBelow) {
            val support = TARGET.relative(Direction.DOWN)
            BlockPlaceContext.SOLID.add(support)
            BlockHitResult(
                Vec3(support.x + 0.5, support.y + 1.0, support.z + 0.5), Direction.UP, support, false
            )
        } else {
            BlockHitResult(
                Vec3(TARGET.x + 0.5, TARGET.y + 1.0, TARGET.z + 0.5), Direction.UP, TARGET, false
            )
        }
        val schematic = BlockGetter { pos -> if (pos == TARGET) wanted else stateOf(Plain) }

        val outcome = Aligner.plan(player, hand, original, schematic)

        val verdict = when (outcome) {
            is Aligner.Outcome.LeaveAlone ->
                if (expected == Expect.LEAVE_ALONE) pass()
                else if (expected == Expect.ANY_CORRECT && produces(player, hand, original, block) == wanted) pass()
                else fail("left alone (${outcome.why}), but the block would land as ${produces(player, hand, original, block)}")

            is Aligner.Outcome.Unsolvable ->
                if (expected == Expect.UNSOLVABLE) pass()
                else fail("reported unsolvable; the closest any candidate got was ${outcome.closest}")

            is Aligner.Outcome.Aligned -> {
                // The claim is only worth anything if it really produces the wanted state, so the
                // check is not that a plan came back but that replaying it lands the right block.
                val placement = outcome.placement
                player.setYRot(placement.rotation.yaw)
                player.setXRot(placement.rotation.pitch)
                val landed = produces(player, hand, placement.hit, block)
                val steered = block.stateDefinition.properties.filter { agrees(landed, wanted, it) }
                when {
                    landed == null -> fail("the claimed placement produces nothing")
                    steered.size != block.stateDefinition.properties.size ->
                        fail("the claimed placement produces $landed, wanted $wanted")
                    expected == Expect.ALIGNED || expected == Expect.ANY_CORRECT -> pass()
                    else -> fail("aligned, but $expected was expected")
                }
            }
        }
        println(String.format("%-52s %s", name, verdict))
    }

    private fun agrees(a: BlockState?, b: BlockState, property: Property<*>) =
        a != null && BlockStates.agree(a, b, property)

    private fun produces(player: LocalPlayer, hand: InteractionHand, hit: BlockHitResult, block: Block) =
        block.getStateForPlacement(BlockPlaceContext(player, hand, player.getItemInHand(hand), hit))

    private fun pass() = "ok"
    private fun fail(why: String): String { failures++; return "FAILED: $why" }
}
