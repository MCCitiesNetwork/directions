package com.minecraftcitiesnetwork.directions.pathfinding;

/** An integer block position. The player's feet occupy this cell. */
public record Pos(int x, int y, int z) {
    public static Pos floor(double x, double y, double z) {
        return new Pos((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));
    }

    public double centerX() {
        return x + 0.5;
    }

    public double centerZ() {
        return z + 0.5;
    }

    public double distance(Pos other) {
        double dx = x - other.x;
        double dy = y - other.y;
        double dz = z - other.z;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    public double distanceSquaredTo(double px, double py, double pz) {
        double dx = centerX() - px;
        double dy = y - py;
        double dz = centerZ() - pz;
        return dx * dx + dy * dy + dz * dz;
    }

    public double horizontalDistanceTo(double px, double pz) {
        double dx = centerX() - px;
        double dz = centerZ() - pz;
        return Math.sqrt(dx * dx + dz * dz);
    }

    public Pos offset(int dx, int dy, int dz) {
        return new Pos(x + dx, y + dy, z + dz);
    }

    @Override
    public String toString() {
        return x + "," + y + "," + z;
    }
}
