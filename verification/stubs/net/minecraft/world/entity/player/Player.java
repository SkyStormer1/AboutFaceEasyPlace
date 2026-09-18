package net.minecraft.world.entity.player;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
public class Player {
    public float yHeadRot;
    private float yRot, xRot;
    public ItemStack held;
    public ItemStack getItemInHand(InteractionHand hand) { return held; }
    public float getYRot() { return yRot; }
    public void setYRot(float v) { yRot = v; }
    public float getXRot() { return xRot; }
    public void setXRot(float v) { xRot = v; }
    public float getYHeadRot() { return yHeadRot; }
    public void setYHeadRot(float v) { yHeadRot = v; }
    public boolean onGround() { return true; }
}
