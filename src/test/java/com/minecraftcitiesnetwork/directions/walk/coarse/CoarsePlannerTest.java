package com.minecraftcitiesnetwork.directions.walk.coarse;

import com.minecraftcitiesnetwork.directions.walk.BlockKind;
import com.minecraftcitiesnetwork.directions.walk.BlockWorld;
import com.minecraftcitiesnetwork.directions.walk.BoxGoal;
import com.minecraftcitiesnetwork.directions.walk.Goal;
import com.minecraftcitiesnetwork.directions.walk.Terrain;
import com.minecraftcitiesnetwork.directions.walk.WalkRules;
import com.minecraftcitiesnetwork.directions.walk.WalkerProfile;
import com.minecraftcitiesnetwork.directions.walk.Window;
import com.minecraftcitiesnetwork.directions.walk.WindowSearch;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntPredicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoarsePlannerTest {
    private static final WalkRules RULES = new WalkRules(WalkerProfile.DEFAULT);
    private static final int START_X = 8;
    private static final int START_Z = 24;

    /**
     * 20 x 3 chunks of flat floor (feet at y 65) with a wall too tall to climb or drop from filling
     * chunk column 10, except in chunk row 2.
     */
    private static final Terrain WORLD = new BlockWorld(0)
            .fill(0, 64, 0, 319, 64, 47, BlockKind.SOLID)
            .fill(160, 65, 0, 175, 72, 31, BlockKind.SOLID)
            .terrain();

    /** What a planner sees: only columns within 128 blocks of the start are captured. */
    private static final Terrain NEAR_START = (x, z) ->
            Math.abs(x - START_X) > 128 || Math.abs(z - START_Z) > 128 ? null : WORLD.surfaces(x, z);

    private static final Goal GOAL = new BoxGoal(306, 60, 20, 312, 70, 28);
    private static final Window WINDOW = Window.around(START_X, START_Z, 80);
    private static final WindowSearch.Start START =
            new WindowSearch.Start(START_X, 65, START_Z, WORLD.surfaces(START_X, START_Z)[0]);
    private static final CoarseGraph FULL_GRAPH = graph(cx -> true);

    /** Builds the graph of the chunks whose x passes the filter, from the fully known world. */
    private static CoarseGraph graph(IntPredicate chunkXs) {
        Map<Long, List<Component>> components = new HashMap<>();
        Map<Long, List<CoarseNode>> bare = new HashMap<>();
        for (int cx = 0; cx < 20; cx++) {
            for (int cz = 0; cz < 3; cz++) {
                if (!chunkXs.test(cx)) {
                    continue;
                }
                List<Component> list = ChunkComponents.of(WORLD, cx, cz, RULES);
                components.put(ChunkKeys.key(cx, cz), list);
                bare.put(ChunkKeys.key(cx, cz), list.stream()
                        .map(c -> new CoarseNode(c.id(), c.minY(), c.maxY(), List.of())).toList());
            }
        }
        CoarseGraph graph = CoarseGraph.of(bare);
        components.forEach((key, list) -> {
            int cx = ChunkKeys.x(key);
            int cz = ChunkKeys.z(key);
            ChunkEdges.Linked linked = ChunkEdges.compute(WORLD, RULES, cx, cz, list);
            graph.replace(cx, cz, linked.nodes(), linked.sides());
        });
        return graph;
    }

    private static CoarseSearch.Found fullSearch() {
        ComponentId start = ChunkComponents.componentOf(WORLD, 0, 1, START_X, 65, START_Z, RULES);
        CoarseSearch.Result result = CoarseSearch.search(FULL_GRAPH, start,
                CoarsePlanner.goalNodes(FULL_GRAPH, GOAL), GOAL, 10_000);
        return assertInstanceOf(CoarseSearch.Found.class, result);
    }

    @Test
    void planLeadsTowardTheGap() {
        CoarsePlanner.Plan plan = CoarsePlanner.plan(FULL_GRAPH, NEAR_START, RULES, START, GOAL, WINDOW, 10_000);
        assertNotNull(plan);
        List<ComponentId> path = fullSearch().path();
        assertTrue(path.stream().anyMatch(id -> id.chunkX() == 10 && id.chunkZ() == 2), path::toString);
        ComponentId waypoint = plan.waypoint();
        assertTrue(path.contains(waypoint), waypoint::toString);
        assertTrue(WINDOW.contains(waypoint.chunkX() * 16, waypoint.chunkZ() * 16));
        assertTrue(WINDOW.contains(waypoint.chunkX() * 16 + 15, waypoint.chunkZ() * 16 + 15));
        // Chunk 4 is the last chunk column fully inside the window (x -72..88).
        assertEquals(4, waypoint.chunkX());
        // The search's tie-break keeps to row 1 until the window edge; pinned to catch a regression.
        assertEquals(1, waypoint.chunkZ());
        assertTrue(plan.goal().contains(waypoint.x(), waypoint.y(), waypoint.z()));
        assertFalse(plan.goal().contains(START_X, 65, START_Z));
    }

    @Test
    void remainingIsTheCoarseLengthBeyondTheWaypoint() {
        CoarsePlanner.Plan plan = CoarsePlanner.plan(FULL_GRAPH, NEAR_START, RULES, START, GOAL, WINDOW, 10_000);
        assertNotNull(plan);
        List<ComponentId> path = fullSearch().path();
        double expected = 0.0;
        for (int i = path.indexOf(plan.waypoint()); i < path.size() - 1; i++) {
            expected += CoarseSearch.distance(path.get(i), path.get(i + 1));
        }
        ComponentId last = path.get(path.size() - 1);
        expected += GOAL.estimate(last.x(), last.y(), last.z());
        assertEquals(expected, plan.remaining(), 1e-9);
        ComponentId w = plan.waypoint();
        assertTrue(plan.remaining() > GOAL.estimate(w.x(), w.y(), w.z()));
    }

    @Test
    void unbuiltGoalAreaGivesNoPlan() {
        CoarseGraph partial = graph(cx -> cx < 15);
        assertNull(CoarsePlanner.plan(partial, NEAR_START, RULES, START, GOAL, WINDOW, 10_000));
    }

    @Test
    void startOutsideTheGraphGivesNoPlan() {
        CoarseGraph withoutStart = graph(cx -> cx > 0);
        assertNull(CoarsePlanner.plan(withoutStart, NEAR_START, RULES, START, GOAL, WINDOW, 10_000));
        WindowSearch.Start inTheAir = new WindowSearch.Start(START_X, 80, START_Z, START.surface());
        assertNull(CoarsePlanner.plan(FULL_GRAPH, NEAR_START, RULES, inTheAir, GOAL, WINDOW, 10_000));
    }

    @Test
    void evictedColumnInTheWaypointChunkGivesNoPlan() {
        // Representative columns stay known; the east edge column of chunk column 4 is evicted.
        Terrain evicted = (x, z) -> x == 79 ? null : NEAR_START.surfaces(x, z);
        assertNull(CoarsePlanner.plan(FULL_GRAPH, evicted, RULES, START, GOAL, WINDOW, 10_000));
    }

    @Test
    void evictedColumnInTheStartChunkGivesNoPlan() {
        Terrain evicted = (x, z) -> x == 0 && z == 16 ? null : NEAR_START.surfaces(x, z);
        assertNull(CoarsePlanner.plan(FULL_GRAPH, evicted, RULES, START, GOAL, WINDOW, 10_000));
    }

    @Test
    void goalNodesKeepOnlyOverlappingChunksAndHeights() {
        ComponentId low = new ComponentId(-2, -3, -24, 64, -40);
        ComponentId high = new ComponentId(-2, -3, -25, 105, -41);
        ComponentId beside = new ComponentId(-1, -3, -8, 64, -40);
        CoarseGraph graph = CoarseGraph.of(Map.of(
                ChunkKeys.key(-2, -3), List.of(new CoarseNode(low, 60, 66, List.of()),
                        new CoarseNode(high, 100, 110, List.of())),
                ChunkKeys.key(-1, -3), List.of(new CoarseNode(beside, 60, 66, List.of()))));
        Goal goal = new BoxGoal(-30, 60, -40, -20, 70, -35);
        assertEquals(Set.of(low), CoarsePlanner.goalNodes(graph, goal));
    }

    @Test
    void goalNodesPreferThoseWhoseRepresentativeIsInTheGoal() {
        // Both components of the chunk overlap the goal's box and heights; only one's representative is inside.
        ComponentId inside = new ComponentId(0, 0, 4, 64, 4);
        ComponentId outside = new ComponentId(0, 0, 12, 64, 12);
        CoarseGraph graph = CoarseGraph.of(Map.of(ChunkKeys.key(0, 0), List.of(
                new CoarseNode(inside, 60, 66, List.of()), new CoarseNode(outside, 60, 66, List.of()))));
        assertEquals(Set.of(inside), CoarsePlanner.goalNodes(graph, new BoxGoal(2, 60, 2, 6, 70, 6)));
        // When no representative is inside, every overlapping node is a goal node.
        assertEquals(Set.of(inside, outside), CoarsePlanner.goalNodes(graph, new BoxGoal(7, 60, 7, 9, 70, 9)));
    }

    @Test
    void goalNodesOfAHugeGoalLookOnlyAtTheGraphsChunks() {
        Goal everywhere = new BoxGoal(-30_000_000, -64, -30_000_000, 30_000_000, 320, 30_000_000);
        Set<ComponentId> nodes = assertTimeoutPreemptively(Duration.ofSeconds(2),
                () -> CoarsePlanner.goalNodes(FULL_GRAPH, everywhere));
        assertEquals(FULL_GRAPH.size(), nodes.size());
        assertTrue(CoarsePlanner.goalNodes(new CoarseGraph(), everywhere).isEmpty());
    }

    @Test
    void budgetGivesNoPlan() {
        assertNull(CoarsePlanner.plan(FULL_GRAPH, NEAR_START, RULES, START, GOAL, WINDOW, 1));
    }
}
