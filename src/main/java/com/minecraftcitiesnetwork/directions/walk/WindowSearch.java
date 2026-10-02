package com.minecraftcitiesnetwork.directions.walk;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.function.LongSupplier;

/**
 * A* over walkable surfaces inside a window. Reaches the goal when it can; when no goal cell can be
 * in the window, ends at the exit that minimises cost so far plus straight-line distance onward.
 * Deterministic: identical inputs give identical routes. Safe to share between threads.
 */
public final class WindowSearch {
    public static final int SNAP_RADIUS = 3;
    public static final int SPENT_EXIT_RADIUS = 8;
    private static final int CLOCK_CHECK_MASK = 255;

    /** The surface a search starts from. */
    public record Start(int x, int y, int z, int surface) {
    }

    private final WalkRules rules;
    private final LongSupplier nanoClock;

    public WindowSearch(WalkRules rules, LongSupplier nanoClock) {
        this.rules = rules;
        this.nanoClock = nanoClock;
    }

    public SearchResult search(Terrain terrain, SearchRequest request) {
        long started = nanoClock.getAsLong();
        Start start = snap(terrain, request.x(), request.y(), request.z(), request.window());
        if (start == null) {
            return SearchResult.failed(SearchResult.Status.INVALID_START, 0, nanoClock.getAsLong() - started);
        }
        if (!request.goal().mayOverlap(request.window())) {
            return run(terrain, request, start, Exits.ANY, started);
        }
        SearchResult direct = run(terrain, request, start, Exits.NONE, started);
        if (direct.status() != SearchResult.Status.NO_ROUTE) {
            return direct;
        }
        // The goal may lie behind terrain that is not captured yet: lead toward it instead.
        return run(terrain, request, start, Exits.UNKNOWN_ONLY, started);
    }

    private enum Exits {
        /** Only the goal ends the search. */
        NONE,
        /** The goal, or a cell next to uncaptured terrain. */
        UNKNOWN_ONLY,
        /** The goal, a cell next to uncaptured terrain, or the window border. */
        ANY
    }

    private SearchResult run(Terrain terrain, SearchRequest request, Start start, Exits exits, long started) {
        Window window = request.window();
        Goal goal = request.goal();
        Map<Long, Node> nodes = new HashMap<>();
        PriorityQueue<Entry> open = new PriorityQueue<>();
        Node first = new Node(start.x(), start.y(), start.z(), start.surface());
        first.g = 0.0;
        nodes.put(first.key, first);
        open.add(new Entry(first, 0.0, goal.estimate(start.x(), start.y(), start.z())));
        int expansions = 0;
        while (!open.isEmpty()) {
            Entry entry = open.poll();
            Node node = entry.node();
            if (node.closed || entry.g() != node.g) {
                continue;
            }
            node.closed = true;
            if (goal.contains(node.x, node.y, node.z)) {
                return finish(SearchResult.Status.COMPLETE, node, 0.0, expansions, started);
            }
            if (exits != Exits.NONE && node != first && isExit(terrain, request, node.x, node.z, exits == Exits.ANY)) {
                return finish(SearchResult.Status.PARTIAL, node, goal.estimate(node.x, node.y, node.z), expansions, started);
            }
            if (expansions >= request.maxExpansions()) {
                return SearchResult.failed(SearchResult.Status.BUDGET, expansions, nanoClock.getAsLong() - started);
            }
            expansions++;
            if ((expansions & CLOCK_CHECK_MASK) == 0 && nanoClock.getAsLong() > request.deadlineNanos()) {
                return SearchResult.failed(SearchResult.Status.BUDGET, expansions, nanoClock.getAsLong() - started);
            }
            Node from = node;
            rules.neighbours(terrain, node.x, node.z, node.surface, (x, y, z, surface, cost) -> {
                if (!window.contains(x, z)) {
                    return;
                }
                long key = Cell.key(x, y, z);
                double g = from.g + cost;
                Node next = nodes.get(key);
                if (next == null) {
                    next = new Node(x, y, z, surface);
                    nodes.put(key, next);
                } else if (next.closed || g >= next.g) {
                    return;
                }
                next.g = g;
                next.parent = from;
                open.add(new Entry(next, g, goal.estimate(x, y, z)));
            });
        }
        return SearchResult.failed(SearchResult.Status.NO_ROUTE, expansions, nanoClock.getAsLong() - started);
    }

