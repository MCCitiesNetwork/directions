package com.minecraftcitiesnetwork.directions.walk.coarse;

import com.minecraftcitiesnetwork.directions.walk.BlockKind;
import com.minecraftcitiesnetwork.directions.walk.BlockWorld;
import com.minecraftcitiesnetwork.directions.walk.BoxGoal;
import com.minecraftcitiesnetwork.directions.walk.Goal;
import com.minecraftcitiesnetwork.directions.walk.SearchRequest;
import com.minecraftcitiesnetwork.directions.walk.SearchResult;
import com.minecraftcitiesnetwork.directions.walk.Terrain;
import com.minecraftcitiesnetwork.directions.walk.WalkRules;
import com.minecraftcitiesnetwork.directions.walk.WalkerProfile;
import com.minecraftcitiesnetwork.directions.walk.Window;
import com.minecraftcitiesnetwork.directions.walk.WindowSearch;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GuidedSearchTest {
    private static final WalkRules RULES = new WalkRules(WalkerProfile.DEFAULT);
    private static final WindowSearch WINDOW_SEARCH = new WindowSearch(RULES, System::nanoTime);
    private static final GuidedSearch GUIDED = new GuidedSearch(WINDOW_SEARCH, RULES);
    private static final int START_X = 8;
    private static final int START_Z = 24;
    private static final int COARSE_MAX_EXPANSIONS = 10_000;
    private static final long BUDGET = Long.MAX_VALUE;

    /**
     * 20 x 3 chunks of flat floor (feet at y 65) with a wall too tall to climb or drop from filling
     * chunk column 10, except in chunk row 2.
     */
    private static BlockWorld world() {
        return new BlockWorld(0)
                .fill(0, 64, 0, 319, 64, 47, BlockKind.SOLID)
                .fill(160, 65, 0, 175, 72, 31, BlockKind.SOLID);
    }

    private static final Terrain WORLD = world().terrain();
    private static final Terrain NEAR_START = nearStart(WORLD);
    private static final Goal FAR_GOAL = new BoxGoal(306, 60, 20, 312, 70, 28);
    private static final Window WINDOW = Window.around(START_X, START_Z, 80);
    private static final CoarseGraph GRAPH = graph(WORLD);

    /** What a search sees: only columns within 128 blocks of the start are captured. */
    private static Terrain nearStart(Terrain all) {
        return (x, z) -> Math.abs(x - START_X) > 128 || Math.abs(z - START_Z) > 128 ? null : all.surfaces(x, z);
    }

    /** The graph of every chunk of the world, built in two passes so that every edge finds its node. */
    private static CoarseGraph graph(Terrain terrain) {
        Map<Long, List<Component>> components = new HashMap<>();
        Map<Long, List<CoarseNode>> bare = new HashMap<>();
        for (int cx = 0; cx < 20; cx++) {
            for (int cz = 0; cz < 3; cz++) {
                List<Component> list = ChunkComponents.of(terrain, cx, cz, RULES);
                components.put(ChunkKeys.key(cx, cz), list);
                bare.put(ChunkKeys.key(cx, cz), list.stream()
                        .map(c -> new CoarseNode(c.id(), c.minY(), c.maxY(), List.of())).toList());
            }
        }
        CoarseGraph graph = CoarseGraph.of(bare);
        components.forEach((key, list) -> {
            ChunkEdges.Linked linked = ChunkEdges.compute(terrain, RULES, ChunkKeys.x(key), ChunkKeys.z(key), list);
            graph.replace(ChunkKeys.x(key), ChunkKeys.z(key), linked.nodes(), linked.sides());
        });
        return graph;
    }

    private static SearchRequest request(Goal goal) {
        return new SearchRequest(START_X + 0.5, 65, START_Z + 0.5, goal, WINDOW, null, 1_000_000, Long.MAX_VALUE);
    }

    @Test
    void goalInsideTheWindowIsReachedDirectly() {
        SearchResult result = GUIDED.search(NEAR_START, request(new BoxGoal(40, 65, 24, 40, 65, 24)), GRAPH,
                COARSE_MAX_EXPANSIONS, BUDGET);
        assertEquals(SearchResult.Status.COMPLETE, result.status());
        assertFalse(result.coarse());
        assertTrue(result.route().complete());
    }

    @Test
    void waypointRouteIsReportedPartialWithCoarseRemaining() {
        WindowSearch.Start start = WindowSearch.snap(NEAR_START, START_X + 0.5, 65, START_Z + 0.5, WINDOW);
        CoarsePlanner.Plan plan = CoarsePlanner.plan(GRAPH, NEAR_START, RULES, start, FAR_GOAL, WINDOW,
                COARSE_MAX_EXPANSIONS);
        assertNotNull(plan);

        SearchResult result = GUIDED.search(NEAR_START, request(FAR_GOAL), GRAPH, COARSE_MAX_EXPANSIONS, BUDGET);
        assertEquals(SearchResult.Status.PARTIAL, result.status());
        assertTrue(result.coarse());
        assertFalse(result.route().complete());
        assertEquals(plan.remaining(), result.route().endEstimate(), 1e-9);
        var end = result.route().end();
        assertTrue(plan.goal().contains(end.x(), end.y(), end.z()), end::toString);
    }

    @Test
    void unreachableWaypointFallsBackToExitSearch() {
        // The graph still says chunk column 3 is open floor; the terrain now has a wall across it.
        Terrain walled = nearStart(world().fill(60, 65, 0, 63, 72, 47, BlockKind.SOLID).terrain());
        WindowSearch.Start start = WindowSearch.snap(walled, START_X + 0.5, 65, START_Z + 0.5, WINDOW);
        assertNotNull(CoarsePlanner.plan(GRAPH, walled, RULES, start, FAR_GOAL, WINDOW, COARSE_MAX_EXPANSIONS),
                "the graph should still give a plan beyond the wall");

        SearchResult result = GUIDED.search(walled, request(FAR_GOAL), GRAPH, COARSE_MAX_EXPANSIONS, BUDGET);
        SearchResult exitSearch = WINDOW_SEARCH.search(walled, request(FAR_GOAL));
        assertEquals(SearchResult.Status.PARTIAL, result.status());
        assertFalse(result.coarse());
        assertEquals(exitSearch.route().end(), result.route().end());
    }

    @Test
    void eachPhaseGetsItsOwnDeadlineFromTheBudget() {
        // The request's own deadline has long passed; the guided search ignores it and gives the
        // coarse search and each window search a fresh deadline of the budget.
        SearchRequest stale = new SearchRequest(START_X + 0.5, 65, START_Z + 0.5, FAR_GOAL, WINDOW, null, 1_000_000,
                Long.MIN_VALUE);
        SearchResult result = GUIDED.search(NEAR_START, stale, GRAPH, COARSE_MAX_EXPANSIONS, 10_000_000_000L);
        assertEquals(SearchResult.Status.PARTIAL, result.status());
        assertTrue(result.coarse());
        assertTrue(result.coarseNanos() > 0, "the coarse search time is reported");
    }

    @Test
    void theFallbackDoesNotRepeatTheDirectSearch() {
        // A goal inside the window behind the wall: the direct search fails, the graph has no node for
        // the wall, and the fallback leads on by the exit phase alone, as the plain search would.
        Goal behindTheWall = new BoxGoal(80, 65, 8, 80, 65, 8);
        Terrain walled = nearStart(world().fill(60, 65, 0, 63, 72, 47, BlockKind.SOLID).terrain());
        SearchResult plain = WINDOW_SEARCH.search(walled, request(behindTheWall));
        SearchResult guided = GUIDED.search(walled, request(behindTheWall), GRAPH, COARSE_MAX_EXPANSIONS, BUDGET);
        assertEquals(plain.status(), guided.status());
        assertFalse(guided.coarse());
        if (plain.found()) {
            assertEquals(plain.route().end(), guided.route().end());
        }
    }

    @Test
    void nullGraphBehavesAsBefore() {
        for (Goal goal : List.of(FAR_GOAL, new BoxGoal(40, 65, 24, 40, 65, 24))) {
            SearchResult alone = WINDOW_SEARCH.search(NEAR_START, request(goal));
            SearchResult guided = GUIDED.search(NEAR_START, request(goal), null, COARSE_MAX_EXPANSIONS, BUDGET);
            assertEquals(alone.status(), guided.status());
            assertEquals(alone.route().end(), guided.route().end());
            assertFalse(guided.coarse());
        }
    }
}
