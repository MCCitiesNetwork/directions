package com.minecraftcitiesnetwork.directions.pathfinding;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Finds the nearest cell a player can stand in. Used to turn a player's exact location, a region
 * centre, or a coordinate destination into a valid search endpoint.
 */
public final class StandingPositions {
    private static final int MAX_RADIUS = 16;
    private static final int[][][] OFFSETS_BY_RADIUS = buildOffsets();

    private StandingPositions() {
    }

    /**
     * Nearest standable cell to {@code (x, y, z)} within the profile's snap radius. Dropping down and
     * a single step up are cheap; climbing two or more blocks is expensive, so a point on the wall
     * between two platforms snaps onto a platform rather than onto the roof above.
     */
    public static Optional<Pos> snap(BlockGrid grid, WalkerProfile profile, Pos at) {
        int radius = Math.min(profile.snapRadius(), MAX_RADIUS);
        for (int r = 0; r <= radius; r++) {
            for (int[] o : OFFSETS_BY_RADIUS[r]) {
                int x = at.x() + o[0];
                int y = at.y() + o[1];
                int z = at.z() + o[2];
                if (y < grid.minY() || y > grid.maxY() + 1) {
                    continue;
                }
                if (WalkRules.standing(grid, profile, x, y, z)) {
                    return Optional.of(new Pos(x, y, z));
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Highest standable cell in a column, scanning downward from {@code fromY}. Used for stops whose
     * region spans the full world height and therefore has no meaningful Y.
     */
    public static Optional<Pos> surface(BlockGrid grid, WalkerProfile profile, int x, int z, int fromY) {
        for (int y = Math.min(fromY, grid.maxY() + 1); y > grid.minY(); y--) {
            if (WalkRules.standing(grid, profile, x, y, z)) {
                return Optional.of(new Pos(x, y, z));
            }
        }
        return Optional.empty();
    }

    /**
     * Offsets grouped by Chebyshev radius, each group ordered by a weighted distance: downward moves
     * cost a quarter, one step up costs the same as one step sideways, and higher climbs cost four
     * times their square. Ties prefer the smaller horizontal displacement.
     */
    private static double verticalCost(int dy) {
        if (dy < 0) {
            return 0.25 * dy * dy;
        }
        if (dy == 1) {
            return 1.0;
        }
        return 4.0 * dy * dy;
    }

    private static int[][][] buildOffsets() {
        int[][][] byRadius = new int[MAX_RADIUS + 1][][];
        for (int r = 0; r <= MAX_RADIUS; r++) {
            List<int[]> ring = new ArrayList<>();
            for (int dx = -r; dx <= r; dx++) {
                for (int dy = -r; dy <= r; dy++) {
                    for (int dz = -r; dz <= r; dz++) {
                        if (Math.max(Math.abs(dx), Math.max(Math.abs(dy), Math.abs(dz))) == r) {
                            ring.add(new int[]{dx, dy, dz});
                        }
                    }
                }
            }
            ring.sort(Comparator.<int[]>comparingDouble(o -> o[0] * o[0] + o[2] * o[2] + verticalCost(o[1]))
                    .thenComparingInt(o -> o[0] * o[0] + o[2] * o[2]));
            byRadius[r] = ring.toArray(new int[0][]);
        }
        return byRadius;
    }
}
