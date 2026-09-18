package com.skystormer.aboutfaceeasyplace

import net.minecraft.core.BlockPos
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.block.state.BlockState

/**
 * Checks, a moment later, whether the block the server actually placed is the one that was asked
 * for.
 *
 * This is the only part of the mod that can tell the difference between a plan that was wrong and a
 * plan that was right but was not believed. Everything up to the placement is reasoning about what
 * a vanilla server *would* do; this is the one place that finds out what it *did*. When a claimed
 * rotation is ignored, or a click is rejected, or a block turns out to read something nobody
 * expected, the mismatch shows up here and nowhere else.
 *
 * It runs only with verbose logging switched on, and it keeps a bounded queue, so leaving it on
 * during a long build costs a fixed amount of memory and one block-state lookup per placement.
 */
object PlacementAudit {

    /**
     * How long to wait before looking.
     *
     * Long enough for the server's answer to have arrived and for the client's own prediction to
     * have been corrected if it was wrong, short enough that the block is unlikely to have been
     * changed by anything else in the meantime. Half a second.
     */
    private const val DELAY_TICKS = 10

    /** A cap, so that a build with a badly lagging server cannot grow this without limit. */
    private const val MAX_PENDING = 64

    private class Pending(
        val pos: BlockPos,
        val wanted: BlockState,
        val expected: BlockState,
        var ticksLeft: Int,
    )

    private val pending = ArrayList<Pending>()

    /**
     * @param expected what the client predicts is there now, after the placement and any uses that
     *   followed it — the thing the server is expected to agree with.
     */
    fun expect(pos: BlockPos, wanted: BlockState, expected: BlockState) {
        if (!Log.detailed) return
        if (pending.size >= MAX_PENDING) pending.removeAt(0)
        pending += Pending(pos, wanted, expected, DELAY_TICKS)
    }

    fun tick(level: BlockGetter) {
        if (pending.isEmpty()) return

        val entries = pending.iterator()
        while (entries.hasNext()) {
            val entry = entries.next()
            entry.ticksLeft--
            if (entry.ticksLeft > 0) continue
            entries.remove()
            report(entry, level.getBlockState(entry.pos))
        }
    }

    private fun report(entry: Pending, actual: BlockState) {
        val mismatch = Aligner.orientationMismatch(actual, entry.wanted) + Adjustments.mismatch(actual, entry.wanted)
        if (mismatch.isEmpty()) return

        // Worth a warning rather than detail, even though only verbose logging gets here: this is
        // the mod having claimed something and been wrong about it, which is the one thing it is
        // supposed never to do.
        Log.warn(
            "at {} the server settled on {}, not the {} that was claimed and expected. Disagreeing on: {}",
            entry.pos, actual, entry.expected, mismatch.joinToString(", "),
        )
    }

    /** Drops everything outstanding, for when the world it referred to has gone. */
    fun forget() = pending.clear()
}
