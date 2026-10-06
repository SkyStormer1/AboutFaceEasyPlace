package com.skystormer.aboutfaceeasyplace.mixin;

import com.skystormer.aboutfaceeasyplace.ServerTick;
import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hears the server acknowledge a numbered action, which is how {@link ServerTick} learns that the
 * server has ticked the player.
 */
@Mixin(ClientLevel.class)
public abstract class ClientLevelMixin {

    @Inject(method = "handleBlockChangedAck(I)V", at = @At("HEAD"))
    private void aboutFaceEasyPlace$acknowledged(int sequence, CallbackInfo ci) {
        ServerTick.acknowledged((ClientLevel) (Object) this, sequence);
    }
}
