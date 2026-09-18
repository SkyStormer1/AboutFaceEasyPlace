package com.skystormer.aboutfaceeasyplace

import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.MultiPlayerGameMode
import net.minecraft.client.player.LocalPlayer
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.phys.BlockHitResult

/**
 * Sits in front of the one placement Litematica makes for Easy Place, and nothing else.
 *
 * A vanilla server works out a block's facing for itself, from the state the player appears to be
 * in when the placement arrives, and a client-side mod cannot overrule that. So this does not try
 * to. It arranges the answer instead: the claimed rotation is sent, the placement is made, and the
 * player's real rotation is sent straight back — three packets in a fixed order, all three of them
 * things a player could genuinely have sent, and no window in between for anything else to be
 * decided under the claim.
 *
 * The claim is also applied to the client's own copy of the player while the placement runs. That
 * is not for the server's benefit, which is already handled by the packet, but for the client's:
 * the block it predicts locally is then the block the server is about to place, so the two agree
 * from the start and there is no correction to watch arrive. It lasts for the length of one method
 * call, well inside the tick, so nothing renders from it and the camera does not move.
 */
object EasyPlaceHook {

    /** Set while this mod is re-entering the placement it is itself making. */
    private var placing = false

    /** Whether this session has said anything yet about what it found itself talking to. */
    private var greeted = false

    /**
     * Returns the result to use, or null to let Litematica's placement go through untouched.
     *
     * Everything about the decision is deliberately cheap until the last moment, because this runs
     * inside every use of every block: two field reads, and then a bounded look at the call stack
     * that ends the moment it finds the first frame it cares about.
     */
    @JvmStatic
    fun intercept(
        gameMode: MultiPlayerGameMode,
        player: LocalPlayer,
        hand: InteractionHand,
        hit: BlockHitResult,
    ): InteractionResult? {
        if (placing) return null
        if (!Config.enabled) return null
        if (Litematica.unavailable) return null

        val initiator = Litematica.placingFrom() ?: return null
        if (!Litematica.isEasyPlace(initiator)) return null

        greet(initiator)
        Engagement.adviseIfProtocolNegotiated()
        if (!Engagement.shouldAlign()) {
            Log.detail("standing aside: Litematica's {} protocol is carrying orientation already", Engagement.protocol())
            return null
        }

        val schematic = Litematica.schematicWorld()
        if (schematic == null) {
            Log.detail("no schematic world loaded; nothing to align to")
            return null
        }

        // The search asks blocks what they would place, and a modded block is free to answer that
        // question badly. An exception escaping here would surface inside Litematica's placement
        // loop and take Easy Place down with it, so one is caught, reported once, and treated as
        // "nothing to do" — leaving Litematica's own placement to go ahead exactly as it would
        // have without this mod installed.
        val outcome = try {
            Aligner.plan(player, hand, hit, schematic)
        } catch (e: Exception) {
            reportSearchFailure(e)
            return null
        } catch (e: LinkageError) {
            reportSearchFailure(e)
            return null
        }

        return when (outcome) {
            is Aligner.Outcome.LeaveAlone -> {
                Log.detail("left alone: {}", outcome.why)
                null
            }

            is Aligner.Outcome.Unsolvable -> {
                Log.warn(
                    "cannot place {} at {} the way the schematic asks for. The nearest any legal " +
                        "click and look could get was {}. The placement was {}.",
                    outcome.wanted, outcome.pos, outcome.closest ?: "nothing",
                    if (Config.skipImpossible) "skipped" else "left to Litematica",
                )
                Messages.unaligned(outcome.wanted, Config.skipImpossible)
                // Declining is the point rather than a failure. Litematica will come back to this
                // position once its own cooldown lapses, so a block that becomes placeable later —
                // when the neighbour it needed is there — is picked up without anything to retry
                // by hand. Anyone who would rather have the block wrong than missing can say so.
                if (Config.skipImpossible) InteractionResult.FAIL else null
            }

            is Aligner.Outcome.Aligned -> place(gameMode, player, hand, outcome)
        }
    }

    /** Says once per session what this session is actually dealing with. */
    private fun greet(initiator: String) {
        if (greeted) return
        greeted = true
        Log.info(
            "Easy Place is placing blocks via {}. Litematica's effective protocol is {}; this mod " +
                "{} be stepping in. Set verboseLogging to see each placement.",
            initiator, Engagement.protocol() ?: "unknown",
            if (Engagement.shouldAlign()) "will" else "will not",
        )
    }

    private fun place(
        gameMode: MultiPlayerGameMode,
        player: LocalPlayer,
        hand: InteractionHand,
        aligned: Aligner.Outcome.Aligned,
    ): InteractionResult? {
        val connection = Minecraft.getInstance().connection
        if (connection == null) {
            // Nothing to claim a rotation to. Better to place it Litematica's way than not at all.
            Log.warn("no connection to claim a rotation on; {} left to Litematica", aligned.wanted)
            return null
        }

        val placement = aligned.placement
        val realYaw = player.yRot
        val realPitch = player.xRot
        val realHead = player.yHeadRot

        Log.detail(
            "aligning {} at {}: would have been {}, claiming yaw {} pitch {} and a click on {} of {} -> expecting {}",
            aligned.wanted.block.descriptionId, aligned.pos, aligned.baseline,
            placement.rotation.yaw, placement.rotation.pitch,
            placement.hit.direction, placement.hit.blockPos, aligned.expected,
        )

        connection.send(rotationPacket(placement.rotation.yaw, placement.rotation.pitch, player))
        player.yRot = placement.rotation.yaw
        player.yHeadRot = placement.rotation.yaw
        player.xRot = placement.rotation.pitch

        placing = true
        try {
            val result = gameMode.useItemOn(player, hand, placement.hit)
            // Only worth auditing something that was actually sent.
            PlacementAudit.expect(aligned)
            return result
        } finally {
            placing = false
            player.yRot = realYaw
            player.xRot = realPitch
            player.yHeadRot = realHead
            // Told the truth again immediately, rather than left to the next movement packet. The
            // client only reports rotation when it changes, so a player who is standing still
            // sends nothing of their own, and the claim would otherwise stand for as long as they
            // stayed put.
            connection.send(rotationPacket(realYaw, realPitch, player))
        }
    }

    private fun rotationPacket(yaw: Float, pitch: Float, player: LocalPlayer) =
        ServerboundMovePlayerPacket.Rot(yaw, pitch, player.onGround(), false)

    /** Forgets what was said about this server, for when the next one is a different one. */
    fun forget() {
        greeted = false
    }

    private var reportedSearchFailure = false

    private fun reportSearchFailure(cause: Throwable) {
        if (reportedSearchFailure) return
        reportedSearchFailure = true
        Log.error(
            "a block threw while being asked what it would place. That placement has been left to " +
                "Litematica. Further occurrences are not logged.",
            cause,
        )
    }
}
