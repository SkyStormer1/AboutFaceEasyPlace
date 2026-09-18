package com.skystormer.aboutfaceeasyplace.verification

import com.skystormer.aboutfaceeasyplace.Aligner
import com.skystormer.aboutfaceeasyplace.BlockStates
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

    enum class Half { TOP, BOTTOM }

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

    /** A log lies along the axis of the face that was clicked. */
    private object Log : Block() {
        override fun getStateDefinition() = StateDefinition<Block, BlockState>(listOf(AXIS))
        override fun getStateForPlacement(context: BlockPlaceContext) =
            stateOf(this, AXIS to context.clickedFace.axis)
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
            Log, stateOf(Log, AXIS to Direction.Axis.X),
            lookingYaw = 0f, expected = Expect.ALIGNED,
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

        println(if (failures == 0) "\nAll checks passed." else "\n$failures check(s) FAILED.")
        if (failures != 0) throw AssertionError("$failures check(s) failed")
    }

    private enum class Expect { ALIGNED, LEAVE_ALONE, UNSOLVABLE, ANY_CORRECT }

    private fun check(name: String, block: Block, wanted: BlockState, lookingYaw: Float, expected: Expect) {
        val player = LocalPlayer()
        player.setYRot(lookingYaw)
        player.setXRot(0f)
        player.held = ItemStack(BlockItem(block))

        val hand = InteractionHand.MAIN_HAND
        val original = BlockHitResult(
            Vec3(TARGET.x + 0.5, TARGET.y + 1.0, TARGET.z + 0.5), Direction.UP, TARGET, false
        )
        val schematic = BlockGetter { pos -> if (pos == TARGET) wanted else stateOf(Plain) }

        val outcome = Aligner.plan(player, hand, original, schematic)

        val verdict = when (outcome) {
            is Aligner.Outcome.LeaveAlone ->
                if (expected == Expect.LEAVE_ALONE) pass()
                else if (expected == Expect.ANY_CORRECT && produces(player, hand, original, block) == wanted) pass()
                else fail("left alone, but the block would land as ${produces(player, hand, original, block)}")

            is Aligner.Outcome.Unsolvable ->
                if (expected == Expect.UNSOLVABLE) pass() else fail("reported unsolvable")

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
