package com.minecraftcitiesnetwork.directions.walk;

/**
 * A polygonal region: inside the polygon (edges inclusive) between {@code minY} and {@code maxY}
 * inclusive. The estimate is the distance to the polygon's bounding box, which never overestimates.
 */
public final class PolygonGoal implements Goal {
    private final int[] xs;
    private final int[] zs;
    private final BoxGoal bounds;

    public PolygonGoal(int[] xs, int[] zs, int minY, int maxY) {
        if (xs.length != zs.length || xs.length < 3) {
            throw new IllegalArgumentException("a polygon needs at least three points");
        }
        this.xs = xs.clone();
        this.zs = zs.clone();
        int minX = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (int i = 0; i < xs.length; i++) {
            minX = Math.min(minX, xs[i]);
            maxX = Math.max(maxX, xs[i]);
            minZ = Math.min(minZ, zs[i]);
            maxZ = Math.max(maxZ, zs[i]);
        }
        this.bounds = new BoxGoal(minX, minY, minZ, maxX, maxY, maxZ);
    }

    @Override
    public boolean contains(int x, int y, int z) {
        return bounds.contains(x, y, z) && inside(x, z);
    }

    @Override
    public double estimate(int x, int y, int z) {
        return bounds.estimate(x, y, z);
    }

    @Override
    public boolean mayOverlap(Window window) {
        return bounds.mayOverlap(window);
    }

    private boolean inside(int px, int pz) {
        boolean in = false;
        for (int i = 0, j = xs.length - 1; i < xs.length; j = i++) {
            int xi = xs[i];
            int zi = zs[i];
            int xj = xs[j];
            int zj = zs[j];
            if (onSegment(xi, zi, xj, zj, px, pz)) {
                return true;
            }
            if ((zi > pz) != (zj > pz)) {
                double crossX = xi + (double) (pz - zi) * (xj - xi) / (zj - zi);
                if (px < crossX) {
                    in = !in;
                }
            }
        }
        return in;
    }

    private static boolean onSegment(int ax, int az, int bx, int bz, int px, int pz) {
        long cross = (long) (bx - ax) * (pz - az) - (long) (bz - az) * (px - ax);
        return cross == 0
                && px >= Math.min(ax, bx) && px <= Math.max(ax, bx)
                && pz >= Math.min(az, bz) && pz <= Math.max(az, bz);
    }
}