    /**
     * The nearest surface to a player position: rings of growing horizontal distance up to
     * {@link #SNAP_RADIUS}, within {@link #SNAP_RADIUS} blocks of height, closest first.
     */
    public static Start snap(Terrain terrain, double x, double y, double z, Window window) {
        int fx = (int) Math.floor(x);
        int fy = (int) Math.floor(y);
        int fz = (int) Math.floor(z);
        for (int r = 0; r <= SNAP_RADIUS; r++) {
            Start best = null;
            int bestDistance = Integer.MAX_VALUE;
            int bestDy = Integer.MAX_VALUE;
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) {
                        continue;
                    }
                    int cx = fx + dx;
                    int cz = fz + dz;
                    if (!window.contains(cx, cz)) {
                        continue;
                    }
                    int[] column = terrain.surfaces(cx, cz);
                    if (column == null) {
                        continue;
                    }
                    for (int s : column) {
                        int dy = Math.abs(Surfaces.y(s) - fy);
                        int d = dx * dx + dz * dz;
                        if (dy <= SNAP_RADIUS && (d < bestDistance || (d == bestDistance && dy < bestDy))) {
                            best = new Start(cx, Surfaces.y(s), cz, s);
                            bestDistance = d;
                            bestDy = dy;
                        }
                    }
                }
            }
            if (best != null) {
                return best;
            }
        }
        return null;
    }

    private static boolean isExit(Terrain terrain, SearchRequest request, int x, int z, boolean border) {
        boolean edge = (border && request.window().onBorder(x, z))
                || terrain.surfaces(x + 1, z) == null || terrain.surfaces(x - 1, z) == null
                || terrain.surfaces(x, z + 1) == null || terrain.surfaces(x, z - 1) == null;
        if (!edge) {
            return false;
        }
        for (Cell spent : request.spentExits()) {
            if (Math.abs(spent.x() - x) <= SPENT_EXIT_RADIUS && Math.abs(spent.z() - z) <= SPENT_EXIT_RADIUS) {
                return false;
            }
        }
        return true;
    }

    private SearchResult finish(SearchResult.Status status, Node last, double endEstimate, int expansions, long started) {
        List<Cell> cells = new ArrayList<>();
        for (Node n = last; n != null; n = n.parent) {
            cells.add(new Cell(n.x, n.y, n.z));
        }
        Collections.reverse(cells);
        Route route = Route.of(cells, status == SearchResult.Status.COMPLETE, endEstimate);
        return new SearchResult(status, route, last.g, expansions, nanoClock.getAsLong() - started);
    }

    private static final class Node {
        final int x;
        final int y;
        final int z;
        final int surface;
        final long key;
        double g = Double.POSITIVE_INFINITY;
        Node parent;
        boolean closed;

        Node(int x, int y, int z, int surface) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.surface = surface;
            this.key = Cell.key(x, y, z);
        }
    }

    /** A queue entry; stale once its node has a lower g. Ties break on h, then on position. */
    private record Entry(Node node, double g, double f, double h) implements Comparable<Entry> {
        Entry(Node node, double g, double h) {
            this(node, g, g + h, h);
        }

        @Override
        public int compareTo(Entry o) {
            int c = Double.compare(f, o.f);
            if (c != 0) {
                return c;
            }
            c = Double.compare(h, o.h);
            if (c != 0) {
                return c;
            }
            return Long.compare(node.key, o.node.key);
        }
    }
}
