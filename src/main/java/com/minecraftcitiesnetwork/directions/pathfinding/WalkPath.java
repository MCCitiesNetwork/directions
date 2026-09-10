package com.minecraftcitiesnetwork.directions.pathfinding;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * An immutable sequence of block positions a player can walk, with cumulative distances, and the
 * helpers navigation needs: where am I along it, how far is left, what should the arrow point at.
 */
public final class WalkPath {
    private final int[] xs;
    private final int[] ys;
    private final int[] zs;
    private final double[] cumulative;

    public WalkPath(List<Pos> nodes) {
        if (nodes.isEmpty()) {
            throw new IllegalArgumentException("a path needs at least one node");
        }
        int n = nodes.size();
        xs = new int[n];
        ys = new int[n];
        zs = new int[n];
        cumulative = new double[n];
        for (int i = 0; i < n; i++) {
            Pos p = nodes.get(i);
            xs[i] = p.x();
            ys[i] = p.y();
            zs[i] = p.z();
            cumulative[i] = i == 0 ? 0.0 : cumulative[i - 1] + p.distance(nodes.get(i - 1));
        }
    }

    public int size() {
        return xs.length;
    }

    public Pos node(int index) {
        return new Pos(xs[index], ys[index], zs[index]);
    }

    public Pos start() {
        return node(0);
    }

    public Pos end() {
        return node(xs.length - 1);
    }

    public List<Pos> nodes() {
        List<Pos> out = new ArrayList<>(xs.length);
        for (int i = 0; i < xs.length; i++) {
            out.add(node(i));
        }
        return Collections.unmodifiableList(out);
    }

    /** Total walking distance from first to last node, in blocks. */
    public double totalDistance() {
        return cumulative[cumulative.length - 1];
    }

    /**
     * Index of the node nearest to the player. {@code hint} is the previous answer; the search
     * scans a window around it first and only falls back to the whole path when the player is far
     * from that window, so per-tick calls stay cheap on long paths.
     */
    public int nearestIndex(double px, double py, double pz, int hint) {
        int from = Math.max(0, hint - 4);
        int to = Math.min(xs.length - 1, hint + 24);
        int best = scan(px, py, pz, from, to);
        if (distanceSquared(best, px, py, pz) > 64.0 && (from > 0 || to < xs.length - 1)) {
            best = scan(px, py, pz, 0, xs.length - 1);
        }
        return best;
    }

    private int scan(double px, double py, double pz, int from, int to) {
        int best = from;
        double bestD = Double.MAX_VALUE;
        for (int i = from; i <= to; i++) {
            double d = distanceSquared(i, px, py, pz);
            if (d < bestD) {
                bestD = d;
                best = i;
            }
        }
        return best;
    }

    private double distanceSquared(int i, double px, double py, double pz) {
        double dx = xs[i] + 0.5 - px;
        double dy = (ys[i] - py) * 0.5;
        double dz = zs[i] + 0.5 - pz;
        return dx * dx + dy * dy + dz * dz;
    }

    /** Horizontal distance from the player to the given node's centre. */
    public double distanceToNode(int index, double px, double pz) {
        double dx = xs[index] + 0.5 - px;
        double dz = zs[index] + 0.5 - pz;
        return Math.sqrt(dx * dx + dz * dz);
    }

    /** Blocks left to walk from the player, given the nearest node index. */
    public double remainingFrom(int nearest, double px, double pz) {
        return (cumulative[cumulative.length - 1] - cumulative[nearest]) + distanceToNode(nearest, px, pz);
    }

    /** The node roughly {@code blocks} further along the path than {@code nearest}. */
    public Pos lookAhead(int nearest, double blocks) {
        double limit = cumulative[nearest] + blocks;
        for (int i = nearest; i < xs.length; i++) {
            if (cumulative[i] >= limit) {
                return node(i);
            }
        }
        return end();
    }

    @Override
    public String toString() {
        return "WalkPath[" + size() + " nodes, " + Math.round(totalDistance()) + " blocks, " + start() + " -> " + end() + "]";
    }
}
