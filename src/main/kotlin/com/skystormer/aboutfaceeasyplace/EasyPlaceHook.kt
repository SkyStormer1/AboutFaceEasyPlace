package com.skystormer.aboutfaceeasyplace

import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientPacketListener
import net.minecraft.client.multiplayer.MultiPlayerGameMode
import net.minecraft.client.player.LocalPlayer
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket
import net.minecraft.network.protocol.game.ServerboundPlayerInputPacket
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.context.BlockPlaceContext
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.Vec3

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
 *
 * Blocks that read the player's head rather than their body cannot be reached that way, because
 * the server only turns its copy of the head once a tick. For those the claim is held across a
 * tick instead — see [RotationHold] — and the placement is made when it ends.
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

        // While a claim is being held for a block that needs the head turned, the server believes
        // the player is looking the claimed way, so any other placement made now would be made
        // under it. Nothing is lost by waiting: Litematica comes back to the position by itself.
        if (RotationHold.busy) return InteractionResult.FAIL

        val initiator = Litematica.placingFrom()
        if (initiator == null) return interceptVanillaClick(gameMode, player, hand, hit)
        if (!Litematica.isEasyPlace(initiator)) return null

        greet(initiator)
        Engagement.adviseIfProtocolNegotiated()
        if (!Engagement.shouldAlign()) {
            val wanted = wantedAt(player, hand, hit) ?: return null
            if (Engagement.alignsHangingSigns() && Engagement.isHangingSign(wanted)) {
                return align(gameMode, player, hand, hit)
            }
            // Litematica's own protocol carries the orientation, but not what a block only gets by
            // being used after it is down. Those are made once the server's answer has arrived.
            if (Adjustments.hasAdjustable(wanted)) FollowUp.expect(targetOf(player, hand, hit), wanted, hand)
            return null
        }

        return align(gameMode, player, hand, hit)
    }

    /**
     * A right click that Easy Place handed back to vanilla.
     *
     * Litematica aims Easy Place along the player's line of sight at the schematic. When the
     * schematic block there is a small one — dust, a rail, a comparator — the line of sight can
     * pass over it and land on the real block underneath, and Litematica then lets the held right
     * click through as an ordinary one. Three things go wrong on a vanilla server as a result, and
     * this catches exactly those, only while Easy Place is on and the player is not sneaking:
     *
     *  - **The block is placed by vanilla instead.** It lands wherever the player happens to be
     *    looking — or, with Tweakeroo's accurate placement on, facing whichever way Tweakeroo
     *    remembered from the first block of the click. So a click that would put the held block
     *    where the schematic wants it is planned here exactly as an Easy Place one would be.
     *  - **A container opens.** A click on a dropper or a chest uses it rather than placing, so
     *    where the schematic wants the held block right against the face that was clicked, the
     *    click is made on that empty space instead. A click on a chest with nothing to put there
     *    still opens the chest.
     *  - **A finished block is undone.** Holding the key on a door this mod has just opened, or a
     *    repeater it has just set, would close it or cycle it again. A click on a block of the
     *    schematic's that this mod adjusts is swallowed.
     */
    private fun interceptVanillaClick(
        gameMode: MultiPlayerGameMode,
        player: LocalPlayer,
        hand: InteractionHand,
        hit: BlockHitResult,
    ): InteractionResult? {
        if (player.isShiftKeyDown) return null
        if (!Litematica.easyPlaceActive()) return null
        if (!Engagement.shouldAlign()) return null
        val schematic = Litematica.schematicWorld() ?: return null
        val level = player.level()

        Tweakeroo.warnIfFighting()

        val clicked = level.getBlockState(hit.blockPos)
        val interactive = Interaction.isInteractive(level, hit.blockPos)
        // Any block the schematic has here, not only one that already matches it: a click that
        // lands a moment after the placement, before the client has caught up with what the
        // server did, would otherwise open a gate the server has only just placed.
        if (interactive && Adjustments.hasAdjustable(clicked) && clicked.block === schematic.getBlockState(hit.blockPos).block) {
            return InteractionResult.FAIL
        }

        val stack = player.getItemInHand(hand)
        val item = stack.item as? BlockItem ?: return null
        // Where the block would go. An interactive block would be used rather than placed
        // against, so for one of those it is the space in front of the face that was clicked.
        val targetPos = if (interactive) hit.blockPos.relative(hit.direction) else BlockPlaceContext(player, hand, stack, hit).clickedPos
        if (!level.getBlockState(targetPos).canBeReplaced()) return null
        if (schematic.getBlockState(targetPos).block.asItem() !== item) return null

        // The same point on the same face, named as a click on the empty space rather than on
        // the container. The search starts from it exactly as it would from one of Litematica's.
        val start = if (interactive) BlockHitResult(hit.location, hit.direction, targetPos, false) else hit
        return align(gameMode, player, hand, start) ?: place(gameMode, player, hand, start)
    }

    private fun align(
        gameMode: MultiPlayerGameMode,
        player: LocalPlayer,
        hand: InteractionHand,
        hit: BlockHitResult,
    ): InteractionResult? {
        val schematic = Litematica.schematicWorld() ?: return null

        // The search asks blocks what they would place, and a modded block is free to answer that
        // question badly. An exception escaping here would surface inside Litematica's placement
        // loop and take Easy Place down with it, so one is caught, reported once, and treated as
        // "nothing to do" — leaving Litematica's own placement to go ahead exactly as it would
        // have without this mod installed.
        val outcome = try {
            Aligner.plan(
                player, hand, player.getItemInHand(hand), hit, schematic,
                RotationHold.believed(player), RotationHold.serverHeads(player),
            )
        } catch (e: Exception) {
            reportSearchFailure(e)
            return null
        } catch (e: LinkageError) {
            reportSearchFailure(e)
            return null
        }

        return when (outcome) {
            is Aligner.Outcome.LeaveAlone -> null

            is Aligner.Outcome.Unsolvable -> {
                if (isUpperHalf(outcome.wanted)) {
                    // Litematica aiming at the top half of a door or a tall plant. Placing there
                    // would put the whole thing a block too high; the lower half is what places it.
                    return InteractionResult.FAIL
                }
                Log.warn(
                    "cannot place {} at {} the way the schematic asks for. The nearest any legal " +
                        "click and look could get was {}. The placement was {}.{}",
                    outcome.wanted, outcome.pos, outcome.closest ?: "nothing",
                    if (Config.skipImpossible) "skipped" else "left to Litematica",
                    if (outcome.hingeOnly) " Only the hinge is wrong, and a door's hinge is decided by the " +
                        "doors beside it: place this one before its neighbour." else "",
                )
                if (outcome.hingeOnly) Messages.hingeBlocked(outcome.wanted) else Messages.unaligned(outcome.wanted, Config.skipImpossible)
                // Declining is the point rather than a failure. Litematica will come back to this
                // position once its own cooldown lapses, so a block that becomes placeable later —
                // when the neighbour it needed is there — is picked up without anything to retry
                // by hand. Anyone who would rather have the block wrong than missing can say so.
                if (Config.skipImpossible) InteractionResult.FAIL else null
            }

            is Aligner.Outcome.Aligned -> {
                val uses = Adjustments.usesNeeded(outcome.expected, outcome.wanted)
                when {
                    // Litematica's own placement is already right, and there is nothing to do to
                    // the block afterwards: the one case where this mod does nothing at all.
                    outcome.untouched && uses == 0 && !Adjustments.hasAdjustable(outcome.wanted) &&
                        !RotationHold.claiming -> null

                    outcome.turnsHead -> hold(player, hand, outcome)
                    else -> placeAligned(gameMode, player, hand, outcome, headTurned = false)
                }
            }
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

    private fun hold(player: LocalPlayer, hand: InteractionHand, aligned: Aligner.Outcome.Aligned): InteractionResult {
        val connection = Minecraft.getInstance().connection ?: return InteractionResult.FAIL
        RotationHold.begin(aligned, hand, player, connection)
        // Declined for now, and made by the hold when it ends. Litematica has already noted the
        // position as attempted, so it will not try the same block again in the meantime.
        return InteractionResult.FAIL
    }

    /** Called by [RotationHold] once a held claim has had time to reach the server's copy of the head. */
    fun placeHeld(aligned: Aligner.Outcome.Aligned, hand: InteractionHand) {
        val minecraft = Minecraft.getInstance()
        val player = minecraft.player
        val gameMode = minecraft.gameMode
        val connection = minecraft.connection
        if (player == null || gameMode == null || connection == null) return

        // A few ticks have passed, so everything the plan rested on is checked again before
        // anything is sent. Any of these failing means Litematica gets the position back.
        val stillWanted = Litematica.schematicWorld()?.getBlockState(aligned.pos) == aligned.wanted
        val stillEmpty = player.level().getBlockState(aligned.pos).canBeReplaced()
        val stillHeld = player.getItemInHand(hand).item === aligned.wanted.block.asItem()
        val stillSound = Aligner.usable(player, hand, player.getItemInHand(hand), aligned.placement.hit, aligned.pos)
        if (!(stillWanted && stillEmpty && stillHeld && stillSound)) {
            RotationHold.restore(player, connection)
            return
        }

        Litematica.whileHandling {
            placeAligned(gameMode, player, hand, aligned, headTurned = true, claimSent = true)
        }
    }

    /**
     * Makes [aligned]'s placement, then any uses that finish the block off.
     *
     * @param headTurned the server's copy of the head has been turned to the claim, so the client's
     *   copy is turned too for its prediction. Otherwise it is left where the server has it.
     * @param claimSent the claim is already in force on the server, sent by a hold.
     */
    private fun placeAligned(
        gameMode: MultiPlayerGameMode,
        player: LocalPlayer,
        hand: InteractionHand,
        aligned: Aligner.Outcome.Aligned,
        headTurned: Boolean,
        claimSent: Boolean = false,
    ): InteractionResult? {
        val connection = Minecraft.getInstance().connection
        if (connection == null) {
            // Nothing to claim a rotation to. Better to place it Litematica's way than not at all.
            Log.warn("no connection to claim a rotation on; {} left to Litematica", aligned.wanted)
            return null
        }

        val placement = aligned.placement
        val claiming = aligned.claimsRotation
        val realYaw = player.yRot
        val realPitch = player.xRot

        Log.detail(
            "aligning {} at {}: would have been {}, claiming yaw {} pitch {}{}{} and a click on {} of {} -> expecting {}",
            aligned.wanted.block.descriptionId, aligned.pos, aligned.baseline,
            placement.rotation.yaw, placement.rotation.pitch, if (headTurned) " (head included)" else "",
            if (placement.sneak != player.lastSentInput.shift()) (if (placement.sneak) ", crouching" else ", standing") else "",
            placement.hit.direction, placement.hit.blockPos, aligned.expected,
        )

        if (claiming && !claimSent) RotationHold.send(placement.rotation, player, connection)
        // The client's copy of the player is put where the server's is, so that the block it
        // predicts is the block the server places: the body at the claim, and the head wherever
        // the server has it — turned with the claim if it was held, and otherwise where it already
        // was, which may itself be a claim made ahead of time.
        player.yRot = placement.rotation.yaw
        player.xRot = placement.rotation.pitch
        Placing.headOverride = if (headTurned) placement.rotation.yaw else RotationHold.serverHeads(player).first()

        placing = true
        try {
            val result = sneaking(player, connection, placement.sneak) {
                gameMode.useItemOn(player, hand, placement.hit)
            }
            if (result.consumesAction()) {
                // Made before the real rotation is sent back, because a fence gate opened by a
                // player facing the wrong way swings round to face them.
                adjust(gameMode, player, hand, aligned, connection)
                PlacementAudit.expect(aligned.pos, aligned.wanted, player.level().getBlockState(aligned.pos))
            }
            return result
        } finally {
            placing = false
            Placing.headOverride = null
            player.yRot = realYaw
            player.xRot = realPitch
            // Told the truth again immediately, rather than left to the next movement packet. The
            // client only reports rotation when it changes, so a player who is standing still
            // sends nothing of their own, and the claim would otherwise stand for as long as they
            // stayed put.
            if (claiming) RotationHold.restore(player, connection)
        }
    }

    /**
     * A placement of the vanilla click this mod redirected, where the search had nothing to say:
     * the block has no orientation, so the redirected click is used as it is.
     */
    private fun place(
        gameMode: MultiPlayerGameMode,
        player: LocalPlayer,
        hand: InteractionHand,
        hit: BlockHitResult,
    ): InteractionResult {
        placing = true
        try {
            return gameMode.useItemOn(player, hand, hit)
        } finally {
            placing = false
        }
    }

    /**
     * Uses the block just placed as many times as it takes to match the schematic.
     *
     * The uses are made with the main hand, whatever placed the block, because vanilla only lets
     * a block be used by a click with the main hand; an off-hand click on a repeater would place
     * another repeater. For the same reason they are never made while the main hand holds
     * something that is not the block — a click that the item in it might answer itself. And
     * sneaking is lifted for them, because a sneaking player's click places rather than uses.
     */
    private fun adjust(
        gameMode: MultiPlayerGameMode,
        player: LocalPlayer,
        hand: InteractionHand,
        aligned: Aligner.Outcome.Aligned,
        connection: ClientPacketListener,
    ) {
        useToMatch(gameMode, player, hand, aligned.pos, aligned.wanted, connection)
    }

    /** Uses the block at [pos] as many times as it takes to match [wanted], as [adjust] describes. */
    fun useToMatch(
        gameMode: MultiPlayerGameMode,
        player: LocalPlayer,
        hand: InteractionHand,
        pos: BlockPos,
        wanted: BlockState,
        connection: ClientPacketListener,
    ) {
        val uses = Adjustments.usesNeeded(player.level().getBlockState(pos), wanted)
        if (uses == 0) return

        if (hand != InteractionHand.MAIN_HAND && !player.mainHandItem.isEmpty) {
            Log.warn(
                "{} at {} needs {} use(s) to match the schematic, but it was placed from the off hand " +
                    "with something else in the main hand, so they were not made",
                wanted.block.descriptionId, pos, uses,
            )
            return
        }

        val wasPlacing = placing
        placing = true
        try {
            sneaking(player, connection, false) {
                val use = BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false)
                repeat(uses) { gameMode.useItemOn(player, InteractionHand.MAIN_HAND, use) }
            }
        } finally {
            placing = wasPlacing
        }
    }

    /** Where [hit] would put a block. */
    private fun targetOf(player: LocalPlayer, hand: InteractionHand, hit: BlockHitResult): BlockPos =
        BlockPlaceContext(player, hand, player.getItemInHand(hand), hit).clickedPos

    /** What the schematic wants where [hit] would put a block, or null with no schematic loaded. */
    private fun wantedAt(player: LocalPlayer, hand: InteractionHand, hit: BlockHitResult): BlockState? =
        Litematica.schematicWorld()?.getBlockState(targetOf(player, hand, hit))

    /**
     * Runs [action] with the server, and the client's copy of the player, believing the player is
     * crouching or not as [sneak] says — and puts both back afterwards. Costs nothing when that is
     * what the server already believes.
     */
    private fun <T> sneaking(player: LocalPlayer, connection: ClientPacketListener, sneak: Boolean, action: () -> T): T {
        val sent = player.lastSentInput
        if (sent.shift() == sneak) return action()
        val keys = player.input.keyPresses
        val claimed = Aligner.withShift(sent, sneak)
        player.input.keyPresses = Aligner.withShift(keys, sneak)
        connection.send(ServerboundPlayerInputPacket(claimed))
        try {
            return action()
        } finally {
            player.input.keyPresses = keys
            connection.send(ServerboundPlayerInputPacket(sent))
        }
    }

    private fun isUpperHalf(state: BlockState) =
        state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF) &&
            state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER

    fun rotationPacket(yaw: Float, pitch: Float, player: LocalPlayer) =
        ServerboundMovePlayerPacket.Rot(yaw, pitch, player.onGround(), player.horizontalCollision)

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
