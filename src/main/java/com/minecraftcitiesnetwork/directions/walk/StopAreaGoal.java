package com.minecraftcitiesnetwork.directions.walk;

/**
 * A configured stop as navigation sees it: inside the region's footprint, or within {@code margin}
 * blocks of its bounding box horizontally, at any height. This mirrors {@code ArrivalPolicy}, so
 * the trail ends exactly where the stop counts as reached.
 */
public record StopAreaGoal(int minX, int minZ, int maxX, int maxZ, double margin) implements Goal {
    @Override
    public boolean contains(int x, int y, int z) {
        if (x >= minX && x <= maxX && z >= minZ && z <= maxZ) {
            return true;
        }
        double dx = axis(x + 0.5, minX, maxX);
        double dz = axis(z + 0.5, minZ, maxZ);
        return dx * dx + dz * dz <= margin * margin;
    }

    @Override
    public double estimate(int x, int y, int z) {
        double d = Math.hypot(BoxGoal.gap(x, minX, maxX), BoxGoal.gap(z, minZ, maxZ));
        return Math.max(0.0, d - margin - 1.0);
    }

    @Override
    public boolean mayOverlap(Window w) {
        int m = (int) Math.ceil(margin) + 1;
        return maxX + m >= w.minX() && minX - m <= w.maxX() && maxZ + m >= w.minZ() && minZ - m <= w.maxZ();
    }

    private static double axis(double v, double min, double max) {
        if (v < min) {
            return min - v;
        }
        return v > max ? v - max : 0.0;
    }
}
