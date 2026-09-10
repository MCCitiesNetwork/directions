package com.minecraftcitiesnetwork.directions.pathfinding;

/**
 * Physical capabilities and preferences of the walking player.
 *
 * @param height     vertical cells the body occupies (2 for a player)
 * @param stepUp     whether a one-block rise can be climbed without a ladder
 * @param maxDrop    deepest fall allowed, in blocks
 * @param canSwim    whether water cells may be entered
 * @param doorCost   multiplier on a move into a door, gate or trapdoor (must be at least 1)
 * @param waterCost  multiplier on a move into water (must be at least 1)
 * @param climbCost  multiplier on a vertical move inside a ladder or vine (must be at least 1)
 * @param snapRadius how far (in blocks) a start or target may be moved to the nearest standing cell
 */
public record WalkerProfile(
        int height,
        boolean stepUp,
        int maxDrop,
        boolean canSwim,
        double doorCost,
        double waterCost,
        double climbCost,
        int snapRadius
) {
    public static final WalkerProfile DEFAULT = new WalkerProfile(2, true, 3, true, 1.5, 2.5, 2.0, 6);

    public WalkerProfile {
        if (height < 1) {
            throw new IllegalArgumentException("height must be at least 1");
        }
        if (maxDrop < 0) {
            throw new IllegalArgumentException("maxDrop must not be negative");
        }
        if (doorCost < 1.0 || waterCost < 1.0 || climbCost < 1.0) {
            throw new IllegalArgumentException("cost multipliers must be at least 1.0 to keep the heuristic admissible");
        }
        if (snapRadius < 0) {
            throw new IllegalArgumentException("snapRadius must not be negative");
        }
    }
}
