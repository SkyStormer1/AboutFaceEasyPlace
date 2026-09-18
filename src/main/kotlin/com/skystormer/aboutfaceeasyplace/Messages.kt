package com.skystormer.aboutfaceeasyplace

import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import net.minecraft.world.level.block.state.BlockState

/**
 * The few things the mod says, all of them on the action bar and all of them brief.
 *
 * Easy Place places blocks faster than anything can usefully be read, so anything said here is
 * rate limited. A line repeated every tick is not information.
 */
object Messages {

    private const val REPEAT_DELAY_MILLIS = 3000L

    private var lastSaid: String? = null
    private var lastSaidAt = 0L

    /** Always shown: this is the answer to a key the player just pressed. */
    fun toggled(enabled: Boolean) =
        say("toggle", Component.translatable(key(if (enabled) "enabled" else "disabled")), force = true)

    /**
     * Says that a block could not be aligned, and — accurately — what became of it.
     *
     * The two cases read differently on purpose. A skipped block is still to do and will be
     * retried; a placed one is already down and facing the wrong way, and will not be.
     */
    fun unaligned(wanted: BlockState, skipped: Boolean) =
        say(
            "unaligned:${wanted.block.descriptionId}",
            Component.translatable(key(if (skipped) "skipped" else "unaligned"), wanted.block.name),
        )

    /** Tweakeroo's Accurate Block Placement is on; see [Tweakeroo]. Said once a session. */
    fun tweakerooFighting() =
        say("tweakeroo", Component.translatable(key("tweakeroo_fighting")), force = true)

    /** A door whose hinge the doors beside it have already decided. */
    fun hingeBlocked(wanted: BlockState) =
        say("hinge:${wanted.block.descriptionId}", Component.translatable(key("hinge_blocked"), wanted.block.name))

    /**
     * Said at most once a session, by [Engagement], so it needs no help getting past the throttle —
     * and it is information rather than feedback, so it respects the setting that turns messages
     * off. The same thing is in the log either way.
     */
    fun protocolNegotiated(protocol: String) =
        say("protocol", Component.translatable(key("protocol_negotiated"), protocol))

    private fun key(name: String) = "text.${AboutFaceEasyPlaceClient.MOD_ID}.$name"

    /**
     * @param force for feedback the player directly asked for by pressing a key, which is neither
     *   throttled nor subject to the setting that silences everything else.
     */
    private fun say(tag: String, message: Component, force: Boolean = false) {
        if (!force && !Config.showMessages) return
        val now = System.currentTimeMillis()
        if (!force && tag == lastSaid && now - lastSaidAt < REPEAT_DELAY_MILLIS) return
        lastSaid = tag
        lastSaidAt = now
        Minecraft.getInstance().gui.hud.setOverlayMessage(message, false)
    }
}
