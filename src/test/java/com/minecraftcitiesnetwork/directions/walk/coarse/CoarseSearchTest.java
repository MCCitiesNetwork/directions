package com.minecraftcitiesnetwork.directions.walk.coarse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import com.minecraftcitiesnetwork.directions.walk.PointGoal;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CoarseSearchTest {
    private static ComponentId id(int cx, int cz) {
        return new ComponentId(cx, cz, cx * 16 + 8, 64, cz * 16 + 8);
    }

    private static CoarseGraph line(int n) {
        return grid(n, 1, Set.of(), Set.of());
    }

    /** A w by h block of chunks, minus the removed chunks, with the cut edges (given as "ax,az-bx,bz") dropped. */
    private static CoarseGraph grid(int w, int h, Set<String> removed, Set<String> cut) {
        CoarseGraph graph = new CoarseGraph();
        for (int pass = 0; pass < 2; pass++) {
            for (int cx = 0; cx < w; cx++) {
                for (int cz = 0; cz < h; cz++) {
                    if (removed.contains(cx + "," + cz)) {
                        continue;
                    }
                    List<ComponentId> edges = new ArrayList<>();
                    if (pass == 1) {
                        int[][] steps = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
                        for (int[] s : steps) {
                            int nx = cx + s[0];
                            int nz = cz + s[1];
                            boolean inside = nx >= 0 && nz >= 0 && nx < w && nz < h;
                            if (inside && !removed.contains(nx + "," + nz)
                                    && !cut.contains(cx + "," + cz + "-" + nx + "," + nz)
                                    && !cut.contains(nx + "," + nz + "-" + cx + "," + cz)) {
                                edges.add(id(nx, nz));
                            }
                        }
                    }
                    graph.replace(cx, cz, List.of(new CoarseNode(id(cx, cz), 60, 70, edges)),
                            EnumSet.allOf(Side.class));
                }
            }
        }
        return graph;
    }

    private static PointGoal goalAt(ComponentId target) {
        return new PointGoal(target.x(), target.y(), target.z(), 0.5, false);
    }

    private static CoarseSearch.Result run(CoarseGraph graph, ComponentId from, ComponentId to, int budget) {
        return CoarseSearch.search(graph, from, Set.of(to), goalAt(to), budget);
    }

    @Test
    void findsTheShortestPathOnAGrid() {
        Set<String> removed = new HashSet<>();
        for (int z = 1; z <= 4; z++) {
            removed.add("2," + z);
        }
        CoarseGraph graph = grid(5, 5, removed, Set.of());
        CoarseSearch.Result result = run(graph, id(0, 0), id(4, 4), 1000);
        CoarseSearch.Found found = assertInstanceOf(CoarseSearch.Found.class, result);
        assertEquals(9, found.path().size());
        assertEquals(id(0, 0), found.path().get(0));
        assertEquals(id(4, 4), found.path().get(8));
        assertEquals(true, found.path().contains(id(2, 0)));
        assertEquals(8 * 16.0, found.length(), 1e-9);
    }

    @Test
    void startInGoalsIsFoundAtOnce() {
        CoarseSearch.Found found = assertInstanceOf(CoarseSearch.Found.class,
                run(line(3), id(1, 0), id(1, 0), 1000));
        assertEquals(List.of(id(1, 0)), found.path());
        assertEquals(0.0, found.length());
    }

    @Test
    void noPathAcrossAGap() {
        CoarseGraph graph = grid(4, 1, Set.of(), Set.of("1,0-2,0"));
        assertEquals(CoarseSearch.Failure.NO_PATH, run(graph, id(0, 0), id(3, 0), 1000));
    }

    @Test
    void budgetStopsTheSearch() {
        CoarseGraph graph = grid(10, 10, Set.of(), Set.of());
        assertEquals(CoarseSearch.Failure.BUDGET, run(graph, id(0, 0), id(9, 9), 3));
    }

    @Test
    void unknownStartIsNoPath() {
        assertEquals(CoarseSearch.Failure.NO_PATH, run(line(3), id(50, 50), id(2, 0), 1000));
    }

    @Test
    void emptyGoalsIsNoPath() {
        assertEquals(CoarseSearch.Failure.NO_PATH,
                CoarseSearch.search(line(3), id(0, 0), Set.of(), goalAt(id(2, 0)), 1000));
    }

    @Test
    void resultIsDeterministic() {
        CoarseSearch.Result first = null;
        for (int i = 0; i < 10; i++) {
            CoarseSearch.Result result = run(grid(3, 3, Set.of(), Set.of()), id(0, 0), id(2, 2), 1000);
            assertInstanceOf(CoarseSearch.Found.class, result);
            if (first == null) {
                first = result;
            }
            assertEquals(first, result);
        }
    }
}
