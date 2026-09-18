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

    fun unsupported(wanted: BlockState) =
        say("unsupported:${wanted.block.descriptionId}", Component.translatable(key("unsupported"), wanted.block.name))

    fun protocolNegotiated(protocol: String) =
        say("protocol", Component.translatable(key("protocol_negotiated"), protocol), force = true)

    private fun key(name: String) = "text.${AboutFaceEasyPlaceClient.MOD_ID}.$name"

    private fun say(tag: String, message: Component, force: Boolean = false) {
        if (!force && !Config.showMessages) return
        val now = System.currentTimeMillis()
        if (!force && tag == lastSaid && now - lastSaidAt < REPEAT_DELAY_MILLIS) return
        lastSaid = tag
        lastSaidAt = now
        Minecraft.getInstance().gui.hud.setOverlayMessage(message, false)
    }
}
