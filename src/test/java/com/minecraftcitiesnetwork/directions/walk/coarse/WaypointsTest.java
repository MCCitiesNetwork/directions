package com.minecraftcitiesnetwork.directions.walk.coarse;

import com.minecraftcitiesnetwork.directions.walk.Terrain;
import com.minecraftcitiesnetwork.directions.walk.Window;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class WaypointsTest {
    /** Chunks 0..3 lie fully inside; chunk 4 (x 64..79) is cut at x 72. */
    private static final Window WINDOW = new Window(-8, -8, 72, 24);
    private static final Terrain CAPTURED = (x, z) -> new int[0];

    private static ComponentId chunk(int chunkX) {
        return new ComponentId(chunkX, 0, chunkX * 16 + 8, 65, 8);
    }

    private static List<ComponentId> path() {
        List<ComponentId> path = new ArrayList<>();
        for (int cx = 0; cx <= 6; cx++) {
            path.add(chunk(cx));
        }
        return path;
    }

    @Test
    void picksTheLastFullyInsideCapturedChunk() {
        assertEquals(chunk(3), Waypoints.pick(path(), WINDOW, CAPTURED));
    }

    @Test
    void skipsUncapturedChunks() {
        Terrain chunkThreeUnknown = (x, z) -> (x >> 4) == 3 ? null : new int[0];
        assertEquals(chunk(2), Waypoints.pick(path(), WINDOW, chunkThreeUnknown));
    }

    @Test
    void stopsWhereThePathFirstLeavesTheWindow() {
        // Out across the window's south edge (z 24) at chunk 2 and back in at chunk 3, as a path to a
        // bridge just outside the window does: the waypoint must stay on the near side.
        List<ComponentId> path = new ArrayList<>(List.of(chunk(0), chunk(1), chunk(2)));
        path.add(new ComponentId(2, 2, 40, 65, 40));
        path.add(new ComponentId(3, 2, 56, 65, 40));
        path.add(chunk(3));
        path.add(chunk(4));
        assertEquals(chunk(2), Waypoints.pick(path, WINDOW, CAPTURED));
    }

    @Test
    void stopsAtTheFirstUncapturedChunkEvenIfLaterOnesAreCaptured() {
        Terrain chunkTwoUnknown = (x, z) -> (x >> 4) == 2 ? null : new int[0];
        assertEquals(chunk(1), Waypoints.pick(path(), WINDOW, chunkTwoUnknown));
    }

    @Test
    void theStartMayStraddleTheWindowEdge() {
        List<ComponentId> path = List.of(new ComponentId(-1, 0, -8, 65, 8), chunk(0), chunk(1));
        assertEquals(chunk(1), Waypoints.pick(path, WINDOW, CAPTURED));
    }

    @Test
    void nullWhenOnlyTheStartQualifies() {
        assertNull(Waypoints.pick(path(), new Window(-8, -8, 24, 24), CAPTURED));
    }
}
