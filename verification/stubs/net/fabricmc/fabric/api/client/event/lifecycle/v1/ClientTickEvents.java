package net.fabricmc.fabric.api.client.event.lifecycle.v1;
import net.minecraft.client.Minecraft;
public final class ClientTickEvents {
    public interface EndTick { void onEndTick(Minecraft client); }
    public static final class Event { public void register(EndTick listener) {} }
    public static final Event END_CLIENT_TICK = new Event();
}
