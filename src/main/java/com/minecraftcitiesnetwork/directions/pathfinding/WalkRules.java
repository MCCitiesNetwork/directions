package com.minecraftcitiesnetwork.directions.pathfinding;

/**
 * The physical rules of a walking player, expressed over a {@link BlockGrid}.
 *
 * <p>Shared by the move validator inside the search and by {@link StandingPositions}, so a cell the
 * search considers standable is exactly a cell the snapper would choose.
 */
public final class WalkRules {
    private WalkRules() {
    }

    /** Whether the player's whole body fits with its feet in the given cell. */
    public static boolean enterable(BlockGrid grid, WalkerProfile p, int x, int y, int z) {
        for (int i = 0; i < p.height(); i++) {
            if (!BlockFlags.canOccupy(grid.flags(x, y + i, z), p.canSwim())) {
                return false;
            }
        }
        return true;
    }

    /** Whether a body with its feet in the cell does not fall: solid ground below, or swimming or climbing. */
    public static boolean supported(BlockGrid grid, WalkerProfile p, int x, int y, int z) {
        byte self = grid.flags(x, y, z);
        if (BlockFlags.isClimbable(self)) {
            return true;
        }
        if (BlockFlags.isLiquid(self)) {
            return p.canSwim();
        }
        return BlockFlags.isSolid(grid.flags(x, y - 1, z));
    }

    /** Whether the player can stand still with its feet in the cell. */
    public static boolean standing(BlockGrid grid, WalkerProfile p, int x, int y, int z) {
        return enterable(grid, p, x, y, z) && supported(grid, p, x, y, z);
    }

    /** Whether the cell is water or a climbable, which both permit vertical movement. */
    public static boolean climbOrSwim(byte f) {
        return BlockFlags.isLiquid(f) || BlockFlags.isClimbable(f);
    }

    /**
     * Number of cells a body with its feet in the cell falls before it is supported, or -1 when it
     * would fall further than the profile allows or hit something it cannot enter on the way down.
     */
    public static int landingDepth(BlockGrid grid, WalkerProfile p, int x, int y, int z) {
        for (int k = 0; k <= p.maxDrop(); k++) {
            int cy = y - k;
            if (k > 0 && !BlockFlags.canOccupy(grid.flags(x, cy, z), p.canSwim())) {
                return -1;
            }
            if (supported(grid, p, x, cy, z)) {
                return k;
            }
        }
        return -1;
    }
}
