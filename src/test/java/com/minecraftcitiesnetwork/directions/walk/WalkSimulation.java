package com.minecraftcitiesnetwork.directions.walk;

import java.util.HashSet;
import java.util.Set;

/**
 * A player who follows the guide across a flat synthetic city: every column is either walkable
 * ground at one height or a building. Chunks within view distance of the player are captured;
 * the rest are unknown. Searches answer 100 ms after they are ordered, as on a server.
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
     */
    record Report(int routes, int reversals, int noRouteResults, boolean arrived, double x, double z) {
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

    private final City city;
    private final int windowRadius;
    private final Set<Long> capturedChunks = new HashSet<>();
    private final WindowSearch search = new WindowSearch(new WalkRules(WalkerProfile.DEFAULT), System::nanoTime);
    private final Terrain terrain = this::surfaces;

    /** Walks from (startX, startZ) toward the goal for at most {@code seconds} of simulated time. */
    static Report walk(City city, int windowRadius, double startX, double startZ, Goal goal, double seconds) {
        return new WalkSimulation(city, windowRadius).run(startX, startZ, goal, seconds);
    }

    private WalkSimulation(City city, int windowRadius) {
        this.city = city;
        this.windowRadius = windowRadius;
    }

    private Report run(double x, double z, Goal goal, double seconds) {
        GuidePolicy policy = new GuidePolicy(TUNING);
        int routes = 0;
        int reversals = 0;
        int noRouteResults = 0;
        GuidePolicy.SearchOrder pendingOrder = null;
        SearchResult pendingResult = null;
        long resultDue = 0;
        for (long now = 0; now < seconds * 1000; now += TICK_MILLIS) {
            captureAround(x, z);
            if (pendingResult != null && now >= resultDue) {
                Route before = policy.route();
                double[] headingBefore = before == null ? null : heading(before, policy.cursor());
                policy.onResult(pendingOrder.generation(), pendingResult, now);
                Route after = policy.route();
                if (pendingResult.found()) {
                    routes++;
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
                pendingResult = search.search(terrain, request);
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
                return new Report(routes, reversals, noRouteResults, true, x, z);
            }
        }
        return new Report(routes, reversals, noRouteResults, false, x, z);
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
