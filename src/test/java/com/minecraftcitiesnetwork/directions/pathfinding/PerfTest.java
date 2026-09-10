package com.minecraftcitiesnetwork.directions.pathfinding;

import com.minecraftcitiesnetwork.directions.pathfinding.grid.ArrayBlockGrid;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * L3: latency on a synthetic city (blocks of buildings on a street grid) and determinism under
 * concurrency. Thresholds are loose enough for CI hardware; tighten locally with
 * {@code -Ddirections.perf.p95ms=...}.
 */
@Tag("perf")
class PerfTest {
    private static final WalkerProfile P = WalkerProfile.DEFAULT;

    /** 400x400 city: 12-wide blocks of solid buildings separated by 4-wide streets, plus random gaps. */
    static ArrayBlockGrid city(long seed, int size) {
        Random random = new Random(seed);
        ArrayBlockGrid grid = new ArrayBlockGrid(0, 64, 0, size, 4, size);
        for (int x = 0; x < size; x++) {
            for (int z = 0; z < size; z++) {
                boolean street = (x % 16) < 4 || (z % 16) < 4;
                boolean building = !street && random.nextDouble() > 0.02;
                if (building) {
                    for (int y = 64; y < 68; y++) {
                        grid.set(x, y, z, BlockFlags.SOLID);
                    }
                }
            }
        }
        return grid;
    }

    static Pos street(Random random, int size) {
        while (true) {
            int x = random.nextInt(size);
            int z = random.nextInt(size);
            if ((x % 16) < 4 || (z % 16) < 4) {
                return new Pos(x, 64, z);
            }
        }
    }

    @Test
    void warmSearchesMeetTheLatencyBudget() {
        double p95Limit = Double.parseDouble(System.getProperty("directions.perf.p95ms", "40"));
        ArrayBlockGrid grid = city(1, 400);
        WalkPathfinder finder = new WalkPathfinder(P, 400_000);
        Random random = new Random(2);
        List<Double> ms = new ArrayList<>();
        int found = 0;
        for (int i = 0; i < 60; i++) {
            Pos s = street(random, 400);
            Pos t;
            do {
                t = street(random, 400);
            } while (s.distance(t) < 120 || s.distance(t) > 180);
            WalkResult r = finder.find(grid, new WalkRequest(s, t, 0, Duration.ofSeconds(5)));
            if (i >= 10) {
                ms.add(r.millis());
            }
            if (r.found()) {
                found++;
            }
        }
        double[] sorted = ms.stream().mapToDouble(Double::doubleValue).sorted().toArray();
        double p50 = sorted[sorted.length / 2];
        double p95 = sorted[(int) Math.floor(0.95 * (sorted.length - 1))];
        System.out.printf("perf: 150-block city routes  p50 %.1f ms  p95 %.1f ms  found %d/60%n", p50, p95, found);
        assertTrue(found >= 55, "most street pairs must be reachable, found " + found);
        assertTrue(p95 <= p95Limit, String.format("p95 %.1f ms exceeds %.1f ms", p95, p95Limit));
    }

    @Test
    void concurrentSearchesAreDeterministic() throws Exception {
        ArrayBlockGrid grid = city(3, 300);
        WalkPathfinder finder = new WalkPathfinder(P, 400_000);
        Random random = new Random(4);
        List<WalkRequest> requests = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            requests.add(new WalkRequest(street(random, 300), street(random, 300), 0, Duration.ofSeconds(10)));
        }
        List<WalkResult> sequential = new ArrayList<>();
        for (WalkRequest r : requests) {
            sequential.add(finder.find(grid, r));
        }
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<WalkResult>> futures = new ArrayList<>();
            for (WalkRequest r : requests) {
                futures.add(pool.submit(() -> finder.find(grid, r)));
            }
            for (int i = 0; i < requests.size(); i++) {
                WalkResult a = sequential.get(i);
                WalkResult b = futures.get(i).get();
                assertEquals(a.status(), b.status(), "status differs for request " + i);
                if (a.found()) {
                    assertEquals(a.path().totalDistance(), b.path().totalDistance(), 1e-9, "distance differs for request " + i);
                    assertTrue(Arrays.equals(a.path().nodes().toArray(), b.path().nodes().toArray()), "nodes differ for request " + i);
                }
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
