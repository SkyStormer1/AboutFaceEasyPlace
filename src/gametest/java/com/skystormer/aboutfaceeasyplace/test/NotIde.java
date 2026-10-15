package com.skystormer.aboutfaceeasyplace.test;

import net.fabricmc.api.ClientModInitializer;
import net.minecraft.SharedConstants;

/**
 * MaLiLib turns on Minecraft's IS_RUNNING_IN_IDE in any development environment, and the stricter
 * render checks that brings crash the test client on some graphics drivers. Test runs only.
 */
public class NotIde implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        SharedConstants.IS_RUNNING_IN_IDE = false;
    }
}
