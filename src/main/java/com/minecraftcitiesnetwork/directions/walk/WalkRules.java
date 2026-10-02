package com.minecraftcitiesnetwork.directions.walk;

/** Which surfaces a player can reach from a surface in one move, and what each move costs. */
public final class WalkRules {
    public static final double STEP_UP_COST = 0.5;
    private static final double SQRT2 = Math.sqrt(2);
    private static final int[][] DIRECTIONS = {
            {1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}
    };

    @FunctionalInterface
    public interface Sink {
        void accept(int x, int y, int z, int surface, double cost);
    }

    private final WalkerProfile profile;

    public WalkRules(WalkerProfile profile) {
        this.profile = profile;
    }

    public WalkerProfile profile() {
        return profile;
    }

    public void neighbours(Terrain terrain, int x, int z, int surface, Sink sink) {
        int y = Surfaces.y(surface);
        Footing from = Surfaces.footing(surface);
        int[] own = terrain.surfaces(x, z);
        if (own != null) {
            for (int other : own) {
                int oy = Surfaces.y(other);
                if (oy == y + 1 && Surfaces.footing(other) == Footing.CLIMB) {
                    sink.accept(x, oy, z, other, profile.climbCost());
                } else if (oy == y - 1 && from == Footing.CLIMB) {
                    sink.accept(x, oy, z, other, profile.climbCost());
                }
            }
        }
        for (int[] d : DIRECTIONS) {
            int nx = x + d[0];
            int nz = z + d[1];
            int[] column = terrain.surfaces(nx, nz);
            if (column == null) {
                continue;
            }
            boolean diagonal = d[0] != 0 && d[1] != 0;
            if (diagonal && (!hasSurfaceAt(terrain, x + d[0], z, y) || !hasSurfaceAt(terrain, x, z + d[1], y))) {
                continue;
            }
            for (int target : column) {
                int dy = Surfaces.y(target) - y;
                boolean allowed = diagonal ? dy == 0 : canStep(surface, from, target, dy);
                if (allowed) {
                    sink.accept(nx, Surfaces.y(target), nz, target, cost(diagonal, dy, target));
                }
            }
        }
    }

    private boolean canStep(int from, Footing fromFooting, int to, int dy) {
        if (dy == 0) {
            return true;
        }
        if (dy == 1) {
            return fromFooting == Footing.CLIMB || Surfaces.halfSupport(to) || Surfaces.headroom(from) >= 3;
        }
        if (dy < 0) {
            // The body moves sideways at the original height, then falls: the target column must be
            // open from the landing spot up to the original head height.
            return -dy <= profile.maxDrop() && Surfaces.headroom(to) >= 2 - dy;
        }
        return false;
    }

    private double cost(boolean diagonal, int dy, int target) {
        double horizontal = diagonal ? SQRT2 : 1.0;
        double cost = Math.sqrt(horizontal * horizontal + dy * dy);
        double multiplier = 1.0;
        if (Surfaces.footing(target) == Footing.WATER) {
            multiplier = Math.max(multiplier, profile.waterCost());
        }
        if (Surfaces.door(target)) {
            multiplier = Math.max(multiplier, profile.doorCost());
        }
        cost *= multiplier;
        if (dy == 1 && !Surfaces.halfSupport(target)) {
            cost += STEP_UP_COST;
        }
        return cost;
    }

    private static boolean hasSurfaceAt(Terrain terrain, int x, int z, int y) {
        int[] column = terrain.surfaces(x, z);
        if (column == null) {
            return false;
        }
        for (int s : column) {
            if (Surfaces.y(s) == y) {
                return true;
            }
        }
        return false;
    }
}
