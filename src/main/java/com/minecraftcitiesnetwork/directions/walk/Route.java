package com.minecraftcitiesnetwork.directions.walk;

import java.util.List;

/**
 * An immutable walking route: feet cells from the start to either the goal ({@link #complete()})
 * or the edge of the search window. Distances are measured between cells.
 */
public final class Route {
    private static final int LOOK_BACK = 8;
    private static final int LOOK_FORWARD = 64;
    private static final double LOCAL_MATCH = 8.0;

    private final int[] xs;
    private final int[] ys;
    private final int[] zs;
    private final double[] cumulative;
    private final boolean complete;
    private final double endEstimate;

    private Route(int[] xs, int[] ys, int[] zs, boolean complete, double endEstimate) {
        this.xs = xs;
        this.ys = ys;
        this.zs = zs;
        this.complete = complete;
        this.endEstimate = endEstimate;
        this.cumulative = new double[xs.length];
        for (int i = 1; i < xs.length; i++) {
            double dx = xs[i] - xs[i - 1];
            double dy = ys[i] - ys[i - 1];
            double dz = zs[i] - zs[i - 1];
            cumulative[i] = cumulative[i - 1] + Math.sqrt(dx * dx + dy * dy + dz * dz);
        }
    }

    /**
     * @param complete    whether the route reaches the goal
     * @param endEstimate for a partial route, the goal's estimate from its last cell; 0 when complete
     */
    public static Route of(List<Cell> cells, boolean complete, double endEstimate) {
        if (cells.isEmpty()) {
            throw new IllegalArgumentException("a route needs at least one cell");
        }
        int n = cells.size();
        int[] xs = new int[n];
        int[] ys = new int[n];
        int[] zs = new int[n];
        for (int i = 0; i < n; i++) {
            Cell c = cells.get(i);
            xs[i] = c.x();
            ys[i] = c.y();
            zs[i] = c.z();
        }
        return new Route(xs, ys, zs, complete, endEstimate);
    }

    /** The same cells as a partial route whose goal is {@code endEstimate} beyond its end. */
    public Route asPartial(double endEstimate) {
        return new Route(xs, ys, zs, false, endEstimate);
    }

    public int size() {
        return xs.length;
    }

    public int x(int i) {
        return xs[i];
    }

    public int y(int i) {
        return ys[i];
    }

    public int z(int i) {
        return zs[i];
    }

    public Cell cell(int i) {
        return new Cell(xs[i], ys[i], zs[i]);
    }

    public Cell end() {
        return cell(xs.length - 1);
    }

    public boolean complete() {
        return complete;
    }

    public double endEstimate() {
        return endEstimate;
    }

    public double length() {
        return cumulative[cumulative.length - 1];
    }

    /** Straight-line distance from a player position to the centre of cell {@code i} at feet height. */
    public double distanceTo(int i, double px, double py, double pz) {
        double dx = xs[i] + 0.5 - px;
        double dy = ys[i] - py;
        double dz = zs[i] + 0.5 - pz;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /**
     * The route index nearest the player, looking a little behind and well ahead of {@code hint}
     * first so a route that doubles back on itself does not skip ahead, then the whole route when
     * nothing nearby is close.
     */
    public int nearestIndex(double px, double py, double pz, int hint) {
        int h = Math.max(0, Math.min(size() - 1, hint));
        int best = nearestBetween(Math.max(0, h - LOOK_BACK), Math.min(size(), h + LOOK_FORWARD), px, py, pz);
        if (distanceTo(best, px, py, pz) > LOCAL_MATCH) {
            best = nearestBetween(0, size(), px, py, pz);
        }
        return best;
    }

    /** Distance from the player to cell {@code i}, plus the route from there to its end. */
    public double remainingFrom(int i, double px, double py, double pz) {
        return distanceTo(i, px, py, pz) + (length() - cumulative[i]);
    }

    /** The first index at least {@code blocks} along the route from {@code i}, or the last index. */
    public int indexAhead(int i, double blocks) {
        for (int j = i; j < size(); j++) {
            if (cumulative[j] - cumulative[i] >= blocks) {
                return j;
            }
        }
        return size() - 1;
    }

    /**
     * The first of {@code count} cells from {@code from} that the terrain no longer has a surface at,
     * or −1. Cells in unknown columns are assumed still walkable.
     */
    public int firstMissing(Terrain terrain, int from, int count) {
        int end = Math.min(size(), from + count);
        for (int i = Math.max(0, from); i < end; i++) {
            int[] column = terrain.surfaces(xs[i], zs[i]);
            if (column == null) {
                continue;
            }
            boolean present = false;
            for (int s : column) {
                if (Surfaces.y(s) == ys[i]) {
                    present = true;
                    break;
                }
            }
            if (!present) {
                return i;
            }
        }
        return -1;
    }

    private int nearestBetween(int from, int to, double px, double py, double pz) {
        int best = from;
        double bestDistance = Double.MAX_VALUE;
        for (int i = from; i < to; i++) {
            double d = distanceTo(i, px, py, pz);
            if (d < bestDistance) {
                bestDistance = d;
                best = i;
            }
        }
        return best;
    }
}
