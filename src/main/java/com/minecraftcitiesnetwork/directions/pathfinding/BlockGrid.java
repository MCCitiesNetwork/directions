package com.minecraftcitiesnetwork.directions.pathfinding;

/**
 * What the walker needs to know about the world: one {@link BlockFlags} byte per block.
 *
 * <p>Implementations must be safe to call from a search thread. Coordinates outside the known area
 * return {@link BlockFlags#UNLOADED}, which the walker treats as impassable.
 */
public interface BlockGrid {
    byte flags(int x, int y, int z);

    /** Lowest block Y the grid can describe. Everything below is solid. */
    int minY();

    /** Highest block Y the grid can describe. Everything above is passable air. */
    int maxY();
}
