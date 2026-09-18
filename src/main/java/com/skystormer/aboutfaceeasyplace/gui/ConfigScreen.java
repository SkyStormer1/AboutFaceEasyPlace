package com.skystormer.aboutfaceeasyplace.gui;

import com.skystormer.aboutfaceeasyplace.Config;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import java.util.function.Consumer;

/**
 * Three switches and a Done button.
 *
 * Built from plain vanilla widgets rather than a config library, so that the mod carries no
 * dependency for the sake of a screen most people will open once. Mod Menu is what usually opens
 * it, but nothing here needs Mod Menu — a keybind toggles the main switch without any of this.
 */
public class ConfigScreen extends Screen {

    private static final int WIDTH = 240;
    private static final int ROW = 20;
    private static final int GAP = 6;

    private final Screen parent;

    public ConfigScreen(Screen parent) {
        super(Component.translatable("config.aboutfaceeasyplace.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int left = this.width / 2 - WIDTH / 2;
        int top = Math.max(40, this.height / 2 - (ROW + GAP) * 3);

        this.addRenderableWidget(new StringWidget(left, top - 28, WIDTH, ROW, this.getTitle(), this.font));

        int y = top;
        this.addRenderableWidget(toggle(left, y, "enabled", Config.getEnabled(), Config::setEnabled));
        y += ROW + GAP;
        this.addRenderableWidget(toggle(left, y, "skip_impossible", Config.getSkipImpossible(), Config::setSkipImpossible));
        y += ROW + GAP;
        this.addRenderableWidget(toggle(left, y, "show_messages", Config.getShowMessages(), Config::setShowMessages));
        y += ROW + GAP;
        this.addRenderableWidget(toggle(left, y, "verbose_logging", Config.getVerboseLogging(), Config::setVerboseLogging));
        y += (ROW + GAP) * 2;

        this.addRenderableWidget(
                Button.builder(CommonComponents.GUI_DONE, button -> this.onClose())
                        .bounds(left, y, WIDTH, ROW)
                        .build());
    }

    private CycleButton<Boolean> toggle(int x, int y, String key, boolean initial, Consumer<Boolean> onChange) {
        return CycleButton.onOffBuilder(initial)
                .create(x, y, WIDTH, ROW,
                        Component.translatable("config.aboutfaceeasyplace." + key),
                        (button, value) -> onChange.accept(value));
    }

    @Override
    public void onClose() {
        // Written on the way out rather than on every click, so a screen left open half-changed
        // still costs one write.
        Config.save();
        if (this.minecraft != null) {
            this.minecraft.setScreenAndShow(this.parent);
        }
    }
}
