package com.minecraftcitiesnetwork.directions.walk;

import org.junit.jupiter.api.Test;

import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WindowSearchTest {
    private static final WalkRules RULES = new WalkRules(WalkerProfile.DEFAULT);
    private static final WindowSearch SEARCH = new WindowSearch(RULES, System::nanoTime);
    private static final double EPS = 1e-9;

    private static BlockWorld flat(int sizeX, int sizeZ) {
        return new BlockWorld(64).fill(0, 64, 0, sizeX - 1, 64, sizeZ - 1, BlockKind.SOLID);
    }

    /** The world as decoded, with everything outside it known to be empty rather than unknown. */
    private static Terrain allKnown(BlockWorld world) {
        Terrain t = world.terrain();
        return (x, z) -> {
            int[] s = t.surfaces(x, z);
            return s == null ? new int[0] : s;
        };
    }

    /** Floor from x −20..40, z −20..20, so a window around the origin has the player in its middle. */
    private static BlockWorld field() {
        return new BlockWorld(64).fill(-20, 64, -20, 40, 64, 20, BlockKind.SOLID);
    }

    private static SearchRequest request(double x, double y, double z, Goal goal, Window window) {
        return new SearchRequest(x, y, z, goal, window, null, 1_000_000, Long.MAX_VALUE);
    }

    private static BoxGoal at(int x, int y, int z) {
        return new BoxGoal(x, y, z, x, y, z);
    }

    @Test
    void straightLineOnOpenFloor() {
        SearchResult r = SEARCH.search(flat(40, 40).terrain(), request(0.5, 65, 0.5, at(10, 65, 0), new Window(0, 0, 39, 39)));
        assertEquals(SearchResult.Status.COMPLETE, r.status());
        assertEquals(10.0, r.cost(), EPS);
        assertEquals(11, r.route().size());
        assertEquals(new Cell(10, 65, 0), r.route().end());
        assertTrue(r.route().complete());
    }

    @Test
    void diagonalsCostTheirLength() {
        SearchResult r = SEARCH.search(flat(40, 40).terrain(), request(0.5, 65, 0.5, at(5, 65, 5), new Window(0, 0, 39, 39)));
        assertEquals(5 * Math.sqrt(2), r.cost(), EPS);
    }

    @Test
    void walksAroundAWall() {
        BlockWorld world = flat(40, 40).fill(5, 65, 0, 5, 66, 30, BlockKind.SOLID);
        SearchResult r = SEARCH.search(world.terrain(), request(0.5, 65, 0.5, at(10, 65, 0), new Window(0, 0, 39, 39)));
        assertEquals(SearchResult.Status.COMPLETE, r.status());
        assertTrue(r.cost() > 10.0);
        for (int i = 0; i < r.route().size(); i++) {
            assertFalse(r.route().x(i) == 5 && r.route().z(i) <= 30, "route crosses the wall at " + r.route().cell(i));
        }
    }

    @Test
    void regionGoalReachesItsNearestCell() {
        SearchResult r = SEARCH.search(flat(40, 40).terrain(),
                request(0.5, 65, 0.5, new BoxGoal(10, 65, 0, 20, 65, 10), new Window(0, 0, 39, 39)));
        assertEquals(new Cell(10, 65, 0), r.route().end());
        assertEquals(10.0, r.cost(), EPS);
    }

    @Test
    void startInsideTheGoalIsCompleteAtOnce() {
        SearchResult r = SEARCH.search(flat(10, 10).terrain(), request(3.5, 65, 3.5, new BoxGoal(0, 60, 0, 9, 70, 9), new Window(0, 0, 9, 9)));
        assertEquals(SearchResult.Status.COMPLETE, r.status());
        assertEquals(1, r.route().size());
        assertEquals(0.0, r.cost(), EPS);
    }

    @Test
    void goalOutsideTheWindowEndsOnTheBorder() {
        SearchResult r = SEARCH.search(field().terrain(), request(0.5, 65, 0.5, at(35, 65, 0), Window.around(0, 0, 20)));
        assertEquals(SearchResult.Status.PARTIAL, r.status());
        assertEquals(new Cell(20, 65, 0), r.route().end());
        assertFalse(r.route().complete());
        assertEquals(15.0, r.route().endEstimate(), EPS);
    }

    @Test
    void wallAcrossTheWindowIsLeftByTheBestReachableEdge() {
        BlockWorld world = flat(40, 40).fill(10, 65, 0, 10, 66, 20, BlockKind.SOLID);
        Terrain t = world.terrain();
        Window window = new Window(0, 0, 20, 20);
        Goal goal = at(35, 65, 10);
        SearchResult r = SEARCH.search(t, request(2.5, 65, 10.5, goal, window));
        assertEquals(SearchResult.Status.PARTIAL, r.status());
        assertTrue(r.route().end().x() < 10, "exit must be on this side of the wall: " + r.route().end());
        WindowSearch.Start start = WindowSearch.snap(t, 2.5, 65, 10.5, window);
        assertEquals(bestExit(t, start, goal, window), r.cost() + r.route().endEstimate(), EPS);
    }

    @Test
    void exitBehindTheRouteBeingFollowedCostsExtra() {
        // On a one-block-wide road, heading west toward (-20, 0) while the goal lies east. Turning
        // east would save 40 blocks of straight-line distance, less than the three-radius penalty.
        BlockWorld road = new BlockWorld(64).fill(-20, 64, 0, 40, 64, 0, BlockKind.SOLID);
        SearchRequest req = new SearchRequest(0.5, 65, 0.5, at(35, 65, 0), Window.around(0, 0, 20),
                new Cell(-20, 65, 0), 1_000_000, Long.MAX_VALUE);
        SearchResult r = SEARCH.search(allKnown(road), req);
        assertEquals(SearchResult.Status.PARTIAL, r.status());
        assertEquals(-20, r.route().end().x(), "turned back: " + r.route().end());
    }

    @Test
    void exitBehindTheRouteBeingFollowedIsTakenWhenItIsTheOnlyWayOut() {
        BlockWorld world = flat(40, 40).fill(10, 65, 0, 10, 66, 39, BlockKind.SOLID);
        SearchRequest req = new SearchRequest(5.5, 65, 10.5, at(35, 65, 10), new Window(0, 0, 20, 20),
                new Cell(20, 65, 10), 1_000_000, Long.MAX_VALUE);
        SearchResult r = SEARCH.search(allKnown(world), req);
        assertEquals(SearchResult.Status.PARTIAL, r.status());
        assertTrue(r.route().end().x() < 10, "exit must be on this side of the wall: " + r.route().end());
    }

    @Test
    void unknownTerrainActsAsAnEdge() {
        Terrain base = field().terrain();
        Terrain clipped = (x, z) -> x > 10 ? null : base.surfaces(x, z);
        SearchResult r = SEARCH.search(clipped, request(0.5, 65, 0.5, at(35, 65, 0), new Window(-20, -20, 39, 20)));
        assertEquals(SearchResult.Status.PARTIAL, r.status());
        assertEquals(10, r.route().end().x());
    }

    @Test
    void searchOnwardRunsOnlyTheExitPhase() {
        // The goal overlaps the window but lies behind uncaptured terrain: search() runs the direct
        // phase, finds nothing and leads to the edge of the unknown; searchOnward goes there at once.
        Terrain base = field().terrain();
        Terrain clipped = (x, z) -> x > 10 ? null : base.surfaces(x, z);
        SearchRequest req = request(0.5, 65, 0.5, at(35, 65, 0), new Window(-20, -20, 39, 20));
        SearchResult full = SEARCH.search(clipped, req);
        SearchResult onward = SEARCH.searchOnward(clipped, req);
        assertEquals(SearchResult.Status.PARTIAL, onward.status());
        assertEquals(full.route().end(), onward.route().end());
        assertEquals(full.expansions(), onward.expansions());
    }

    @Test
    void searchOnwardLeavesByAnyExitWhenTheGoalIsOutsideTheWindow() {
        SearchRequest req = request(0.5, 65, 0.5, at(35, 65, 0), Window.around(0, 0, 20));
        SearchResult full = SEARCH.search(field().terrain(), req);
        SearchResult onward = SEARCH.searchOnward(field().terrain(), req);
        assertEquals(full.status(), onward.status());
        assertEquals(full.route().end(), onward.route().end());
    }

    @Test
    void expansionCapReportsBudget() {
        SearchRequest req = new SearchRequest(0.5, 65, 0.5, at(0, 200, 0), new Window(0, 0, 39, 39), null, 5, Long.MAX_VALUE);
        assertEquals(SearchResult.Status.BUDGET, SEARCH.search(flat(40, 40).terrain(), req).status());
    }

    @Test
    void deadlineReportsBudget() {
        AtomicLong clock = new AtomicLong();
        WindowSearch slow = new WindowSearch(RULES, () -> clock.addAndGet(1_000_000));
        SearchRequest req = new SearchRequest(0.5, 65, 0.5, at(0, 200, 0), new Window(0, 0, 39, 39), null, 1_000_000, 1);
        assertEquals(SearchResult.Status.BUDGET, slow.search(flat(40, 40).terrain(), req).status());
    }

    @Test
    void unreachableGoalInsideFullyKnownTerrainIsNoRoute() {
        SearchResult r = SEARCH.search(allKnown(flat(40, 40)), request(0.5, 65, 0.5, at(0, 200, 0), new Window(0, 0, 39, 39)));
        assertEquals(SearchResult.Status.NO_ROUTE, r.status());
    }

    @Test
    void unreachableGoalNextToUnknownTerrainLeadsTowardIt() {
        SearchResult r = SEARCH.search(flat(40, 40).terrain(), request(5.5, 65, 5.5, at(5, 200, 5), new Window(0, 0, 39, 39)));
        assertEquals(SearchResult.Status.PARTIAL, r.status());
        Cell end = r.route().end();
        assertTrue(end.x() == 0 || end.z() == 0, "should end beside the undecoded chunks at x < 0 or z < 0: " + end);
    }

    @Test
    void startOnTheBorderIsNotAnExit() {
        SearchResult r = SEARCH.search(field().terrain(), request(0.5, 65, 0.5, at(35, 65, 0), new Window(0, -20, 20, 20)));
        assertEquals(SearchResult.Status.PARTIAL, r.status());
        assertTrue(r.route().size() > 1);
    }

    @Test
    void startInMidAirIsInvalid() {
        SearchResult r = SEARCH.search(flat(10, 10).terrain(), request(0.5, 80, 0.5, at(5, 65, 0), new Window(0, 0, 9, 9)));
        assertEquals(SearchResult.Status.INVALID_START, r.status());
    }

    @Test
    void startOnAnUncapturedColumnIsReportedAsUncaptured() {
        SearchResult r = SEARCH.search(flat(10, 10).terrain(), request(50.5, 65, 50.5, at(5, 65, 0), new Window(0, 0, 60, 60)));
        assertEquals(SearchResult.Status.UNCAPTURED_START, r.status());
    }

    @Test
    void negativeCoordinatesWork() {
        BlockWorld world = new BlockWorld(64).fill(-30, 64, -30, -1, 64, -1, BlockKind.SOLID);
        SearchResult r = SEARCH.search(world.terrain(), request(-0.5, 65, -0.5, at(-20, 65, -1), new Window(-30, -30, -1, -1)));
        assertEquals(SearchResult.Status.COMPLETE, r.status());
        assertEquals(new Cell(-1, 65, -1), r.route().cell(0));
        assertEquals(19.0, r.cost(), EPS);
    }

    @Test
    void startOnSlabSnapsOntoIt() {
        BlockWorld world = flat(10, 10).set(0, 65, 0, BlockKind.HALF);
        SearchResult r = SEARCH.search(world.terrain(), request(0.5, 65.5, 0.5, at(5, 65, 0), new Window(0, 0, 9, 9)));
        assertEquals(SearchResult.Status.COMPLETE, r.status());
        assertEquals(new Cell(0, 66, 0), r.route().cell(0));
    }

    @Test
    void swimmingStartSnapsToWaterSurface() {
        BlockWorld world = new BlockWorld(63).fill(0, 63, 0, 9, 63, 9, BlockKind.SOLID)
                .fill(0, 64, 0, 2, 64, 2, BlockKind.WATER)
                .fill(3, 64, 0, 9, 64, 9, BlockKind.SOLID)
                .fill(0, 64, 3, 2, 64, 9, BlockKind.SOLID);
        SearchResult r = SEARCH.search(world.terrain(), request(0.5, 64.3, 0.5, at(6, 65, 0), new Window(0, 0, 9, 9)));
        assertEquals(SearchResult.Status.COMPLETE, r.status());
        assertEquals(new Cell(0, 64, 0), r.route().cell(0));
    }

    @Test
    void matchesDijkstraOnRandomWorlds() {
        int checked = 0;
        for (long seed = 1; seed <= 200; seed++) {
            Terrain t = allKnown(randomWorld(seed, 24));
            Window window = new Window(0, 0, 23, 23);
            Goal goal = new BoxGoal(18, 60, 18, 23, 70, 23);
            WindowSearch.Start start = WindowSearch.snap(t, 0.5, 66, 0.5, window);
            if (start == null) {
                continue;
            }
            checked++;
            double expected = bestGoal(t, start, goal, window);
            SearchResult r = SEARCH.search(t, request(0.5, 66, 0.5, goal, window));
            if (Double.isInfinite(expected)) {
                assertEquals(SearchResult.Status.NO_ROUTE, r.status(), "seed " + seed);
            } else {
                assertEquals(SearchResult.Status.COMPLETE, r.status(), "seed " + seed);
                assertEquals(expected, r.cost(), EPS, "seed " + seed);
            }
        }
        assertTrue(checked >= 150, "only " + checked + " seeds had a valid start");
    }

    @Test
    void exitMinimisesCostPlusEstimateOnRandomWorlds() {
        for (long seed = 1; seed <= 100; seed++) {
            Terrain t = randomWorld(seed, 24).terrain();
            Window window = new Window(0, 0, 15, 15);
            Goal goal = new BoxGoal(40, 60, 40, 45, 70, 45);
            WindowSearch.Start start = WindowSearch.snap(t, 7.5, 66, 7.5, window);
            if (start == null) {
                continue;
            }
            double expected = bestExit(t, start, goal, window);
            SearchResult r = SEARCH.search(t, request(7.5, 66, 7.5, goal, window));
            if (Double.isInfinite(expected)) {
                assertEquals(SearchResult.Status.NO_ROUTE, r.status(), "seed " + seed);
            } else {
                assertEquals(SearchResult.Status.PARTIAL, r.status(), "seed " + seed);
                assertNotNull(r.route());
                assertEquals(expected, r.cost() + r.route().endEstimate(), EPS, "seed " + seed);
            }
        }
    }

    // ---- reference implementation -------------------------------------------------------------

    private record Item(double d, int x, int y, int z, int surface) {
    }

    /** Plain Dijkstra over the same rules: cost of every reachable cell, keyed like {@link Cell#key}. */
    private static Map<Long, double[]> dijkstra(Terrain t, WindowSearch.Start s, Window w) {
        Map<Long, double[]> best = new HashMap<>();
        PriorityQueue<Item> queue = new PriorityQueue<>(Comparator.comparingDouble(Item::d));
        best.put(Cell.key(s.x(), s.y(), s.z()), new double[]{0, s.x(), s.y(), s.z()});
        queue.add(new Item(0, s.x(), s.y(), s.z(), s.surface()));
        while (!queue.isEmpty()) {
            Item it = queue.poll();
            if (it.d() > best.get(Cell.key(it.x(), it.y(), it.z()))[0]) {
                continue;
            }
            RULES.neighbours(t, it.x(), it.z(), it.surface(), (x, y, z, surface, cost) -> {
                if (!w.contains(x, z)) {
                    return;
                }
                long key = Cell.key(x, y, z);
                double d = it.d() + cost;
                double[] old = best.get(key);
                if (old == null || d < old[0]) {
                    best.put(key, new double[]{d, x, y, z});
                    queue.add(new Item(d, x, y, z, surface));
                }
            });
        }
        return best;
    }

    private static double bestGoal(Terrain t, WindowSearch.Start s, Goal goal, Window w) {
        double best = Double.POSITIVE_INFINITY;
        for (double[] e : dijkstra(t, s, w).values()) {
            if (goal.contains((int) e[1], (int) e[2], (int) e[3])) {
                best = Math.min(best, e[0]);
            }
        }
        return best;
    }

    private static double bestExit(Terrain t, WindowSearch.Start s, Goal goal, Window w) {
        double best = Double.POSITIVE_INFINITY;
        for (double[] e : dijkstra(t, s, w).values()) {
            int x = (int) e[1];
            int z = (int) e[3];
            if (x == s.x() && (int) e[2] == s.y() && z == s.z()) {
                continue;
            }
            boolean exit = w.onBorder(x, z)
                    || t.surfaces(x + 1, z) == null || t.surfaces(x - 1, z) == null
                    || t.surfaces(x, z + 1) == null || t.surfaces(x, z - 1) == null;
            if (exit) {
                best = Math.min(best, e[0] + goal.estimate(x, (int) e[2], z));
            }
        }
        return best;
    }

    private static BlockWorld randomWorld(long seed, int size) {
        Random random = new Random(seed);
        BlockWorld world = new BlockWorld(63);
        for (int x = 0; x < size; x++) {
            for (int z = 0; z < size; z++) {
                world.set(x, 63, z, BlockKind.SOLID);
                double roll = random.nextDouble();
                if (roll < 0.15) {
                    world.fill(x, 64, z, x, 66, z, BlockKind.SOLID);
                } else if (roll < 0.25) {
                    world.fill(x, 64, z, x, 65, z, BlockKind.SOLID);
                } else if (roll < 0.30) {
                    world.set(x, 64, z, BlockKind.WATER);
                } else if (roll < 0.33) {
                    world.set(x, 64, z, BlockKind.HALF);
                } else if (roll < 0.36) {
                    world.set(x, 64, z, BlockKind.FENCE);
                } else {
                    world.set(x, 64, z, BlockKind.SOLID);
                }
                if (random.nextDouble() < 0.05) {
                    world.set(x, 67, z, BlockKind.SOLID);
                }
            }
        }
        return world;
    }
}
