package com.minecraftcitiesnetwork.directions.graph;

import com.minecraftcitiesnetwork.directions.model.Line;
import com.minecraftcitiesnetwork.directions.model.RouteResult;
import com.minecraftcitiesnetwork.directions.model.Stop;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

public class TransitGraph {
    private final Map<String, Stop> stopsById;
    private final Map<String, Map<String, Double>> staticAdjacency;
    private final Set<String> transitEdges;
    private final Map<String, Set<String>> transitEdgeLines;
    private final Map<String, Set<String>> stopTransitKinds;
    private final double maxWalkingDistance;
    private final String walkingTransferPolicy;

    public TransitGraph(
            Collection<Stop> stops,
            List<Line> lines,
            double transferPenalty,
            double maxWalkingDistance,
            String walkingTransferPolicy
    ) {
        this.maxWalkingDistance = maxWalkingDistance;
        this.walkingTransferPolicy = walkingTransferPolicy;
        this.stopsById = new HashMap<>();
        this.staticAdjacency = new HashMap<>();
        this.transitEdges = new HashSet<>();
        this.transitEdgeLines = new HashMap<>();
        this.stopTransitKinds = new HashMap<>();

        for (Stop stop : stops) {
            stopsById.put(stop.regionId(), stop);
            staticAdjacency.put(stop.regionId(), new HashMap<>());
            stopTransitKinds.put(stop.regionId(), new HashSet<>());
        }

        indexStopTransitKinds(lines);
        buildTransitEdges(lines, transferPenalty);
        buildWalkingEdges();
    }

    public Map<String, Stop> getStopsById() {
        return stopsById;
    }

    public boolean isTransitHop(String from, String to) {
        return transitEdges.contains(edgeKey(from, to));
    }

    public Set<String> transitLineNames(String from, String to) {
        return transitEdgeLines.getOrDefault(edgeKey(from, to), Set.of());
    }

    public RouteResult route(
            String startNode,
            String targetNode,
            Map<String, Double> startToStops,
            Map<String, Double> stopsToTarget
    ) {
        Map<String, Map<String, Double>> working = copyGraph(staticAdjacency);
        working.putIfAbsent(startNode, new HashMap<>());
        working.putIfAbsent(targetNode, new HashMap<>());

        for (Map.Entry<String, Double> edge : startToStops.entrySet()) {
            addBidirectionalEdge(working, startNode, edge.getKey(), edge.getValue());
        }
        for (Map.Entry<String, Double> edge : stopsToTarget.entrySet()) {
            addBidirectionalEdge(working, targetNode, edge.getKey(), edge.getValue());
        }
        return Dijkstra.shortestPath(working, startNode, targetNode);
    }

    public RouteResult routeAStar(
            String startNode,
            double startX,
            double startZ,
            String targetNode,
            double targetX,
            double targetZ,
            Map<String, Double> startToStops,
            Map<String, Double> stopsToTarget
    ) {
        Map<String, Map<String, Double>> working = copyGraph(staticAdjacency);
        working.putIfAbsent(startNode, new HashMap<>());
        working.putIfAbsent(targetNode, new HashMap<>());

        for (Map.Entry<String, Double> edge : startToStops.entrySet()) {
            addBidirectionalEdge(working, startNode, edge.getKey(), edge.getValue());
        }
        for (Map.Entry<String, Double> edge : stopsToTarget.entrySet()) {
            addBidirectionalEdge(working, targetNode, edge.getKey(), edge.getValue());
        }

        Map<String, NodePos> pos = new HashMap<>();
        for (Stop stop : stopsById.values()) {
            pos.put(stop.regionId(), new NodePos(stop.x(), stop.z()));
        }
        pos.put(startNode, new NodePos(startX, startZ));
        pos.put(targetNode, new NodePos(targetX, targetZ));

        return aStarShortestPath(working, pos, startNode, targetNode);
    }

    public List<StopDistance> nearestStops(double x, double z, String worldName, int limit) {
        List<StopDistance> candidates = new ArrayList<>();
        for (Stop stop : stopsById.values()) {
            if (!stop.worldName().equalsIgnoreCase(worldName)) {
                continue;
            }
            double dx = stop.x() - x;
            double dz = stop.z() - z;
            candidates.add(new StopDistance(stop.regionId(), Math.sqrt(dx * dx + dz * dz)));
        }
        candidates.sort((a, b) -> Double.compare(a.distance(), b.distance()));
        if (candidates.size() > limit) {
            return candidates.subList(0, limit);
        }
        return candidates;
    }

    public List<StopDistance> stopsWithinDistance(double x, double z, String worldName) {
        List<StopDistance> inRange = new ArrayList<>();
        for (StopDistance sd : nearestStops(x, z, worldName, Integer.MAX_VALUE)) {
            if (sd.distance() <= maxWalkingDistance) {
                inRange.add(sd);
            }
        }
        return inRange;
    }

    private void buildTransitEdges(List<Line> lines, double transferPenalty) {
        for (Line line : lines) {
            List<String> ids = line.stops();
            for (int i = 0; i < ids.size(); i++) {
                for (int j = i + 1; j < ids.size(); j++) {
                Stop a = stopsById.get(ids.get(i));
                Stop b = stopsById.get(ids.get(j));
                if (a == null || b == null || !a.worldName().equalsIgnoreCase(b.worldName())) {
                    continue;
                }
                double cost = a.distance2D(b) + transferPenalty;
                addBidirectionalEdge(staticAdjacency, a.regionId(), b.regionId(), cost);
                transitEdges.add(edgeKey(a.regionId(), b.regionId()));
                transitEdges.add(edgeKey(b.regionId(), a.regionId()));
                transitEdgeLines.computeIfAbsent(edgeKey(a.regionId(), b.regionId()), __ -> new HashSet<>()).add(line.id());
                transitEdgeLines.computeIfAbsent(edgeKey(b.regionId(), a.regionId()), __ -> new HashSet<>()).add(line.id());
                }
            }
        }
    }

