package net.minecraft.client.gui.screens;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;
public abstract class Screen {
    public int width, height;
    protected Font font;
    protected Minecraft minecraft;
    private final Component title;
    protected Screen(Component title) { this.title = title; }
    public Component getTitle() { return title; }
    protected void init() {}
    public void onClose() {}
    protected <T> T addRenderableWidget(T widget) { return widget; }
}
