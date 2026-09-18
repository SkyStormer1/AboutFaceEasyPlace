package com.skystormer.aboutfaceeasyplace

import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import net.minecraft.resources.Identifier
import org.lwjgl.glfw.GLFW

/**
 * About Face Easy Place — a Litematica plugin that makes Easy Place put blocks down the right way
 * round on vanilla servers.
 *
 * There is nothing to turn on. Litematica decides when Easy Place runs and what belongs where;
 * this mod only steps in for the moment a block is placed, and only where the orientation would
 * otherwise go unsaid.
 *
 * The settings live behind Mod Menu, and the main switch is also on a keybind so it can be taken
 * out of the way mid-build without opening anything. The key starts unbound, so that it cannot
 * collide with any of Litematica's own.
 */
object AboutFaceEasyPlaceClient : ClientModInitializer {

    const val MOD_ID = "aboutfaceeasyplace"

    lateinit var toggleKey: KeyMapping
        private set

    override fun onInitializeClient() {
        Config.load()

        toggleKey = KeyMappingHelper.registerKeyMapping(
            KeyMapping(
                "key.$MOD_ID.toggle",
                GLFW.GLFW_KEY_UNKNOWN,
                KeyMapping.Category.register(Identifier.fromNamespaceAndPath(MOD_ID, "main")),
            )
        )

        ClientTickEvents.END_CLIENT_TICK.register { client -> onTick(client) }
    }

    private fun onTick(client: Minecraft) {
        if (client.player == null) {
            // Leaving a server takes what was worked out about that server with it, so the next
            // one is read fresh rather than judged by the last one.
            Engagement.forget()
            return
        }

        // Consume every press rather than only the first: a key mapping counts presses, and one
        // left on the pile would fire the next time the player joined a world.
        var toggles = 0
        while (toggleKey.consumeClick()) toggles++
        if (toggles % 2 == 1) {
            Config.enabled = !Config.enabled
            Config.save()
            Messages.toggled(Config.enabled)
        }
    }
}
