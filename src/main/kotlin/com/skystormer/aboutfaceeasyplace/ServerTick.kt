package com.skystormer.aboutfaceeasyplace

import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.multiplayer.ClientPacketListener
import net.minecraft.client.player.LocalPlayer
import net.minecraft.core.component.DataComponents
import net.minecraft.network.protocol.game.ServerboundUseItemPacket
import net.minecraft.world.InteractionHand
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.ItemStack

/**
 * Tells when the server has ticked the player since a rotation claim, so a placement that needs the
 * head turned goes out as soon as it can and no sooner.
 *
 * Counting client ticks is a guess either way: too few and the claim and the placement land in
 * the same server tick, so the head never turns and an observer faces the player; too many and
 * every piston is a wait. The server says it outright instead. A numbered use of the item in hand,
 * sent straight after the claim, is acknowledged at the start of the server's tick for the player
 * — the same tick that brings the head level with the claim. Anything sent once the
 * acknowledgement is back reaches the server after that tick, with the head already turned.
 *
 * The use is one that does nothing: it is only made with an empty hand, or with a plain block,
 * which does nothing when used in the air. With anything else in both hands no question is asked,
 * and the caller falls back to counting ticks.
 */
object ServerTick {

    private var lastAcknowledged = -1

    /** The level the numbering belongs to. It starts again with each one. */
    private var level: ClientLevel? = null

    /** Called for every acknowledgement the server sends; see `ClientLevelMixin`. */
    @JvmStatic
    fun acknowledged(level: ClientLevel, sequence: Int) {
        follow(level)
        if (sequence > lastAcknowledged) lastAcknowledged = sequence
    }

    /**
     * The number of the client's latest numbered action, such as a use of a block. Once
     * [hasTicked] says yes to it, the server has handled that use and everything before it, and
     * has already sent back the blocks they changed.
     */
    fun latest(player: LocalPlayer): Int? = (player.level() as? ClientLevel)?.let { Placing.predictions(it).currentSequence() }

    /** Whether the server has ticked the player since the question numbered [sequence] was asked. */
    fun hasTicked(sequence: Int?): Boolean = sequence != null && lastAcknowledged >= sequence

    /**
     * Asks to be told when the server next ticks the player. Sent after the claim, and carrying it.
     * Returns the number to wait for, or null if nothing safe could be asked.
     */
    fun ask(player: LocalPlayer, connection: ClientPacketListener, rotation: Aligner.Rotation): Int? {
        val hand = InteractionHand.entries.firstOrNull { inert(player.getItemInHand(it)) } ?: return null
        val level = player.level() as? ClientLevel ?: return null
        follow(level)
        // Asked from inside a vanilla placement as often as not, which is itself a prediction in
        // progress: ending ours must not end that one too.
        val predictions = Placing.predictions(level)
        val alreadyPredicting = predictions.isPredicting
        predictions.startPredicting()
        val sequence = predictions.currentSequence()
        if (!alreadyPredicting) predictions.close()
        connection.send(ServerboundUseItemPacket(hand, sequence, rotation.yaw, rotation.pitch))
        return sequence
    }

    private fun follow(level: ClientLevel) {
        if (level === this.level) return
        this.level = level
        lastAcknowledged = -1
    }

    private fun inert(stack: ItemStack): Boolean =
        stack.isEmpty || stack.item.javaClass == BlockItem::class.java &&
            !stack.has(DataComponents.EQUIPPABLE) && !stack.has(DataComponents.CONSUMABLE) &&
            !stack.has(DataComponents.BLOCKS_ATTACKS)

    fun forget() {
        level = null
        lastAcknowledged = -1
    }
}
