package net.minecraft.client.gui.components;
import net.minecraft.network.chat.Component;
public class CycleButton<T> {
    public interface OnValueChange<T> { void onValueChange(CycleButton<T> button, T value); }
    public static class Builder<T> {
        public CycleButton<T> create(int x, int y, int width, int height, Component name, OnValueChange<T> onValueChange) {
            return new CycleButton<>();
        }
    }
    public static Builder<Boolean> onOffBuilder(boolean initialValue) { return new Builder<>(); }
}
