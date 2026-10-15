package com.skystormer.aboutfaceeasyplace.mixin;

import com.skystormer.aboutfaceeasyplace.Substitutions;
import com.skystormer.aboutfaceeasyplace.gui.PlacementProfilesScreen;
import fi.dy.masa.litematica.gui.GuiPlacementConfiguration;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.gui.button.ButtonOnOff;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Block substitutes in Litematica's own settings for a placement: a <b>Substitutes</b> switch and a
 * button to the profiles it uses (see {@link PlacementProfilesScreen}), at the top of the right-hand
 * column, above Litematica's own switches. The same switch as on About Face Easy Place's Block
 * substitutes screen. Optional: a Litematica whose screen has changed just goes without it.
 */
@Pseudo
@Mixin(targets = "fi.dy.masa.litematica.gui.GuiPlacementConfiguration", remap = false)
public abstract class LitematicaPlacementScreenMixin {

    @Inject(method = "initGui", at = @At("TAIL"), require = 0)
    private void aboutFaceEasyPlace$addSwapsButtons(CallbackInfo ci) {
        GuiPlacementConfiguration gui = (GuiPlacementConfiguration) (Object) this;
        SchematicPlacement placement = gui.placement;
        int width = 120;
        int x = gui.getScreenWidth() - width - 10;
        int y = 1;

        ButtonOnOff toggle = new ButtonOnOff(x, y, width - 22, false, "aboutfaceeasyplace.litematica.substitutes",
                Substitutions.INSTANCE.choiceFor(placement).getOn(),
                Component.translatable("gui.aboutfaceeasyplace.switch.tip").getString());
        gui.addButton(toggle, (button, mouseButton) -> {
            boolean on = !Substitutions.INSTANCE.choiceFor(placement).getOn();
            Substitutions.INSTANCE.setOn(placement, on);
            toggle.updateDisplayString(on);
        });

        ButtonGeneric links = new ButtonGeneric(x + width - 20, y, 20, 20, "…",
                Component.translatable("gui.aboutfaceeasyplace.profiles_button.tip").getString());
        gui.addButton(links, (button, mouseButton) ->
                Minecraft.getInstance().gui.setScreen(new PlacementProfilesScreen((Screen) (Object) gui, placement)));
    }
}