    private void buildWalkingEdges() {
        List<Stop> all = new ArrayList<>(stopsById.values());
        for (int i = 0; i < all.size(); i++) {
            for (int j = i + 1; j < all.size(); j++) {
                Stop a = all.get(i);
                Stop b = all.get(j);
                if (!a.worldName().equalsIgnoreCase(b.worldName())) {
                    continue;
                }
                if (!shareTransitKind(a.regionId(), b.regionId())) {
                    continue;
                }
                double dist = a.distance2D(b);
                if (dist <= maxWalkingDistance) {
                    addBidirectionalEdge(staticAdjacency, a.regionId(), b.regionId(), dist);
                }
            }
        }
    }

    private void indexStopTransitKinds(List<Line> lines) {
        for (Line line : lines) {
            String kind = line.type().toLowerCase();
            if (!kind.equals("bus") && !kind.equals("train")) {
                continue;
            }
            for (String stopId : line.stops()) {
                stopTransitKinds.computeIfAbsent(stopId, __ -> new HashSet<>()).add(kind);
            }
        }
    }

    private boolean shareTransitKind(String stopA, String stopB) {
        if (walkingTransferPolicy.equals("strict")) {
            return true;
        }
        Set<String> aKinds = stopTransitKinds.getOrDefault(stopA, Set.of());
        Set<String> bKinds = stopTransitKinds.getOrDefault(stopB, Set.of());
        if (aKinds.isEmpty() || bKinds.isEmpty()) {
            return true;
        }
        for (String kind : aKinds) {
            if (bKinds.contains(kind)) {
                return true;
            }
        }
        return false;
    }

    private static Map<String, Map<String, Double>> copyGraph(Map<String, Map<String, Double>> source) {
        Map<String, Map<String, Double>> copy = new HashMap<>();
        for (Map.Entry<String, Map<String, Double>> e : source.entrySet()) {
            copy.put(e.getKey(), new HashMap<>(e.getValue()));
        }
        return copy;
    }

    private static void addBidirectionalEdge(Map<String, Map<String, Double>> graph, String a, String b, double w) {
        graph.putIfAbsent(a, new HashMap<>());
        graph.putIfAbsent(b, new HashMap<>());
        Map<String, Double> aNeighbors = graph.get(a);
        Map<String, Double> bNeighbors = graph.get(b);
        aNeighbors.put(b, Math.min(aNeighbors.getOrDefault(b, Double.POSITIVE_INFINITY), w));
        bNeighbors.put(a, Math.min(bNeighbors.getOrDefault(a, Double.POSITIVE_INFINITY), w));
    }

    private static String edgeKey(String a, String b) {
        return a + "->" + b;
    }

    private static RouteResult aStarShortestPath(
            Map<String, Map<String, Double>> graph,
            Map<String, NodePos> nodePositions,
            String start,
            String target
    ) {
        Map<String, Double> gScore = new HashMap<>();
        Map<String, String> prev = new HashMap<>();
        PriorityQueue<NodeF> open = new PriorityQueue<>(Comparator.comparingDouble(NodeF::fScore));

        gScore.put(start, 0.0);
        open.add(new NodeF(start, heuristic(nodePositions, start, target)));

        while (!open.isEmpty()) {
            NodeF current = open.poll();
            String node = current.node();

            if (node.equals(target)) {
                break;
            }

            double currentG = gScore.getOrDefault(node, Double.POSITIVE_INFINITY);
            Map<String, Double> neighbors = graph.getOrDefault(node, Map.of());
            for (Map.Entry<String, Double> entry : neighbors.entrySet()) {
                String next = entry.getKey();
                double tentative = currentG + entry.getValue();
                if (tentative < gScore.getOrDefault(next, Double.POSITIVE_INFINITY)) {
                    gScore.put(next, tentative);
                    prev.put(next, node);
                    double f = tentative + heuristic(nodePositions, next, target);
                    open.add(new NodeF(next, f));
                }
            }
        }

        double total = gScore.getOrDefault(target, Double.POSITIVE_INFINITY);
        if (Double.isInfinite(total)) {
            return new RouteResult(List.of(), Double.POSITIVE_INFINITY);
        }

        List<String> path = new ArrayList<>();
        String cur = target;
        while (cur != null) {
            path.add(0, cur);
            cur = prev.get(cur);
        }
        return new RouteResult(path, total);
    }

    private static double heuristic(Map<String, NodePos> nodePositions, String node, String target) {
        NodePos a = nodePositions.get(node);
        NodePos b = nodePositions.get(target);
        if (a == null || b == null) {
            return 0.0;
        }
        return a.distance2D(b);
    }

    private record NodePos(double x, double z) {
        private double distance2D(NodePos other) {
            double dx = x - other.x;
            double dz = z - other.z;
            return Math.sqrt(dx * dx + dz * dz);
        }
    }

    private record NodeF(String node, double fScore) {
    }

    public record StopDistance(String stopId, double distance) {
    }
}
