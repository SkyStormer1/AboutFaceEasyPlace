package com.skystormer.aboutfaceeasyplace.mixin;

import com.skystormer.aboutfaceeasyplace.Clicks;
import com.skystormer.aboutfaceeasyplace.RotationHold;
import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.network.protocol.Packet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Keeps a standing rotation claim in force while the client goes on reporting its movement.
 *
 * A claim that has to survive a server tick is sent before the placement it is for, and in between
 * the client keeps sending the player's position — with their real rotation whenever they move the
 * mouse — and, when a held click places nothing, a use of the item carrying it too. Left alone,
 * those would undo the claim before the server had read it. While a claim stands they carry the
 * claimed rotation instead; positions are untouched, and nothing changes once the claim ends.
 *
 * Every click on the way out is counted too, for {@link Clicks}.
 */
@Mixin(ClientCommonPacketListenerImpl.class)
public abstract class ClientCommonPacketListenerImplMixin {

    @ModifyVariable(method = "send(Lnet/minecraft/network/protocol/Packet;)V", at = @At("HEAD"), argsOnly = true)
    private Packet<?> aboutFaceEasyPlace$holdClaimedRotation(Packet<?> packet) {
        Packet<?> sent = RotationHold.rewrite(packet);
        Clicks.sending(sent);
        return sent;
    }
}
