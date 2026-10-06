package com.skystormer.aboutfaceeasyplace

import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket
import net.minecraft.network.protocol.game.ServerboundUseItemPacket

/**
 * Decides when this mod's uses may go out, so that Spigot and Paper take every one of them.
 *
 * Those servers silently drop a player's clicks once more than five arrive within 30 ms. Keeping
 * each tick under that was not enough: on a real connection the clicks of consecutive ticks can
 * reach the server together, and tuning note blocks lost close to half its clicks. So this mod's
 * uses go out a few at a time, and the next few only once the server has answered the last — by
 * then they have all arrived, and nothing sent afterwards can arrive alongside them.
 *
 * Every click is counted against the tick's few, not only this mod's: Litematica's placements and
 * the player's own reach the same limit. Only this mod's own wait for an answer, because the
 * others are not this mod's to hold back.
 */
object Clicks {

    /**
     * Clicks a tick, everyone's together. Two short of the five that Spigot and Paper allow, to
     * leave room for a placement Litematica makes in the next tick, which may arrive alongside.
     */
    private const val PER_TICK = 3

    /** The longest this mod's uses wait for the server to answer the last of them. */
    private const val PATIENCE_TICKS = 10

    private var sent = 0

    /** Whether this tick's clicks started with the last of this mod's uses answered. */
    private var open = false

    /** The number of the last of this mod's uses, until the server has answered it. */
    private var awaiting: Int? = null
    private var waited = 0

    /** How many of this mod's uses may go out now. */
    val left: Int
        get() {
            if (sent == 0) open = answered()
            return if (open) (PER_TICK - sent).coerceAtLeast(0) else 0
        }

    /** Called for every packet the client sends; see `ClientCommonPacketListenerImplMixin`. */
    @JvmStatic
    fun sending(packet: Packet<*>) {
        if (packet !is ServerboundUseItemOnPacket && packet !is ServerboundUseItemPacket) return
        if (sent == 0) open = answered()
        sent++
    }

    /** Records that this mod's uses, up to the one numbered [sequence], are on their way. */
    fun used(sequence: Int?) {
        awaiting = sequence
        waited = 0
    }

    private fun answered(): Boolean =
        awaiting.let { it == null || ServerTick.hasTicked(it) } || waited >= PATIENCE_TICKS

    /** Starts the next tick's allowance, once everything this tick has had its turn. */
    fun tick() {
        sent = 0
        waited++
    }

    fun forget() {
        sent = 0
        awaiting = null
        waited = 0
    }
}
