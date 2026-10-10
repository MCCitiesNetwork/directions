package com.minecraftcitiesnetwork.directions.walk;

/** A cuboid of cells, bounds inclusive: a WorldGuard cuboid region. */
public record BoxGoal(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) implements Goal {
    @Override
    public boolean contains(int x, int y, int z) {
        return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
    }

    @Override
    public double estimate(int x, int y, int z) {
        int dx = gap(x, minX, maxX);
        int dy = gap(y, minY, maxY);
        int dz = gap(z, minZ, maxZ);
        return Math.sqrt((double) dx * dx + (double) dy * dy + (double) dz * dz);
    }

    @Override
    public boolean mayOverlap(Window w) {
        return maxX >= w.minX() && minX <= w.maxX() && maxZ >= w.minZ() && minZ <= w.maxZ();
    }

    /** Distance from {@code v} to the range [min, max]; 0 inside. */
    static int gap(int v, int min, int max) {
        if (v < min) {
            return min - v;
        }
        return v > max ? v - max : 0;
    }
}
