package com.skystormer.aboutfaceeasyplace.mixin;

import com.skystormer.aboutfaceeasyplace.WallMounts;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The one place this mod reaches into Litematica rather than asking it something.
 *
 * For torches, banners, signs and skulls, Litematica moves the click onto the block the schematic
 * block rests against, and refuses the placement outright when that block is not there. Two things
 * go wrong on a vanilla server as a result. A skull that was on a wall, and stays there when the
 * wall is broken, can never be placed at all ("Action prevented by the Easy Place mode"). And
 * where the support does exist, the click is moved to it but the click position is left inside the
 * target, which vanilla rejects as too far from the block clicked, so the block silently never
 * appears.
 *
 * So, for a block that could survive where it is going without any help, the special case is
 * switched off and Litematica makes an ordinary click on the target itself — which this mod's own
 * hook then plans like any other. Blocks that need their support, such as a wall torch, are left
 * exactly as Litematica had them.
 *
 * Targeted by name so that there is still no compile-time dependency on Litematica, and optional
 * so that a Litematica without this method leaves the mod working exactly as it did before.
 */
@Pseudo
@Mixin(targets = "fi.dy.masa.litematica.util.EasyPlaceUtils")
public abstract class LitematicaEasyPlaceUtilsMixin {

    @Inject(
            method = "applyPlacementProtocolAll(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/phys/Vec3;)Lfi/dy/masa/litematica/util/EasyPlaceUtils$PlacementProtocolData;",
            at = @At("RETURN"),
            require = 0
    )
    private static void aboutFaceEasyPlace$releaseFreeStandingBlocks(
            BlockPos pos,
            BlockState stateSchematic,
            Vec3 hitVecIn,
            CallbackInfoReturnable<Object> cir
    ) {
        WallMounts.release(cir.getReturnValue(), pos, stateSchematic);
    }
}
