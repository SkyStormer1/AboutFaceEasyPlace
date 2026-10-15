package com.skystormer.aboutfaceeasyplace.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.skystormer.aboutfaceeasyplace.Substitutions;
import fi.dy.masa.litematica.render.schematic.ChunkCacheSchematic;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Litematica's schematic renderer draws an allowed swap as finished (see {@link Substitutions}):
 * where the block there is a swap for the schematic's, the schematic is read as holding that very
 * block, so no ghost block and no wrong-block overlay is drawn on it, nor on its neighbours' sides.
 *
 * Runs on Litematica's chunk builder threads, which is why {@link Substitutions} answers from a
 * snapshot. Optional: without the method, the overlay is drawn as it always has been.
 */
@Pseudo
@Mixin(targets = "fi.dy.masa.litematica.render.schematic.ChunkRendererSchematicVbo", remap = false)
public abstract class LitematicaChunkRendererMixin {

    @Shadow
    protected ChunkCacheSchematic clientWorldView;

    @WrapOperation(
            method = "renderBlocksAndOverlay",
            at = @At(value = "INVOKE", target = "Lfi/dy/masa/litematica/render/schematic/ChunkCacheSchematic;getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;"),
            require = 0
    )
    private BlockState aboutFaceEasyPlace$swapsLookFinished(ChunkCacheSchematic view, BlockPos pos, Operation<BlockState> original) {
        BlockState state = original.call(view, pos);
        // The client world's own reads go through untouched; only the schematic's are swapped.
        if (view == this.clientWorldView || state.isAir()) {
            return state;
        }
        return Substitutions.shown(pos, state, this.clientWorldView.getBlockState(pos));
    }
}
