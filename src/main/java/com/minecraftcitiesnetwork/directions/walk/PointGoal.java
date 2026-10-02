package com.minecraftcitiesnetwork.directions.walk;

/**
 * Within {@code radius} of a coordinate, measured from the cell's centre, ignoring height unless
 * {@code includeY}. Mirrors {@code Waypoint.Coordinates.isWithinRadius}.
 */
public record PointGoal(double x, double y, double z, double radius, boolean includeY) implements Goal {
    @Override
    public boolean contains(int cx, int cy, int cz) {
        double d2 = distanceSquared(cx, cy, cz);
        return radius <= 0 ? d2 <= 0.0001 : d2 <= radius * radius;
    }

    @Override
    public double estimate(int cx, int cy, int cz) {
        return Math.max(0.0, Math.sqrt(distanceSquared(cx, cy, cz)) - radius);
    }

    @Override
    public boolean mayOverlap(Window w) {
        return Math.floor(x + radius) >= w.minX() && Math.floor(x - radius) <= w.maxX()
                && Math.floor(z + radius) >= w.minZ() && Math.floor(z - radius) <= w.maxZ();
    }

    private double distanceSquared(int cx, int cy, int cz) {
        double dx = cx + 0.5 - x;
        double dz = cz + 0.5 - z;
        double dy = includeY ? cy - y : 0.0;
        return dx * dx + dy * dy + dz * dz;
    }
}
