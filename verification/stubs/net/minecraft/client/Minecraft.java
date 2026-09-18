package net.minecraft.client;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import org.jetbrains.annotations.Nullable;
public class Minecraft {
    public static Minecraft getInstance() { return null; }
    @Nullable public LocalPlayer player;
    @Nullable public ClientPacketListener getConnection() { return null; }
    public Gui getGui() { return null; }
    public boolean isLocalServer() { return false; }
    public void setScreen(net.minecraft.client.gui.screens.Screen screen) {}
}
