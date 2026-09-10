package com.minecraftcitiesnetwork.directions.pathfinding;

import com.minecraftcitiesnetwork.directions.pathfinding.grid.ArrayBlockGrid;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SearchBudgetTest {

    /** A grid whose reads are slow, like a cold chunk cache. */
    private static final class SlowGrid implements BlockGrid {
        private final BlockGrid inner;

        SlowGrid(BlockGrid inner) {
            this.inner = inner;
        }

        @Override
        public byte flags(int x, int y, int z) {
            long end = System.nanoTime() + 20_000;
            while (System.nanoTime() < end) {
                Thread.onSpinWait();
            }
            return inner.flags(x, y, z);
        }

        @Override
        public int minY() {
            return inner.minY();
        }

        @Override
        public int maxY() {
            return inner.maxY();
        }
    }

    @Test
    void searchStopsAtTheBudgetAndReportsTimeout() {
        ArrayBlockGrid open = new ArrayBlockGrid(0, 64, 0, 300, 1, 300);
        WalkPathfinder finder = new WalkPathfinder(WalkerProfile.DEFAULT, 10_000_000);
        WalkRequest request = new WalkRequest(new Pos(1, 64, 1), new Pos(298, 64, 298), 0, Duration.ofMillis(40));

        long t0 = System.nanoTime();
        WalkResult result = finder.find(new SlowGrid(open), request);
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000;

        assertEquals(WalkResult.Status.TIMEOUT, result.status(), result.toString());
        assertTrue(elapsedMs < 400, "search overran the budget badly: " + elapsedMs + " ms");
    }

    @Test
    void generousBudgetFindsThePath() {
        ArrayBlockGrid open = new ArrayBlockGrid(0, 64, 0, 60, 1, 60);
        WalkPathfinder finder = new WalkPathfinder(WalkerProfile.DEFAULT, 1_000_000);
        WalkResult result = finder.find(open, new WalkRequest(new Pos(1, 64, 1), new Pos(58, 64, 58), 0, Duration.ofSeconds(5)));
        assertTrue(result.found(), result.toString());
    }

    @Test
    void iterationCapReportsExhausted() {
        ArrayBlockGrid open = new ArrayBlockGrid(0, 64, 0, 200, 1, 200);
        WalkPathfinder finder = new WalkPathfinder(WalkerProfile.DEFAULT, 50);
        WalkResult result = finder.find(open, new WalkRequest(new Pos(1, 64, 1), new Pos(198, 64, 198), 0, Duration.ofSeconds(5)));
        assertEquals(WalkResult.Status.EXHAUSTED, result.status(), result.toString());
    }
}
