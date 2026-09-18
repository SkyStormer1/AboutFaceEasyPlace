package com.skystormer.aboutfaceeasyplace

import net.minecraft.client.Minecraft

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

    fun shouldAlign(): Boolean {
        // Single player runs the placement through the same client that decided what to place, so
        // Litematica's V3 protocol is honoured in full and there is nothing here to fix.
        if (Minecraft.getInstance().isLocalServer) return false
        return Litematica.effectiveProtocol() in UNSPOKEN
    }

    /**
     * Says once, when it becomes relevant, that a hand-picked protocol is being left to it.
     *
     * Only reached on a server, and only while Easy Place is actually placing blocks, so a player
     * who has never turned Easy Place on never hears about any of this. On a Carpet or Servux
     * server the note is accurate and harmless; on a vanilla server where the protocol has been
     * pinned by hand it is the explanation for every ghost block they are about to see.
     */
    fun adviseIfProtocolNegotiated() {
        if (advised) return
        if (Litematica.effectiveProtocol() !in NEGOTIATED) return
        advised = true
        Messages.protocolNegotiated()
    }

    /** Forgets what was learned about a server, for when the next one is a different one. */
    fun forget() {
        advised = false
    }
}
