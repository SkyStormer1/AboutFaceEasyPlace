package net.minecraft.world.item.context;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
public class BlockPlaceContext extends UseOnContext {
    private final Player player; private final BlockHitResult hit;
    public BlockPlaceContext(Player player, InteractionHand hand, ItemStack stack, BlockHitResult hit) {
        this.player = player; this.hit = hit;
    }
    /** The test world is empty, so the clicked block is always replaceable and keeps its position. */
    public BlockPos getClickedPos() { return hit.getBlockPos(); }
    public Direction getClickedFace() { return hit.getDirection(); }
    public Vec3 getClickLocation() { return hit.getLocation(); }
    public Direction getHorizontalDirection() { return Direction.fromYRot(player.getYRot()); }
    public Direction getNearestLookingDirection() {
        if (player.getXRot() < -45f) return Direction.UP;
        if (player.getXRot() > 45f) return Direction.DOWN;
        return Direction.fromYRot(player.getYRot());
    }
}
