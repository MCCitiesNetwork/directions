package com.minecraftcitiesnetwork.directions.walk;

/**
 * How far the walker may drop and how much slower water, climbing and doors are. Every multiplier
 * is at least 1 so straight-line distance stays an admissible heuristic.
 */
public record WalkerProfile(int maxDrop, double waterCost, double climbCost, double doorCost) {
    public static final WalkerProfile DEFAULT = new WalkerProfile(3, 2.5, 2.0, 1.5);

    public WalkerProfile {
        if (maxDrop < 0 || maxDrop > Surfaces.MAX_HEADROOM - 2) {
            throw new IllegalArgumentException("maxDrop must be between 0 and " + (Surfaces.MAX_HEADROOM - 2));
        }
        if (waterCost < 1 || climbCost < 1 || doorCost < 1) {
            throw new IllegalArgumentException("cost multipliers must be at least 1");
        }
    }
}
