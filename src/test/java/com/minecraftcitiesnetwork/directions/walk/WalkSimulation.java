package com.minecraftcitiesnetwork.directions.walk;

import com.minecraftcitiesnetwork.directions.walk.coarse.ChunkComponents;
import com.minecraftcitiesnetwork.directions.walk.coarse.ChunkEdges;
import com.minecraftcitiesnetwork.directions.walk.coarse.ChunkKeys;
import com.minecraftcitiesnetwork.directions.walk.coarse.CoarseGraph;
import com.minecraftcitiesnetwork.directions.walk.coarse.CoarseNode;
import com.minecraftcitiesnetwork.directions.walk.coarse.Component;
import com.minecraftcitiesnetwork.directions.walk.coarse.GuidedSearch;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A player who follows the guide across a flat synthetic city: every column is either walkable
 * ground at one height or a building. Chunks within view distance of the player are captured;
 * the rest are unknown. Searches answer 100 ms after they are ordered, as on a server. In coarse
 * mode every search goes through {@link GuidedSearch} with a graph of the whole city.
 */
final class WalkSimulation {
    /** Which columns a player can walk on. */
    interface City {
        boolean walkable(int x, int z);
    }

    /**
     * @param routes        routes found over the walk
     * @param reversals     routes that pointed the player back along the way they were walking
     * @param noRouteResults searches that found nothing while the player had a route to follow
     * @param arrived       whether the player reached the goal within the time
     * @param guidedRoutes  routes found that lead to a waypoint chosen with the coarse graph
     * @param trail         the player's (x, z) once per simulated second, from the start position
     */
    record Report(int routes, int reversals, int noRouteResults, boolean arrived, double x, double z,
                  int guidedRoutes, List<double[]> trail) {
        @Override
        public String toString() {
            return "Report[routes=" + routes + ", reversals=" + reversals + ", noRouteResults=" + noRouteResults
                    + ", arrived=" + arrived + ", x=" + x + ", z=" + z + ", guidedRoutes=" + guidedRoutes
                    + ", trail=" + trail.size() + " samples]";
        }
    }

    private static final int VIEW_DISTANCE_BLOCKS = 128;
    private static final double BLOCKS_PER_SECOND = 4.3;
    private static final long TICK_MILLIS = 50;
    private static final long SEARCH_LATENCY_MILLIS = 100;
    private static final double HEADING_SPAN = 10;
    private static final int FEET_Y = 64;
    private static final int[] GROUND = {Surfaces.pack(FEET_Y, Footing.GROUND, Surfaces.MAX_HEADROOM, false, false)};
    private static final int[] BUILDING = new int[0];
    private static final GuidePolicy.Tuning TUNING = new GuidePolicy.Tuning(48, 6, 2000);
    private static final WalkRules RULES = new WalkRules(WalkerProfile.DEFAULT);
    private static final int GRAPH_MIN_X = -200;
    private static final int GRAPH_MAX_X = 1200;
    private static final int GRAPH_MIN_Z = -900;
    private static final int GRAPH_MAX_Z = 900;
    private static final int COARSE_MAX_EXPANSIONS = 50_000;
    /** Graphs already built, one per city; building one takes seconds. */
    private static final Map<City, CoarseGraph> GRAPHS = new HashMap<>();

    private final City city;
    private final int windowRadius;
    private final Set<Long> capturedChunks = new HashSet<>();
    private final WindowSearch search = new WindowSearch(RULES, System::nanoTime);
    private final GuidedSearch guided = new GuidedSearch(search, RULES);
    private final Terrain terrain = this::surfaces;
    /** The city's graph in coarse mode, otherwise null. */
    private final CoarseGraph graph;

    /** Walks from (startX, startZ) toward the goal for at most {@code seconds} of simulated time. */
    static Report walk(City city, int windowRadius, double startX, double startZ, Goal goal, double seconds) {
        return walk(city, windowRadius, startX, startZ, goal, seconds, false);
    }

    /** As {@link #walk(City, int, double, double, Goal, double)}, guided by the city's coarse graph when {@code coarse}. */
    static Report walk(City city, int windowRadius, double startX, double startZ, Goal goal, double seconds,
                       boolean coarse) {
        return new WalkSimulation(city, windowRadius, coarse ? graphOf(city) : null).run(startX, startZ, goal, seconds);
    }

    private WalkSimulation(City city, int windowRadius, CoarseGraph graph) {
        this.city = city;
        this.windowRadius = windowRadius;
        this.graph = graph;
    }

    private static synchronized CoarseGraph graphOf(City city) {
        return GRAPHS.computeIfAbsent(city, WalkSimulation::buildGraph);
    }

