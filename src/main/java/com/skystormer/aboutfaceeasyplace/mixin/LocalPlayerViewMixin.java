package com.skystormer.aboutfaceeasyplace.mixin;

import com.skystormer.aboutfaceeasyplace.Placing;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Lets the client's player answer "which way is the head facing" the way the server's would.
 *
 * On the server, a player's view yaw is their head's, which only catches up with the body once a
 * tick. The client's own player skips that and answers with the body — it never needs anything
 * else, because on the client the two are always level. So asking the client what a piston would
 * do tells you what it would do with the head wherever the body is, which is not what the server
 * will do. While this mod is asking that question, or making a placement it has planned, the head
 * is answered with the value the server is believed to hold instead. At every other moment this
 * does nothing.
 */
@Mixin(LocalPlayer.class)
public abstract class LocalPlayerViewMixin {

    @Inject(method = "getViewYRot(F)F", at = @At("HEAD"), cancellable = true)
    private void aboutFaceEasyPlace$serverHead(float partialTick, CallbackInfoReturnable<Float> cir) {
        Float head = Placing.headOverride;
        if (head != null) {
            cir.setReturnValue(head);
        }
    }
}
