package com.minecraftcitiesnetwork.directions.pathfinding;

import com.minecraftcitiesnetwork.directions.pathfinding.grid.ArrayBlockGrid;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Comparator;
import java.util.PriorityQueue;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Compares the engine against an independent shortest-path implementation on random flat mazes.
 * Flat terrain keeps the reference simple: eight moves with Euclidean costs and the same
 * corner-cutting rule, so the optimum is exactly computable and the engine must match it.
 */
class ReferenceShortestPathTest {
    private static final WalkerProfile P = WalkerProfile.DEFAULT;
    private static final int[][] MOVES = {
            {1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}
    };

    @Test
    void fourConnectedMatchesBreadthFirst() {
        WalkPathfinder finder = new WalkPathfinder(P, 500_000, true);
        Random random = new Random(7);
        for (int seed = 0; seed < 60; seed++) {
            ArrayBlockGrid grid = maze(random, 24, 24, 0.30);
            Pos s = openCell(grid, random);
            Pos t = openCell(grid, random);
            int expected = bfs4(grid, s, t);
            WalkResult result = finder.find(grid, new WalkRequest(s, t, 0, Duration.ofSeconds(5)));
            if (expected < 0) {
                assertFalse(result.found(), "seed " + seed + ": reference says unreachable");
            } else {
                WalkPath path = PathAssertions.assertFound(result, grid, P);
                assertEquals(expected, path.size() - 1, "seed " + seed + ": step count differs from BFS");
            }
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {200})
    @Tag("fuzz")
    void eightConnectedMatchesDijkstra(int seeds) {
        runDijkstraComparison(seeds, 11);
    }

    @Test
    @Tag("fuzz-long")
    void eightConnectedMatchesDijkstraLong() {
        runDijkstraComparison(5000, 99);
    }

    private static void runDijkstraComparison(int seeds, long baseSeed) {
        WalkPathfinder finder = new WalkPathfinder(P, 1_000_000);
        Random random = new Random(baseSeed);
        for (int seed = 0; seed < seeds; seed++) {
            int size = 12 + random.nextInt(28);
            double density = 0.15 + random.nextDouble() * 0.30;
            ArrayBlockGrid grid = maze(random, size, size, density);
            Pos s = openCell(grid, random);
            Pos t = openCell(grid, random);
            double expected = dijkstra8(grid, s, t);
            WalkResult result = finder.find(grid, new WalkRequest(s, t, 0, Duration.ofSeconds(5)));
            if (expected < 0) {
                assertFalse(result.found(), "seed " + seed + ": reference says unreachable but engine found " + result);
            } else {
                WalkPath path = PathAssertions.assertFound(result, grid, P);
                assertEquals(expected, path.totalDistance(), 1e-6, "seed " + seed + ": engine path is not optimal");
            }
        }
    }

    /** A flat maze: one walkable layer at y=64 over solid ground, with three-block-tall walls. */
    static ArrayBlockGrid maze(Random random, int w, int d, double wallDensity) {
        ArrayBlockGrid grid = new ArrayBlockGrid(0, 64, 0, w, 3, d);
        for (int x = 0; x < w; x++) {
            for (int z = 0; z < d; z++) {
                boolean border = x == 0 || z == 0 || x == w - 1 || z == d - 1;
                if (border || random.nextDouble() < wallDensity) {
                    for (int y = 64; y < 67; y++) {
                        grid.set(x, y, z, BlockFlags.SOLID);
                    }
                }
            }
        }
        return grid;
    }

    static Pos openCell(ArrayBlockGrid grid, Random random) {
        while (true) {
            int x = 1 + random.nextInt(grid.sizeX() - 2);
            int z = 1 + random.nextInt(grid.sizeZ() - 2);
            if (grid.flags(x, 64, z) == BlockFlags.PASSABLE) {
                return new Pos(x, 64, z);
            }
        }
    }

    static int bfs4(ArrayBlockGrid grid, Pos s, Pos t) {
        int w = grid.sizeX();
        int d = grid.sizeZ();
        int[] dist = new int[w * d];
        Arrays.fill(dist, -1);
        ArrayDeque<int[]> queue = new ArrayDeque<>();
        dist[s.z() * w + s.x()] = 0;
        queue.add(new int[]{s.x(), s.z()});
        while (!queue.isEmpty()) {
            int[] c = queue.poll();
            if (c[0] == t.x() && c[1] == t.z()) {
                return dist[c[1] * w + c[0]];
            }
            for (int m = 0; m < 4; m++) {
                int nx = c[0] + MOVES[m][0];
                int nz = c[1] + MOVES[m][1];
                if (nx < 0 || nz < 0 || nx >= w || nz >= d || grid.flags(nx, 64, nz) != BlockFlags.PASSABLE) {
                    continue;
                }
                if (dist[nz * w + nx] < 0) {
                    dist[nz * w + nx] = dist[c[1] * w + c[0]] + 1;
                    queue.add(new int[]{nx, nz});
                }
            }
        }
        return -1;
    }

    static double dijkstra8(ArrayBlockGrid grid, Pos s, Pos t) {
        int w = grid.sizeX();
        int d = grid.sizeZ();
        double[] dist = new double[w * d];
        Arrays.fill(dist, Double.POSITIVE_INFINITY);
        PriorityQueue<double[]> pq = new PriorityQueue<>(Comparator.comparingDouble(a -> a[0]));
        dist[s.z() * w + s.x()] = 0;
        pq.add(new double[]{0, s.x(), s.z()});
        while (!pq.isEmpty()) {
            double[] c = pq.poll();
            int x = (int) c[1];
            int z = (int) c[2];
            if (c[0] > dist[z * w + x]) {
                continue;
            }
            if (x == t.x() && z == t.z()) {
                return c[0];
            }
            for (int[] m : MOVES) {
                int nx = x + m[0];
                int nz = z + m[1];
                if (nx < 0 || nz < 0 || nx >= w || nz >= d || grid.flags(nx, 64, nz) != BlockFlags.PASSABLE) {
                    continue;
                }
                if (m[0] != 0 && m[1] != 0) {
                    boolean a = grid.flags(x + m[0], 64, z) == BlockFlags.PASSABLE;
                    boolean b = grid.flags(x, 64, z + m[1]) == BlockFlags.PASSABLE;
                    if (!a && !b) {
                        continue;
                    }
                }
                double nd = c[0] + Math.sqrt(m[0] * m[0] + m[1] * m[1]);
                if (nd < dist[nz * w + nx]) {
                    dist[nz * w + nx] = nd;
                    pq.add(new double[]{nd, nx, nz});
                }
            }
        }
        return -1;
    }

    @Test
    void engineNeverProducesInvalidPathsOnRoughTerrain() {
        // Height-varying random terrain: checks 3D rules through the invariants only.
        WalkPathfinder finder = new WalkPathfinder(P, 300_000);
        Random random = new Random(3);
        int found = 0;
        for (int seed = 0; seed < 40; seed++) {
            ArrayBlockGrid grid = heightField(random, 30, 30);
            Pos s = StandingPositions.surface(grid, P, 2 + random.nextInt(26), 2 + random.nextInt(26), 90).orElseThrow();
            Pos t = StandingPositions.surface(grid, P, 2 + random.nextInt(26), 2 + random.nextInt(26), 90).orElseThrow();
            WalkResult result = finder.find(grid, new WalkRequest(s, t, 0, Duration.ofSeconds(5)));
            if (result.found()) {
                PathAssertions.assertValid(result.path(), grid, P);
                found++;
            }
        }
        assertTrue(found > 10, "expected most height fields to be traversable, found " + found);
    }

    static ArrayBlockGrid heightField(Random random, int w, int d) {
        ArrayBlockGrid grid = new ArrayBlockGrid(0, 60, 0, w, 30, d);
        int[][] h = new int[w][d];
        for (int x = 0; x < w; x++) {
            for (int z = 0; z < d; z++) {
                int base = 66 + (int) (3 * Math.sin(x / 4.0) + 3 * Math.cos(z / 5.0));
                h[x][z] = Math.max(61, base + (random.nextDouble() < 0.1 ? random.nextInt(3) : 0));
            }
        }
        for (int x = 0; x < w; x++) {
            for (int z = 0; z < d; z++) {
                for (int y = 60; y < 90; y++) {
                    grid.set(x, y, z, y < h[x][z] ? BlockFlags.SOLID : BlockFlags.PASSABLE);
                }
                if (random.nextDouble() < 0.03) {
                    grid.set(x, h[x][z], z, BlockFlags.LIQUID);
                }
            }
        }
        return grid;
    }
}
