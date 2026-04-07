package com.minecraftcitiesnetwork.directions.model;

import java.util.Set;

public record Stop(String regionId, String worldName, double x, double z, Set<String> lines) {
    public double distance2D(Stop other) {
        double dx = x - other.x;
        double dz = z - other.z;
        return Math.sqrt(dx * dx + dz * dz);
    }
}
