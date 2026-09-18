package com.skystormer.aboutfaceeasyplace

import net.minecraft.core.Direction
import net.minecraft.world.level.block.ComparatorBlock
import net.minecraft.world.level.block.DaylightDetectorBlock
import net.minecraft.world.level.block.DoorBlock
import net.minecraft.world.level.block.FenceGateBlock
import net.minecraft.world.level.block.LeverBlock
import net.minecraft.world.level.block.NoteBlock
import net.minecraft.world.level.block.RedStoneWireBlock
import net.minecraft.world.level.block.RepeaterBlock
import net.minecraft.world.level.block.TrapDoorBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.Property
import net.minecraft.world.level.block.state.properties.RedstoneSide

/**
 * Settings a block only takes once it is down: a repeater's delay, a comparator's mode, whether a
 * door stands open.
 *
 * No placement can set these. On a vanilla server the only way to them is the way a player gets
 * there — by using the block after placing it — and that is what this does: works out how many
 * uses turn what was placed into what the schematic asks for, and makes them straight after the
 * placement, while the claim that placed the block is still in force.
 *
 * Each rule is written against one vanilla block class and the property it cycles, rather than
 * against a property name. A modded block with an `open` or a `delay` may do anything at all when
 * used, and a click whose effect is not known is not one worth making.
 */
object Adjustments {

    /** A repeater cycles through four delays. */
    private const val REPEATER_DELAYS = 4

    /** A note block cycles through twenty-five notes. */
    private const val NOTES = 25

    /** How many uses of the block turn [placed] into [wanted]; zero when none will. */
    fun usesNeeded(placed: BlockState, wanted: BlockState): Int {
        if (placed.block !== wanted.block) return 0
        return when (val block = wanted.block) {
            is RepeaterBlock -> steps(placed, wanted, RepeaterBlock.DELAY, REPEATER_DELAYS)
            is NoteBlock -> steps(placed, wanted, NoteBlock.NOTE, NOTES)
            is ComparatorBlock -> toggle(placed, wanted, ComparatorBlock.MODE)
            is DaylightDetectorBlock -> toggle(placed, wanted, DaylightDetectorBlock.INVERTED)
            is LeverBlock -> toggle(placed, wanted, LeverBlock.POWERED)
            is FenceGateBlock -> toggle(placed, wanted, FenceGateBlock.OPEN)
            is DoorBlock -> if (block.type().canOpenByHand()) toggle(placed, wanted, DoorBlock.OPEN) else 0
            is TrapDoorBlock -> if (Placing.opensByHand(block)) toggle(placed, wanted, TrapDoorBlock.OPEN) else 0
            is RedStoneWireBlock -> dustToggle(placed, wanted)
            else -> 0
        }
    }

    /**
     * The settings on which [actual] and [wanted] still disagree, for the audit to report.
     *
     * Limited to the settings this can change, so that a comparator's `powered` — decided by the
     * circuit, not the placement — is never reported as a failure.
     */
    fun mismatch(actual: BlockState, wanted: BlockState): List<String> {
        if (actual.block !== wanted.block) return emptyList()
        return adjustable(wanted)
            .filter { !BlockStates.agree(actual, wanted, it) }
            .map { "${it.name}=${BlockStates.read(actual, it)} (wanted ${BlockStates.read(wanted, it)})" }
    }

    private fun adjustable(wanted: BlockState): List<Property<*>> = when (val block = wanted.block) {
        is RepeaterBlock -> listOf(RepeaterBlock.DELAY)
        is NoteBlock -> listOf(NoteBlock.NOTE)
        is ComparatorBlock -> listOf(ComparatorBlock.MODE)
        is DaylightDetectorBlock -> listOf(DaylightDetectorBlock.INVERTED)
        is LeverBlock -> listOf(LeverBlock.POWERED)
        is FenceGateBlock -> listOf(FenceGateBlock.OPEN)
        is DoorBlock -> if (block.type().canOpenByHand()) listOf(DoorBlock.OPEN) else emptyList()
        is TrapDoorBlock -> if (Placing.opensByHand(block)) listOf(TrapDoorBlock.OPEN) else emptyList()
        // The four sides are what tell a dot from a cross, but only while the dust is on its own.
        is RedStoneWireBlock -> if (isDot(wanted) || isCross(wanted)) SIDES else emptyList()
        else -> emptyList()
    }

    private fun <T : Comparable<T>> toggle(placed: BlockState, wanted: BlockState, property: Property<T>) =
        if (placed.getValue(property) == wanted.getValue(property)) 0 else 1

    /** Uses to step an integer setting forwards, wrapping, from where it is to where it should be. */
    private fun steps(
        placed: BlockState,
        wanted: BlockState,
        property: Property<Int>,
        count: Int,
    ): Int = Math.floorMod(wanted.getValue(property) - placed.getValue(property), count)

    /**
     * Redstone dust on its own is either a cross or a dot, and using it swaps one for the other.
     *
     * Only between those two. Dust with neighbours is shaped by them, and using it does nothing —
     * so anything but a lone cross that should be a dot, or the other way round, is left alone.
     */
    private fun dustToggle(placed: BlockState, wanted: BlockState): Int =
        if ((isCross(placed) && isDot(wanted)) || (isDot(placed) && isCross(wanted))) 1 else 0

    private val SIDES: List<Property<RedstoneSide>> = Direction.Plane.HORIZONTAL.map {
        RedStoneWireBlock.PROPERTY_BY_DIRECTION.getValue(it)
    }

    private fun isDot(state: BlockState) = SIDES.all { state.getValue(it) == RedstoneSide.NONE }

    private fun isCross(state: BlockState) = SIDES.all { state.getValue(it) != RedstoneSide.NONE }

    /** Whether [state] has anything [usesNeeded] could ever act on, for deciding what to audit. */
    fun hasAdjustable(state: BlockState): Boolean = adjustable(state).isNotEmpty()
}
