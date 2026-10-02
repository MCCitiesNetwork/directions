package com.minecraftcitiesnetwork.directions.walk;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RouteTest {
    private static final double EPS = 1e-9;

    private static Route line(int fromX, int toX) {
        List<Cell> cells = new ArrayList<>();
        for (int x = fromX; x <= toX; x++) {
            cells.add(new Cell(x, 65, 0));
        }
        return Route.of(cells, true, 0);
    }

    @Test
    void cellKeysAreDistinctForNeighboursAndNegatives() {
        assertTrue(Cell.key(0, 65, 0) != Cell.key(1, 65, 0));
        assertTrue(Cell.key(-1, 65, 0) != Cell.key(1, 65, 0));
        assertTrue(Cell.key(0, -64, 0) != Cell.key(0, 64, 0));
        assertTrue(Cell.key(0, 65, -1) != Cell.key(0, 65, 1));
    }

    @Test
    void lengthAndRemaining() {
        Route r = line(0, 10);
        assertEquals(11, r.size());
        assertEquals(10.0, r.length(), EPS);
        assertEquals(new Cell(10, 65, 0), r.end());
        assertEquals(6.0, r.remainingFrom(4, 4.5, 65, 0.5), EPS);
    }

    @Test
    void diagonalStepsCountTheirTrueLength() {
        Route r = Route.of(List.of(new Cell(0, 65, 0), new Cell(1, 65, 1), new Cell(2, 66, 1)), false, 7);
        assertEquals(Math.sqrt(2) + Math.sqrt(2), r.length(), EPS);
        assertFalse(r.complete());
        assertEquals(7.0, r.endEstimate(), EPS);
    }

    @Test
    void nearestIndexSearchesForwardFromTheHint() {
        Route r = line(0, 100);
        assertEquals(42, r.nearestIndex(42.5, 65, 0.5, 40));
        assertEquals(80, r.nearestIndex(80.5, 65, 0.5, 2));
        assertEquals(0, r.nearestIndex(-5, 65, 0.5, 0));
    }

    @Test
    void nearestIndexPrefersTheNearbySectionOnALoop() {
        // Out along z = 0 and back along z = 2: near (5, 1) both legs are 1 block away.
        List<Cell> cells = new ArrayList<>();
        for (int x = 0; x <= 10; x++) {
            cells.add(new Cell(x, 65, 0));
        }
        for (int x = 10; x >= 0; x--) {
            cells.add(new Cell(x, 65, 2));
        }
        Route r = Route.of(cells, true, 0);
        assertEquals(5, r.nearestIndex(5.5, 65, 1.5, 4));
    }

    @Test
    void indexAheadWalksTheGivenDistance() {
        Route r = line(0, 10);
        assertEquals(6, r.indexAhead(0, 6));
        assertEquals(10, r.indexAhead(8, 6));
    }

    @Test
    void firstMissingFindsABlockedCell() {
        BlockWorld world = new BlockWorld(64).fill(0, 64, 0, 10, 64, 0, BlockKind.SOLID);
        Route r = line(0, 10);
        assertEquals(-1, r.firstMissing(world.terrain(), 0, 40));
        world.set(5, 65, 0, BlockKind.SOLID).set(5, 66, 0, BlockKind.SOLID);
        assertEquals(5, r.firstMissing(world.terrain(), 2, 40));
        assertEquals(-1, r.firstMissing(world.terrain(), 6, 40));
        assertEquals(-1, r.firstMissing((x, z) -> null, 0, 40));
    }
}
