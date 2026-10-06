package com.skystormer.aboutfaceeasyplace

import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.world.InteractionHand
import net.minecraft.world.level.block.state.BlockState

/**
 * Finishes blocks that Litematica placed with its own protocol — in single player, or on a Carpet or
 * Servux server — by using them once they are down: a repeater's delay, a lone dust dot, an open door.
 *
 * Unlike the placements this mod makes itself, these cannot be finished straight away. Litematica's
 * protocol is decoded by the server, so the client does not know what was placed until the server
 * says; counting uses against the client's guess could cycle a repeater the server had already set.
 * So each placement is watched until the block shows up, and the uses are counted against that.
 *
 * It also pays off uses this mod's own placements could not make at once. Spigot and Paper drop
 * clicks that come too close together, so a note block's two dozen are sent a few at a time, as
 * [Clicks] allows, and once they have all been answered the block is counted again and topped up
 * if any went missing.
 */
object FollowUp {

    /** How long to wait for the server's answer before giving up on a block. */
    private const val PATIENCE_TICKS = 40

    /** Ticks to let the server's answer replace the client's own prediction of the block. */
    private const val SETTLE_TICKS = 3

    /**
     * Ticks to wait after the last use before checking that the block came out right, when the
     * server's acknowledgement of that use does not arrive first. Long enough for every use to
     * have been answered, so that a check never counts one still on its way and sends it again.
     */
    private const val CHECK_TICKS = 20

    /** Counts in a row that find none of the uses taken, before the block is left as it is. */
    private const val STALLS = 3

    private class Expected(
        val pos: BlockPos,
        val wanted: BlockState,
        val hand: InteractionHand,
        /** Uses still to send, as [Clicks] allows. */
        var owed: Int = 0,
        /** Whether uses have been sent, so the next count checks them rather than being the first. */
        var counted: Boolean = false,
        /** Ticks since this was expected, for giving up on a block that never shows. */
        var ticks: Int = 0,
        /** Ticks since the last use was sent, for letting the server answer before counting. */
        var quiet: Int = 0,
        /** The number of the last use sent, which [ServerTick] says when the server has handled. */
        var lastUse: Int? = null,
        /** Uses sent since the block was last counted. */
        var sinceCount: Int = 0,
        /** Uses sent in all, for the log. */
        var sent: Int = 0,
        /** Times the block has been counted again after uses went missing, for the log. */
        var recounts: Int = 0,
        /** Counts in a row that found none of the uses taken. */
        var stalls: Int = 0,
    )

    private val expected = ArrayList<Expected>()

    /** Watches a block Litematica placed with its own protocol, and finishes it once it is down. */
    fun expect(pos: BlockPos, wanted: BlockState, hand: InteractionHand) {
        if (expected.none { it.pos == pos }) expected += Expected(pos, wanted, hand)
    }

    /**
     * A block that has had [sent] uses, the last of them numbered [lastUse], and is owed [uses]
     * more: they are sent as [Clicks] allows, and the block is then counted to be sure the server
     * took them all. Even a block owed nothing is counted once its uses have been answered,
     * because the server may have dropped some of those too.
     */
    fun owe(pos: BlockPos, wanted: BlockState, hand: InteractionHand, sent: Int, uses: Int, lastUse: Int?) {
        expected.removeAll { it.pos == pos }
        expected += Expected(pos, wanted, hand, owed = uses, counted = true, lastUse = lastUse, sinceCount = sent, sent = sent)
    }

    /** Whether the block at [pos] still has uses on their way, or waiting to be checked. */
    fun isFinishing(pos: BlockPos): Boolean = expected.any { it.pos == pos }

    fun tick(minecraft: Minecraft) {
        if (expected.isEmpty()) return
        val player = minecraft.player ?: return forget()
        val gameMode = minecraft.gameMode ?: return forget()
        val connection = minecraft.connection ?: return forget()
        val level = player.level()

        val entries = expected.iterator()
        while (entries.hasNext()) {
            val entry = entries.next()
            entry.ticks++
            val state = level.getBlockState(entry.pos)
            if (state.block !== entry.wanted.block) {
                // Not down yet, or broken since: nothing to use either way.
                if (entry.counted || entry.ticks > PATIENCE_TICKS) {
                    Log.detail("{} at {} is no longer there to finish", entry.wanted.block.descriptionId, entry.pos)
                    entries.remove()
                }
                continue
            }
            if (!player.isWithinBlockInteractionRange(entry.pos, 1.0)) {
                Log.detail("{} at {} went out of reach before it was finished", entry.wanted.block.descriptionId, entry.pos)
                entries.remove()
                continue
            }

            if (entry.owed > 0) {
                val sent = EasyPlaceHook.useToMatch(gameMode, player, entry.hand, entry.pos, entry.wanted, connection, uses = entry.owed)
                if (sent == null) {
                    entries.remove()
                    continue
                }
                // The server has not answered the last uses yet, or this tick has no room left.
                if (sent == 0) continue
                entry.owed -= sent
                entry.sinceCount += sent
                entry.sent += sent
                entry.quiet = 0
                entry.lastUse = ServerTick.latest(player)
                continue
            }

            entry.quiet++
            // Counted as soon as the server has handled the last use: it sends the block back
            // before it says so. The fixed wait is only for when that word does not come.
            val answered = entry.counted && ServerTick.hasTicked(entry.lastUse)
            if (!answered && entry.quiet < (if (entry.counted) CHECK_TICKS else SETTLE_TICKS)) continue

            val needed = Adjustments.usesNeeded(state, entry.wanted)
            if (needed == 0) {
                if (entry.counted) {
                    Log.detail(
                        "{} at {} finished after {} tick(s): {} use(s), {} recount(s)",
                        entry.wanted.block.descriptionId, entry.pos, entry.ticks, entry.sent, entry.recounts,
                    )
                }
                entries.remove()
                continue
            }
            if (entry.counted) {
                entry.stalls = if (needed >= entry.sinceCount) entry.stalls + 1 else 0
                if (entry.stalls >= STALLS) {
                    Log.warn(
                        "{} at {} still needs {} use(s) to match the schematic; the server is not taking them",
                        entry.wanted.block.descriptionId, entry.pos, needed,
                    )
                    entries.remove()
                    continue
                }
                entry.recounts++
                Log.detail(
                    "{} at {} came out {} use(s) short of the {} sent; making them again",
                    entry.wanted.block.descriptionId, entry.pos, needed, entry.sinceCount,
                )
            }
            entry.owed = needed
            entry.sinceCount = 0
            entry.counted = true
        }
    }

    fun forget() = expected.clear()
}
