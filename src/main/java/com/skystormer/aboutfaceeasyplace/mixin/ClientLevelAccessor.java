package com.skystormer.aboutfaceeasyplace.mixin;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.prediction.BlockStatePredictionHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** The numbering of actions the server acknowledges, which is package-private. */
@Mixin(ClientLevel.class)
public interface ClientLevelAccessor {

    @Invoker("getBlockStatePredictionHandler")
    BlockStatePredictionHandler aboutFaceEasyPlace$predictions();
}
