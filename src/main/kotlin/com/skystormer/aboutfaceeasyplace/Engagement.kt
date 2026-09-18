package com.skystormer.aboutfaceeasyplace

import net.minecraft.client.Minecraft
import net.minecraft.world.level.block.CeilingHangingSignBlock
import net.minecraft.world.level.block.WallHangingSignBlock
import net.minecraft.world.level.block.state.BlockState

/**
 * Decides whether this mod has any business in a placement.
 *
 * The question is not "is this a vanilla server" but "is anything already carrying the orientation
 * for us", and Litematica answers it as a side effect of choosing its own protocol. On `Auto` it
 * looks for Carpet or Servux and settles on V2 or V3 when it finds one, on V3 in single player,
 * and on slabs-only when it finds nothing — which is the vanilla server case, and the only one
 * where orientation is going unspoken. Reading the protocol Litematica settled on is therefore a
 * better test than any detecting this mod could do for itself, and it costs nothing to keep right
 * when the server changes.
 *
 * Standing aside on a Carpet or Servux server is deliberate rather than cautious. Those protocols
 * carry things a click and a look cannot: a repeater's delay, a comparator's mode. Taking over
 * would trade a complete answer for a partial one.
 */
object Engagement {

    /** Protocols that leave orientation to be worked out from the placement itself. */
    private val UNSPOKEN = setOf("NONE", "SLAB_ONLY")

    /** Protocols that need the server to be in on it, and produce ghost blocks when it is not. */
    private val NEGOTIATED = setOf("V2", "V3")

    private var advised = false

    /** The protocol Litematica has settled on, for the log to quote. */
    fun protocol(): String? = Litematica.effectiveProtocol()

    fun shouldAlign(): Boolean {
        // Single player runs the placement through the same client that decided what to place, so
        // Litematica's V3 protocol is honoured in full and there is nothing here to fix.
        if (Minecraft.getInstance().isLocalServer) return false
        return Litematica.effectiveProtocol() in UNSPOKEN
    }

    /**
     * Whether hanging signs get this mod's full treatment even though Litematica's own protocol is
     * in force. In single player it is, and it gets hanging signs wrong: which way round, and
     * whether they hang from chains.
     */
    fun alignsHangingSigns(): Boolean = Minecraft.getInstance().isLocalServer

    fun isHangingSign(state: BlockState): Boolean =
        state.block is CeilingHangingSignBlock || state.block is WallHangingSignBlock

    /**
     * Says once, when it becomes relevant, which protocol is being left to carry orientation.
     *
     * The same line covers both reasons a negotiated protocol can be in force, because from here
     * they are indistinguishable and the advice is the same either way: Litematica detected Carpet
     * or Servux and chose it, in which case the note is accurate and nothing is wrong, or it was
     * pinned by hand on a server that supports neither, in which case it is the explanation for
     * every ghost block about to appear.
     *
     * Only reached on a server, and only while Easy Place is actually placing blocks, so a player
     * who has never turned Easy Place on never hears about any of this.
     */
    fun adviseIfProtocolNegotiated() {
        if (advised) return
        val protocol = Litematica.effectiveProtocol() ?: return
        if (protocol !in NEGOTIATED) return
        advised = true
        Messages.protocolNegotiated(protocol)
    }

    /** Forgets what was learned about a server, for when the next one is a different one. */
    fun forget() {
        advised = false
    }
}
