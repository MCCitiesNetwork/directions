package com.minecraftcitiesnetwork.directions.pathfinding;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WalkPathTest {
    private final WalkPath path = new WalkPath(List.of(
            new Pos(0, 64, 0), new Pos(1, 64, 0), new Pos(2, 64, 0), new Pos(3, 64, 1),
            new Pos(4, 64, 2), new Pos(5, 64, 2), new Pos(6, 64, 2), new Pos(7, 64, 2)
    ));

    @Test
    void cumulativeDistanceUsesEuclideanSteps() {
        assertEquals(2 + 2 * Math.sqrt(2) + 3, path.totalDistance(), 1e-9);
    }

    @Test
    void nearestIndexFindsClosestNode() {
        assertEquals(0, path.nearestIndex(0.5, 64, 0.5, 0));
        assertEquals(4, path.nearestIndex(4.4, 64, 2.6, 0));
        assertEquals(7, path.nearestIndex(9.0, 64, 2.5, 3));
    }

    @Test
    void nearestIndexEscapesAStaleHint() {
        // Hint at the start but the player is at the far end: the window misses, the full scan finds it.
        WalkPath longPath = straight(200);
        assertEquals(190, longPath.nearestIndex(190.5, 64, 0.5, 0));
    }

    @Test
    void remainingDistanceCountsRestOfPathPlusOffset() {
        int idx = path.nearestIndex(2.5, 64, 0.5, 0);
        assertEquals(2, idx);
        assertEquals(2 * Math.sqrt(2) + 3, path.remainingFrom(idx, 2.5, 0.5), 1e-9);
        assertEquals(2 * Math.sqrt(2) + 3 + 0.5, path.remainingFrom(idx, 2.0, 0.5), 1e-9);
    }

    @Test
    void lookAheadWalksForwardByDistanceAndClampsAtEnd() {
        assertEquals(new Pos(3, 64, 1), path.lookAhead(0, 3.0));
        assertEquals(new Pos(7, 64, 2), path.lookAhead(6, 50.0));
        assertEquals(new Pos(7, 64, 2), path.lookAhead(7, 1.0));
    }

    @Test
    void singleNodePathHasZeroDistance() {
        WalkPath single = new WalkPath(List.of(new Pos(5, 70, 5)));
        assertEquals(0.0, single.totalDistance());
        assertEquals(new Pos(5, 70, 5), single.lookAhead(0, 6));
        assertEquals(0, single.nearestIndex(5.5, 70, 5.5, 0));
    }

    @Test
    void emptyPathIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new WalkPath(List.of()));
    }

    private static WalkPath straight(int n) {
        Pos[] nodes = new Pos[n];
        for (int i = 0; i < n; i++) {
            nodes[i] = new Pos(i, 64, 0);
        }
        return new WalkPath(List.of(nodes));
    }
}
