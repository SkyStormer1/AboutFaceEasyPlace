package com.skystormer.aboutfaceeasyplace.mixin;

import com.skystormer.aboutfaceeasyplace.RotationHold;
import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.network.protocol.Packet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Keeps a held rotation claim in force while the client goes on reporting its movement.
 *
 * A claim that has to survive a server tick is sent a tick or two before the placement it is for,
 * and in between the client keeps sending the player's position — with their real rotation
 * whenever they move the mouse. Left alone, that would undo the claim before the server had read
 * it. While a hold is active, those reports carry the claimed rotation instead; the position in
 * them is untouched, and nothing changes once the hold ends.
 */
@Mixin(ClientCommonPacketListenerImpl.class)
public abstract class ClientCommonPacketListenerImplMixin {

    @ModifyVariable(method = "send(Lnet/minecraft/network/protocol/Packet;)V", at = @At("HEAD"), argsOnly = true)
    private Packet<?> aboutFaceEasyPlace$holdClaimedRotation(Packet<?> packet) {
        return RotationHold.rewrite(packet);
    }
}
