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
 */
object FollowUp {

    /** How long to wait for the server's answer before giving up on a block. */
    private const val PATIENCE_TICKS = 40

    /** Ticks to let the server's answer replace the client's own prediction of the block. */
    private const val SETTLE_TICKS = 3

    private class Expected(val pos: BlockPos, val wanted: BlockState, val hand: InteractionHand, var ticks: Int = 0)

    private val expected = ArrayList<Expected>()

    fun expect(pos: BlockPos, wanted: BlockState, hand: InteractionHand) {
        if (expected.none { it.pos == pos }) expected += Expected(pos, wanted, hand)
    }

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
            if (level.getBlockState(entry.pos).block !== entry.wanted.block) {
                if (entry.ticks > PATIENCE_TICKS) entries.remove()
                continue
            }
            if (entry.ticks < SETTLE_TICKS) continue
            entries.remove()
            if (!player.isWithinBlockInteractionRange(entry.pos, 1.0)) continue
            EasyPlaceHook.useToMatch(gameMode, player, entry.hand, entry.pos, entry.wanted, connection)
        }
    }

    fun forget() = expected.clear()
}
