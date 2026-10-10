package com.minecraftcitiesnetwork.directions.walk;

/** Where a walking leg ends. Implementations are immutable and thread-safe. */
public interface Goal {
    /** Whether a player whose feet are in this cell has arrived. */
    boolean contains(int x, int y, int z);

    /** A lower bound on the walking cost from this cell to the nearest goal cell. */
    double estimate(int x, int y, int z);

    /** False only when no goal cell can lie inside the window. */
    boolean mayOverlap(Window window);

    /** A box, bounds inclusive, holding every goal cell. */
    Bounds bounds();
}
