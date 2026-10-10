package com.minecraftcitiesnetwork.directions.walk;

/** A square of columns a search may use, bounds inclusive. */
public record Window(int minX, int minZ, int maxX, int maxZ) {
    public static Window around(int x, int z, int radius) {
        return new Window(x - radius, z - radius, x + radius, z + radius);
    }

    public boolean contains(int x, int z) {
        return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
    }

    public boolean onBorder(int x, int z) {
        return x == minX || x == maxX || z == minZ || z == maxZ;
    }
}
