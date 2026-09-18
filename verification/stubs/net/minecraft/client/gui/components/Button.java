package net.minecraft.client.gui.components;
import net.minecraft.network.chat.Component;
public class Button {
    public interface OnPress { void onPress(Button button); }
    public static class Builder {
        public Builder bounds(int x, int y, int width, int height) { return this; }
        public Button build() { return new Button(); }
    }
    public static Builder builder(Component message, OnPress onPress) { return new Builder(); }
}
