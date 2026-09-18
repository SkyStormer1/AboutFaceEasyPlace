package com.skystormer.aboutfaceeasyplace.mixin;

import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.properties.BlockSetType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Whether a trapdoor opens by hand is on its block set, which a trapdoor keeps to itself. */
@Mixin(TrapDoorBlock.class)
public interface TrapDoorBlockAccessor {

    @Invoker("getType")
    BlockSetType aboutFaceEasyPlace$getType();
}