    /**
     * The graph of every chunk over x −200..1200, z −900..900, from the city fully known. Two passes:
     * all nodes first, then the edges, since the graph drops edges to nodes it does not have yet.
     */
    private static CoarseGraph buildGraph(City city) {
        Terrain known = (x, z) -> city.walkable(x, z) ? GROUND : BUILDING;
        Map<Long, List<Component>> components = new HashMap<>();
        Map<Long, List<CoarseNode>> bare = new HashMap<>();
        for (int cx = GRAPH_MIN_X >> 4; cx <= GRAPH_MAX_X >> 4; cx++) {
            for (int cz = GRAPH_MIN_Z >> 4; cz <= GRAPH_MAX_Z >> 4; cz++) {
                List<Component> list = ChunkComponents.of(known, cx, cz, RULES);
                components.put(ChunkKeys.key(cx, cz), list);
                bare.put(ChunkKeys.key(cx, cz), list.stream()
                        .map(c -> new CoarseNode(c.id(), c.minY(), c.maxY(), List.of())).toList());
            }
        }
        CoarseGraph graph = CoarseGraph.of(bare);
        components.forEach((key, list) -> {
            ChunkEdges.Linked linked = ChunkEdges.compute(known, RULES, ChunkKeys.x(key), ChunkKeys.z(key), list);
            graph.replace(ChunkKeys.x(key), ChunkKeys.z(key), linked.nodes(), linked.sides());
        });
        return graph;
    }

    private Report run(double x, double z, Goal goal, double seconds) {
        GuidePolicy policy = new GuidePolicy(TUNING);
        int routes = 0;
        int reversals = 0;
        int noRouteResults = 0;
        int guidedRoutes = 0;
        List<double[]> trail = new ArrayList<>();
        GuidePolicy.SearchOrder pendingOrder = null;
        SearchResult pendingResult = null;
        long resultDue = 0;
        for (long now = 0; now < seconds * 1000; now += TICK_MILLIS) {
            if (now % 1000 == 0) {
                trail.add(new double[] {x, z});
            }
            captureAround(x, z);
            if (pendingResult != null && now >= resultDue) {
                Route before = policy.route();
                double[] headingBefore = before == null ? null : heading(before, policy.cursor());
                policy.onResult(pendingOrder.generation(), pendingResult, now);
                Route after = policy.route();
                if (pendingResult.found()) {
                    routes++;
                    if (pendingResult.coarse()) {
                        guidedRoutes++;
                    }
                    double[] headingAfter = heading(after, after.nearestIndex(x, FEET_Y, z, 0));
                    if (turnsBack(headingBefore, headingAfter)) {
                        reversals++;
                    }
                } else if (before != null) {
                    noRouteResults++;
                }
                pendingResult = null;
            }
            Route route = policy.route();
            boolean blocked = route != null && route.firstMissing(terrain, policy.cursor(), 40) >= 0;
            GuidePolicy.SearchOrder order = policy.tick(new GuidePolicy.Observation(now, x, FEET_Y, z, true, blocked));
            if (order != null) {
                Window window = Window.around((int) Math.floor(x), (int) Math.floor(z), windowRadius);
                SearchRequest request = new SearchRequest(x, FEET_Y, z, goal, window, order.routeEnd(),
                        200_000, Long.MAX_VALUE);
                pendingOrder = order;
                pendingResult = graph == null ? search.search(terrain, request)
                        : guided.search(terrain, request, graph, COARSE_MAX_EXPANSIONS, Long.MAX_VALUE);
                resultDue = now + SEARCH_LATENCY_MILLIS;
            }
            route = policy.route();
            if (route == null) {
                continue;
            }
            int next = Math.min(route.size() - 1, policy.cursor() + 1);
            double dx = route.x(next) + 0.5 - x;
            double dz = route.z(next) + 0.5 - z;
            double distance = Math.hypot(dx, dz);
            double step = Math.min(distance, BLOCKS_PER_SECOND * TICK_MILLIS / 1000.0);
            if (distance > 0) {
                x += dx / distance * step;
                z += dz / distance * step;
            }
            if (goal.contains((int) Math.floor(x), FEET_Y, (int) Math.floor(z))) {
                return new Report(routes, reversals, noRouteResults, true, x, z, guidedRoutes, trail);
            }
        }
        return new Report(routes, reversals, noRouteResults, false, x, z, guidedRoutes, trail);
    }

    private void captureAround(double x, double z) {
        int chunkX = (int) Math.floor(x) >> 4;
        int chunkZ = (int) Math.floor(z) >> 4;
        int radius = VIEW_DISTANCE_BLOCKS >> 4;
        for (int cx = chunkX - radius; cx <= chunkX + radius; cx++) {
            for (int cz = chunkZ - radius; cz <= chunkZ + radius; cz++) {
                capturedChunks.add(chunkKey(cx, cz));
            }
        }
    }

    private int[] surfaces(int x, int z) {
        if (!capturedChunks.contains(chunkKey(x >> 4, z >> 4))) {
            return null;
        }
        return city.walkable(x, z) ? GROUND : BUILDING;
    }

    /** The unit direction of the route over the next few blocks from index {@code from}, or null at its end. */
    private static double[] heading(Route route, int from) {
        int to = route.indexAhead(from, HEADING_SPAN);
        double dx = route.x(to) - route.x(from);
        double dz = route.z(to) - route.z(from);
        double length = Math.hypot(dx, dz);
        return length == 0 ? null : new double[] {dx / length, dz / length};
    }

    private static boolean turnsBack(double[] before, double[] after) {
        return before != null && after != null && before[0] * after[0] + before[1] * after[1] < 0;
    }

    private static long chunkKey(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
    }
}
