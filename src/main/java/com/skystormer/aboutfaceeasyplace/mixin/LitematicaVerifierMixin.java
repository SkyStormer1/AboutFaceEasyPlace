package com.skystormer.aboutfaceeasyplace.mixin;

import com.skystormer.aboutfaceeasyplace.Substitutions;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The schematic verifier counts an allowed swap as the right block (see {@link Substitutions}): the
 * check is made again as if the schematic had asked for exactly what is there.
 *
 * Optional, like every substitution hook: a Litematica without this method just verifies as it
 * always has.
 */
@Pseudo
@Mixin(targets = "fi.dy.masa.litematica.schematic.verifier.SchematicVerifier", remap = false)
public abstract class LitematicaVerifierMixin {

    @Shadow
    private void checkBlockStates(int x, int y, int z, BlockState stateSchematic, BlockState stateClient) {
    }

    @Inject(method = "checkBlockStates", at = @At("HEAD"), cancellable = true, require = 0)
    private void aboutFaceEasyPlace$countSwapsAsRight(int x, int y, int z, BlockState stateSchematic, BlockState stateClient, CallbackInfo ci) {
        if (Substitutions.matches(x, y, z, stateSchematic, stateClient)) {
            ci.cancel();
            this.checkBlockStates(x, y, z, stateClient, stateClient);
        }
    }
}
