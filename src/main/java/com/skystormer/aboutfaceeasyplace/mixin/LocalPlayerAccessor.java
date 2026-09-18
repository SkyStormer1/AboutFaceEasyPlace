package com.skystormer.aboutfaceeasyplace.mixin;

import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * The yaw the client last told the server about.
 *
 * Closer to what the server believes than the client's current yaw, which may not have been sent
 * yet. It matters for the blocks that read the player's head rather than their body, because the
 * server only brings its copy of the head up to date once a tick.
 */
@Mixin(LocalPlayer.class)
public interface LocalPlayerAccessor {

    @Accessor("yRotLast")
    float aboutFaceEasyPlace$getYRotLast();
}
