package net.minecraft.core;
public class BlockPos {
    private final int x, y, z;
    public BlockPos(int x, int y, int z) { this.x = x; this.y = y; this.z = z; }
    public int getX() { return x; }
    public int getY() { return y; }
    public int getZ() { return z; }
    @Override public boolean equals(Object o) {
        if (!(o instanceof BlockPos)) return false;
        BlockPos p = (BlockPos) o; return p.x == x && p.y == y && p.z == z;
    }
    @Override public int hashCode() { return (x * 31 + y) * 31 + z; }
    @Override public String toString() { return x + "," + y + "," + z; }
}
