package net.minecraft.world.phys;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
public class BlockHitResult {
    private final Vec3 location; private final Direction direction; private final BlockPos pos;
    public BlockHitResult(Vec3 location, Direction direction, BlockPos pos, boolean inside) {
        this.location = location; this.direction = direction; this.pos = pos;
    }
    public BlockPos getBlockPos() { return pos; }
    public Direction getDirection() { return direction; }
    public Vec3 getLocation() { return location; }
}
