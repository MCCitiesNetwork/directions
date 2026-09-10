package com.minecraftcitiesnetwork.directions.model;

import java.util.Set;

/**
 * A transit stop: a WorldGuard region reduced to its centre.
 *
 * @param y centre height of the region, or {@code NaN} when the region spans the whole world height
 *          and therefore says nothing about where the platform is
 */
public record Stop(String regionId, String worldName, double x, double y, double z, Set<String> lines) {
    public Stop(String regionId, String worldName, double x, double z, Set<String> lines) {
        this(regionId, worldName, x, Double.NaN, z, lines);
    }

    public boolean hasY() {
        return !Double.isNaN(y);
    }

    public double distance2D(Stop other) {
        double dx = x - other.x;
        double dz = z - other.z;
        return Math.sqrt(dx * dx + dz * dz);
    }
}
