package net.minecraft.client;
import net.minecraft.resources.Identifier;
public class KeyMapping {
    public static class Category {
        public static Category register(Identifier id) { return null; }
    }
    public KeyMapping(String name, int key, Category category) {}
    public boolean consumeClick() { return false; }
    public boolean isDown() { return false; }
}
