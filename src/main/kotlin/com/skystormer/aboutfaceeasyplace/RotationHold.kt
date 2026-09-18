package com.skystormer.aboutfaceeasyplace

import net.minecraft.client.multiplayer.ClientPacketListener
import net.minecraft.client.player.LocalPlayer
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket
import net.minecraft.world.InteractionHand

/**
 * Keeps a rotation claim in force across server ticks, for the blocks that read the player's head.
 *
 * Pistons, observers, dispensers, droppers, crafters and barrels take their facing from the
 * player's head, and a vanilla server only brings its copy of the head level with the body once per
 * tick. A claim sent with the placement arrives, is used for the body, and is gone again before
 * that tick comes round, so the head never sees it. A claim that has been standing for a tick has
 * already turned it.
 *
 * Two kinds of claim stand here, and every movement report the client sends carries whichever is in
 * force instead of the real rotation. The camera never moves; only what the server is told does.
 *
 *  - **Anticipated** — set by [Anticipation] every tick, for the schematic block under the
 *    crosshair, before anything is clicked. By the time Easy Place places it the head has long
 *    since turned, and the placement goes through at once. This is the ordinary case.
 *  - **Held** — the fallback, for a click that arrives before an anticipated claim could stand:
 *    the claim is sent, kept for [HOLD_TICKS], and the placement made when it ends. Nothing else is
 *    placed meanwhile, because the server would place it under the claim.
 */
object RotationHold {

    /**
     * Client ticks a held claim is kept before its placement is made.
     *
     * The claim and the placement must reach the server either side of one of its ticks, which two
     * client ticks apart they do unless the connection bunches packets together.
     */
    private const val HOLD_TICKS = 2

    /** Ticks a claim must have stood before the server's head can be relied on to hold it. */
    private const val SETTLED_TICKS = 2

    /** Ticks after a held placement during which the server's head may still hold its claim. */
    private const val AFTERMATH_TICKS = 1

    private class Held(
        val aligned: Aligner.Outcome.Aligned,
        val hand: InteractionHand,
        var ticks: Int = 0,
    )

    private var held: Held? = null

    private var anticipated: Aligner.Rotation? = null
    private var anticipatedTicks = 0

    /** The rotation every outgoing movement report is to carry, or null to leave them alone. */
    @Volatile
    private var inForce: Aligner.Rotation? = null

    private var aftermath = 0
    private var lastClaimYaw: Float? = null

    /** Whether the server is being told anything other than the player's own rotation. */
    val claiming: Boolean get() = inForce != null

    /** Whether a held claim is in force, or has only just ended. */
    val busy: Boolean get() = held != null || aftermath > 0

    /** The rotation the server believes the player has, which a placement is measured against. */
    fun believed(player: LocalPlayer): Aligner.Rotation =
        inForce ?: Aligner.Rotation(player.yRot, player.xRot)

    /**
     * Every yaw the server's copy of the head might hold right now.
     *
     * A claim that has stood long enough is the only answer. Otherwise: the yaw the client last
     * reported, the one it is about to, the one it had a tick ago — and any claim that has only
     * just been made or withdrawn, which the server may or may not have caught up with.
     */
    fun serverHeads(player: LocalPlayer): List<Float> {
        val standing = anticipated
        if (held == null && standing != null && anticipatedTicks >= SETTLED_TICKS) return listOf(standing.yaw)

        val heads = LinkedHashSet<Float>()
        heads += Placing.lastSentYaw(player)
        heads += player.yRot
        heads += player.yRotO
        standing?.let { heads += it.yaw }
        lastClaimYaw?.let { heads += it }
        return heads.toList()
    }

    /**
     * Sets or clears the anticipated claim. Sent at once, and then carried by every movement
     * report; re-sent each tick too, because a player standing still sends no reports to carry it.
     */
    fun anticipate(rotation: Aligner.Rotation?, player: LocalPlayer, connection: ClientPacketListener) {
        if (held != null) return
        if (rotation == anticipated) {
            if (rotation != null) anticipatedTicks++
            rotation?.let { send(it, player, connection) }
            return
        }

        anticipated?.let { lastClaimYaw = it.yaw }
        anticipated = rotation
        anticipatedTicks = 0
        inForce = rotation
        send(rotation ?: Aligner.Rotation(player.yRot, player.xRot), player, connection)
    }

    fun begin(
        aligned: Aligner.Outcome.Aligned,
        hand: InteractionHand,
        player: LocalPlayer,
        connection: ClientPacketListener,
    ) {
        val rotation = aligned.placement.rotation
        inForce = rotation
        lastClaimYaw = rotation.yaw
        held = Held(aligned, hand)
        send(rotation, player, connection)
    }

    fun tick() {
        if (aftermath > 0 && --aftermath == 0) lastClaimYaw = null
        val current = held ?: return
        current.ticks++
        if (current.ticks < HOLD_TICKS) return

        held = null
        // Handed back to the anticipated claim, or to nothing, before the placement — so that the
        // rotation sent after it is the one the server should go back to believing. The placement
        // is a different packet and is unaffected.
        inForce = anticipated
        aftermath = AFTERMATH_TICKS
        EasyPlaceHook.placeHeld(current.aligned, current.hand)
    }

    /** Called for every packet the client sends; see `ClientCommonPacketListenerImplMixin`. */
    @JvmStatic
    fun rewrite(packet: Packet<*>): Packet<*> {
        val rotation = inForce ?: return packet
        if (packet !is ServerboundMovePlayerPacket) return packet
        return when {
            packet.hasPosition() -> ServerboundMovePlayerPacket.PosRot(
                packet.getX(0.0), packet.getY(0.0), packet.getZ(0.0),
                rotation.yaw, rotation.pitch, packet.isOnGround, packet.horizontalCollision(),
            )

            packet.hasRotation() -> ServerboundMovePlayerPacket.Rot(
                rotation.yaw, rotation.pitch, packet.isOnGround, packet.horizontalCollision(),
            )

            else -> packet
        }
    }

    /**
     * The rotation packet that puts the server back where it should be after a placement: the
     * standing claim if there is one, the player's own rotation if not. Sent with nothing in force
     * for a moment, so that it is not itself rewritten on the way out.
     */
    fun restore(player: LocalPlayer, connection: ClientPacketListener) = send(believed(player), player, connection)

    /**
     * Sends [rotation] exactly as given. The rewrite is lifted for the one packet, because this mod's
     * own claims are the one thing it must not turn into something else.
     */
    fun send(rotation: Aligner.Rotation, player: LocalPlayer, connection: ClientPacketListener) {
        val keep = inForce
        inForce = null
        try {
            connection.send(EasyPlaceHook.rotationPacket(rotation.yaw, rotation.pitch, player))
        } finally {
            inForce = keep
        }
    }

    /** Drops every claim, for when the world it was made in has gone. */
    fun forget() {
        held = null
        anticipated = null
        anticipatedTicks = 0
        inForce = null
        aftermath = 0
        lastClaimYaw = null
    }
}
