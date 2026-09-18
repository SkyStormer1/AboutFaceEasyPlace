package com.skystormer.aboutfaceeasyplace.mixin;

import com.skystormer.aboutfaceeasyplace.EasyPlaceHook;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The mod's single point of contact with Minecraft.
 *
 * This is where Litematica's Easy Place makes its placement, and hooking it rather than the packet
 * that comes out of it is what lets the claimed rotation be in place for the client's own
 * prediction as well as for the server — by the time a packet exists, the client has already
 * decided what it thinks it just placed.
 *
 * Every other use of every other block reaches this too, which is why the hook's first act is to
 * ask Litematica whether it is the one placing. Nothing here fires for a block the player places
 * by hand.
 */
@Mixin(MultiPlayerGameMode.class)
public abstract class MultiPlayerGameModeMixin {

    @Inject(
            method = "useItemOn(Lnet/minecraft/client/player/LocalPlayer;Lnet/minecraft/world/InteractionHand;Lnet/minecraft/world/phys/BlockHitResult;)Lnet/minecraft/world/InteractionResult;",
            at = @At("HEAD"),
            cancellable = true
    )
    private void aboutFaceEasyPlace$alignSchematicPlacement(
            LocalPlayer player,
            InteractionHand hand,
            BlockHitResult hit,
            CallbackInfoReturnable<InteractionResult> cir
    ) {
        InteractionResult result = EasyPlaceHook.intercept(
                (MultiPlayerGameMode) (Object) this, player, hand, hit);

        // Null means the placement is not this mod's business, and the ordinary one goes ahead.
        if (result != null) {
            cir.setReturnValue(result);
        }
    }
}
