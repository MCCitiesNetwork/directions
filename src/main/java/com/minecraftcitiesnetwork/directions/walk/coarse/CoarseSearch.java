package com.minecraftcitiesnetwork.directions.walk.coarse;

import com.minecraftcitiesnetwork.directions.walk.Goal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/** A* over the chunk-component graph. Edge cost is the straight-line distance between representative cells. */
public final class CoarseSearch {
    public sealed interface Result permits Found, Failure {}

    /** The path runs from the start to a goal component, both inclusive; length is the sum of its edge costs. */
    public record Found(List<ComponentId> path, double length) implements Result {}

    public enum Failure implements Result { NO_PATH, BUDGET }

    private record Entry(double f, double h, ComponentId id, double g) {}

    private static final Comparator<ComponentId> ID_ORDER = Comparator
            .comparingInt(ComponentId::chunkX)
            .thenComparingInt(ComponentId::chunkZ)
            .thenComparingInt(ComponentId::x)
            .thenComparingInt(ComponentId::y)
            .thenComparingInt(ComponentId::z);

    private static final Comparator<Entry> ENTRY_ORDER = Comparator
            .comparingDouble(Entry::f)
            .thenComparingDouble(Entry::h)
            .thenComparing(Entry::id, ID_ORDER);

    private CoarseSearch() {}

    public static Result search(CoarseGraph graph, ComponentId start, Set<ComponentId> goals, Goal goal,
            int maxExpansions) {
        if (goals.isEmpty() || graph.node(start) == null) {
            return Failure.NO_PATH;
        }
        PriorityQueue<Entry> open = new PriorityQueue<>(ENTRY_ORDER);
        Map<ComponentId, Double> bestG = new HashMap<>();
        Map<ComponentId, ComponentId> parents = new HashMap<>();
        double startH = goal.estimate(start.x(), start.y(), start.z());
        open.add(new Entry(startH, startH, start, 0.0));
        bestG.put(start, 0.0);
        int expansions = 0;
        while (!open.isEmpty()) {
            Entry entry = open.poll();
            if (entry.g() > bestG.get(entry.id())) {
                continue;
            }
            if (++expansions > maxExpansions) {
                return Failure.BUDGET;
            }
            if (goals.contains(entry.id())) {
                return new Found(pathTo(entry.id(), parents), entry.g());
            }
            CoarseNode node = graph.node(entry.id());
            if (node == null) {
                continue;
            }
            for (ComponentId next : node.edges()) {
                // A concurrent update can leave an edge whose target is gone for a moment.
                if (graph.node(next) == null) {
                    continue;
                }
                double g = entry.g() + distance(entry.id(), next);
                Double known = bestG.get(next);
                if (known != null && known <= g) {
                    continue;
                }
                bestG.put(next, g);
                parents.put(next, entry.id());
                double h = goal.estimate(next.x(), next.y(), next.z());
                open.add(new Entry(g + h, h, next, g));
            }
        }
        return Failure.NO_PATH;
    }

    private static double distance(ComponentId a, ComponentId b) {
        double dx = a.x() - b.x();
        double dy = a.y() - b.y();
        double dz = a.z() - b.z();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static List<ComponentId> pathTo(ComponentId end, Map<ComponentId, ComponentId> parents) {
        List<ComponentId> path = new ArrayList<>();
        for (ComponentId at = end; at != null; at = parents.get(at)) {
            path.add(at);
        }
        Collections.reverse(path);
        return path;
    }
}
