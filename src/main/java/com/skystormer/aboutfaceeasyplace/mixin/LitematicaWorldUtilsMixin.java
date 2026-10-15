package com.skystormer.aboutfaceeasyplace.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import com.skystormer.aboutfaceeasyplace.Substitutions;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Litematica's older Easy Place (the one that runs while {@code easyPlacePostRewrite} is off, as it
 * is by default) places an allowed swap where you have it rather than the schematic's block, and
 * leaves a swap already there alone (see {@link Substitutions#toPlace}). Everything after that,
 * the item it picks and the way it clicks, follows from the block it was handed.
 *
 * Optional: without the method, Easy Place places exactly what the schematic says.
 */
@Pseudo
@Mixin(targets = "fi.dy.masa.litematica.util.WorldUtils", remap = false)
public abstract class LitematicaWorldUtilsMixin {

    @ModifyVariable(method = "doEasyPlaceAction", at = @At(value = "STORE", ordinal = 0), ordinal = 0, require = 0)
    private static BlockState aboutFaceEasyPlace$placeSwaps(BlockState stateSchematic, @Local(ordinal = 0) BlockPos pos) {
        return Substitutions.toPlace(pos, stateSchematic);
    }
}
