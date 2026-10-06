package com.skystormer.aboutfaceeasyplace

import net.minecraft.client.multiplayer.ClientPacketListener
import net.minecraft.client.player.LocalPlayer
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket
import net.minecraft.network.protocol.game.ServerboundUseItemPacket
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
 * force instead of the real rotation — as does every use of an item in the air, which carries a
 * rotation too. The camera never moves; only what the server is told does.
 *
 *  - **Anticipated** — set by [Anticipation] every tick, for the schematic block under the
 *    crosshair, before anything is clicked. By the time Easy Place places it the head has long
 *    since turned, and the placement goes through at once. This is the ordinary case.
 *  - **Held** — the fallback, for a click that arrives before an anticipated claim could stand:
 *    the claim is sent and kept until [ServerTick] hears that the server has ticked, and the
 *    placement is made then. Nothing else is placed meanwhile, because the server would place it
 *    under the claim.
 */
object RotationHold {

    /**
     * The claim and the placement must reach the server either side of one of its ticks, or the
     * head never turns and an observer faces the player. [ServerTick] says when that tick has
     * passed, and the placement goes then. These counts are only for when it cannot be asked, or
     * its answer never comes: two client ticks proved too few to be sure of, so they are generous.
     */
    private const val HOLD_TICKS = 4

    /** Ticks a claim must have stood before the server's head can be relied on to hold it. */
    private const val SETTLED_TICKS = 3

    /** The longest a hold waits for the server's answer before placing anyway. */
    private const val GIVE_UP_TICKS = 10

    /** Ticks after a held placement during which the server's head may still hold its claim. */
    private const val AFTERMATH_TICKS = 1

    private class Held(
        val aligned: Aligner.Outcome.Aligned,
        val hand: InteractionHand,
        /** What [ServerTick] is waiting to hear back, or null if it could not ask. */
        val question: Int?,
        var ticks: Int = 0,
    )

    private var held: Held? = null

    private var anticipated: Aligner.Rotation? = null
    private var anticipatedTicks = 0

    /** What [ServerTick] is waiting to hear back for the anticipated claim, or null. */
    private var anticipatedQuestion: Int? = null

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
        if (held == null && standing != null && (ServerTick.hasTicked(anticipatedQuestion) || anticipatedTicks >= SETTLED_TICKS)) {
            return listOf(standing.yaw)
        }

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
        anticipatedQuestion = rotation?.let { ServerTick.ask(player, connection, it) }
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
        send(rotation, player, connection)
        held = Held(aligned, hand, ServerTick.ask(player, connection, rotation))
    }

    fun tick() {
        if (aftermath > 0 && --aftermath == 0) lastClaimYaw = null
        val current = held ?: return
        current.ticks++
        val ready = when {
            current.question == null -> current.ticks >= HOLD_TICKS
            ServerTick.hasTicked(current.question) -> true
            current.ticks >= GIVE_UP_TICKS -> true.also {
                Log.detail("no word from the server after {} ticks; placing {} anyway", current.ticks, current.aligned.wanted)
            }
            else -> false
        }
        if (!ready) return

        held = null
        // The hold turned the server's head away from whatever was anticipated before it, so that
        // claim is dropped rather than resumed: resumed, it still counted as settled, and the next
        // barrel went down facing the way the hold had turned the head. Anticipation claims it
        // afresh next tick, and it settles again from there.
        anticipated = null
        anticipatedTicks = 0
        anticipatedQuestion = null
        inForce = null
        aftermath = AFTERMATH_TICKS
        EasyPlaceHook.placeHeld(current.aligned, current.hand)
    }

    /** Called for every packet the client sends; see `ClientCommonPacketListenerImplMixin`. */
    @JvmStatic
    fun rewrite(packet: Packet<*>): Packet<*> {
        val rotation = inForce ?: return packet
        // A use of the item in the air carries a rotation too, and the server turns the player to
        // it: vanilla sends one with the real rotation whenever a held click places nothing.
        if (packet is ServerboundUseItemPacket) {
            return ServerboundUseItemPacket(packet.hand, packet.sequence, rotation.yaw, rotation.pitch)
        }
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
        anticipatedQuestion = null
        inForce = null
        aftermath = 0
        lastClaimYaw = null
    }
}
