package com.minecraftcitiesnetwork.directions.walk;

/** Read-only walkability of the world, column by column. */
@FunctionalInterface
public interface Terrain {
    /**
     * The walkable surfaces of column (x, z), sorted by height: an empty array when nothing there
     * is walkable, or {@code null} when the column is not known (not captured). Callers must not
     * modify the array.
     */
    int[] surfaces(int x, int z);
}
