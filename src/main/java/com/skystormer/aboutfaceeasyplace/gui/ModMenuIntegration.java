package com.skystormer.aboutfaceeasyplace.gui;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

/**
 * Puts the settings behind Mod Menu's cog, for the people who have Mod Menu.
 *
 * Mod Menu is a compile-time dependency only. Fabric loads a `modmenu` entrypoint solely when Mod
 * Menu asks for it, so this class is never touched — and never needs to resolve — on an install
 * without it.
 */
public class ModMenuIntegration implements ModMenuApi {

    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return ConfigScreen::new;
    }
}
