package com.minecraftcitiesnetwork.directions.walk.coarse;

import com.minecraftcitiesnetwork.directions.walk.Bounds;
import com.minecraftcitiesnetwork.directions.walk.CellSetGoal;
import com.minecraftcitiesnetwork.directions.walk.Goal;
import com.minecraftcitiesnetwork.directions.walk.Terrain;
import com.minecraftcitiesnetwork.directions.walk.WalkRules;
import com.minecraftcitiesnetwork.directions.walk.Window;
import com.minecraftcitiesnetwork.directions.walk.WindowSearch;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Turns a path over the coarse graph into a goal inside one search window. */
public final class CoarsePlanner {
    private CoarsePlanner() {
    }

    /**
     * Where the window search should head next.
     *
     * @param waypoint  the component of the coarse path the window search should reach
     * @param goal      the waypoint's cells
     * @param remaining the coarse length from the waypoint to the path's end, plus the goal's
     *                  estimate from that end
     */
    public record Plan(ComponentId waypoint, CellSetGoal goal, double remaining) {
    }

    /** As {@link #plan(CoarseGraph, Terrain, WalkRules, WindowSearch.Start, Goal, Window, int, long)} with no deadline. */
    public static Plan plan(CoarseGraph graph, Terrain terrain, WalkRules rules, WindowSearch.Start start,
                            Goal goal, Window window, int maxExpansions) {
        return plan(graph, terrain, rules, start, goal, window, maxExpansions, Long.MAX_VALUE);
    }

    /**
     * A plan toward {@code goal}, or null when the coarse graph cannot give one.
     *
     * @param deadlineNanos {@link System#nanoTime()} value after which the coarse search gives up
     */
    public static Plan plan(CoarseGraph graph, Terrain terrain, WalkRules rules, WindowSearch.Start start,
                            Goal goal, Window window, int maxExpansions, long deadlineNanos) {
        ComponentId startId;
        try {
            startId = ChunkComponents.componentOf(terrain, start.x() >> 4, start.z() >> 4,
                    start.x(), start.y(), start.z(), rules);
        } catch (IllegalStateException evicted) {
            // A column of the start chunk was evicted from the column store.
            return null;
        }
        if (startId == null) {
            return null;
        }
        Set<ComponentId> goals = goalNodes(graph, goal);
        if (goals.isEmpty()) {
            return null;
        }
        if (!(CoarseSearch.search(graph, startId, goals, goal, maxExpansions, deadlineNanos) instanceof CoarseSearch.Found found)) {
            return null;
        }
        List<ComponentId> path = found.path();
        ComponentId waypoint = Waypoints.pick(path, window, terrain);
        if (waypoint == null) {
            return null;
        }
        Set<Long> cells;
        try {
            cells = ChunkComponents.cellsOf(terrain, waypoint, rules);
        } catch (IllegalStateException evicted) {
            // Waypoints.pick checks only the representative column; another column may be gone.
            return null;
        }
        if (cells.isEmpty()) {
            // The terrain changed since the graph was built: the representative is no longer a surface.
            return null;
        }
        double remaining = 0.0;
        for (int i = path.indexOf(waypoint); i < path.size() - 1; i++) {
            remaining += CoarseSearch.distance(path.get(i), path.get(i + 1));
        }
        ComponentId last = path.get(path.size() - 1);
        remaining += goal.estimate(last.x(), last.y(), last.z());
        return new Plan(waypoint, new CellSetGoal(cells), remaining);
    }

    /**
     * The nodes of the chunks overlapping the goal's bounds whose height range overlaps them too;
     * of those, only the ones whose representative cell is in the goal when there are any, so the
     * coarse path ends on the goal rather than beside it. Only chunks the graph has are visited, so
     * a goal the size of the world costs no more than the graph.
     */
    public static Set<ComponentId> goalNodes(CoarseGraph graph, Goal goal) {
        CoarseGraph.ChunkBounds extent = graph.bounds();
        if (extent == null) {
            return Set.of();
        }
        Bounds bounds = goal.bounds();
        int minCx = Math.max(bounds.minX() >> 4, extent.minX());
        int maxCx = Math.min(bounds.maxX() >> 4, extent.maxX());
        int minCz = Math.max(bounds.minZ() >> 4, extent.minZ());
        int maxCz = Math.min(bounds.maxZ() >> 4, extent.maxZ());
        Set<ComponentId> overlapping = new HashSet<>();
        Set<ComponentId> inside = new HashSet<>();
        for (int cx = minCx; cx <= maxCx; cx++) {
            for (int cz = minCz; cz <= maxCz; cz++) {
                for (CoarseNode node : graph.nodesOf(cx, cz)) {
                    if (node.maxY() >= bounds.minY() && node.minY() <= bounds.maxY()) {
                        ComponentId id = node.id();
                        overlapping.add(id);
                        if (goal.contains(id.x(), id.y(), id.z())) {
                            inside.add(id);
                        }
                    }
                }
            }
        }
        return inside.isEmpty() ? overlapping : inside;
    }
}
