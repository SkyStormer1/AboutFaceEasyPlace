package net.minecraft.core;
public enum Direction {
    DOWN(Axis.Y, AxisDirection.NEGATIVE), UP(Axis.Y, AxisDirection.POSITIVE),
    NORTH(Axis.Z, AxisDirection.NEGATIVE), SOUTH(Axis.Z, AxisDirection.POSITIVE),
    WEST(Axis.X, AxisDirection.NEGATIVE), EAST(Axis.X, AxisDirection.POSITIVE);
    public enum Axis { X, Y, Z; public boolean isHorizontal() { return this != Y; } }
    public enum AxisDirection { POSITIVE, NEGATIVE }
    private final Axis axis; private final AxisDirection axisDirection;
    Direction(Axis axis, AxisDirection d) { this.axis = axis; this.axisDirection = d; }
    public Axis getAxis() { return axis; }
    public AxisDirection getAxisDirection() { return axisDirection; }
    public Direction getOpposite() {
        switch (this) {
            case DOWN: return UP; case UP: return DOWN;
            case NORTH: return SOUTH; case SOUTH: return NORTH;
            case WEST: return EAST; default: return WEST;
        }
    }
    /** Mirrors vanilla: yaw 0 is south, and the quadrant is taken from the rounded yaw. */
    public static Direction fromYRot(double yaw) {
        int i = (int) Math.floor(yaw / 90.0 + 0.5) & 3;
        switch (i) { case 0: return SOUTH; case 1: return WEST; case 2: return NORTH; default: return EAST; }
    }
}
