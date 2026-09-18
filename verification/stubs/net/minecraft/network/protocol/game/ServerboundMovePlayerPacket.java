package net.minecraft.network.protocol.game;
import net.minecraft.network.protocol.Packet;
public class ServerboundMovePlayerPacket implements Packet<Object> {
    public static class Rot extends ServerboundMovePlayerPacket {
        public Rot(float yRot, float xRot, boolean onGround, boolean horizontalCollision) {}
    }
}
