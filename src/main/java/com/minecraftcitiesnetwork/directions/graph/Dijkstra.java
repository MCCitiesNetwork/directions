package com.minecraftcitiesnetwork.directions.graph;

import com.minecraftcitiesnetwork.directions.model.RouteResult;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

public final class Dijkstra {
    private Dijkstra() {
    }

    public static RouteResult shortestPath(Map<String, Map<String, Double>> graph, String start, String target) {
        Map<String, Double> dist = new HashMap<>();
        Map<String, String> prev = new HashMap<>();
        PriorityQueue<NodeDist> pq = new PriorityQueue<>(Comparator.comparingDouble(NodeDist::distance));

        for (String node : graph.keySet()) {
            dist.put(node, Double.POSITIVE_INFINITY);
        }
        dist.put(start, 0.0);
        pq.add(new NodeDist(start, 0.0));

        while (!pq.isEmpty()) {
            NodeDist current = pq.poll();
            if (current.distance() > dist.getOrDefault(current.node(), Double.POSITIVE_INFINITY)) {
                continue;
            }
            if (current.node().equals(target)) {
                break;
            }
            Map<String, Double> neighbors = graph.getOrDefault(current.node(), Map.of());
            for (Map.Entry<String, Double> entry : neighbors.entrySet()) {
                String next = entry.getKey();
                double nd = current.distance() + entry.getValue();
                if (nd < dist.getOrDefault(next, Double.POSITIVE_INFINITY)) {
                    dist.put(next, nd);
                    prev.put(next, current.node());
                    pq.add(new NodeDist(next, nd));
                }
            }
        }

        if (!dist.containsKey(target) || Double.isInfinite(dist.get(target))) {
            return new RouteResult(List.of(), Double.POSITIVE_INFINITY);
        }

        List<String> path = new ArrayList<>();
        String cur = target;
        while (cur != null) {
            path.add(0, cur);
            cur = prev.get(cur);
        }
        return new RouteResult(path, dist.get(target));
    }

    private record NodeDist(String node, double distance) {
    }
}
