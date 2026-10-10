package com.minecraftcitiesnetwork.directions.walk;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ColumnDecoderTest {

    private static int[] surfaces(int minY, String bottomUp) {
        return BlockWorld.column(minY, bottomUp).terrain().surfaces(0, 0);
    }

    private static int[] ys(int[] surfaces) {
        int[] out = new int[surfaces.length];
        for (int i = 0; i < surfaces.length; i++) {
            out[i] = Surfaces.y(surfaces[i]);
        }
        return out;
    }

    @Test
    void packRoundTripsNegativeHeightsAndCapsHeadroom() {
        int s = Surfaces.pack(-63, Footing.CLIMB, 12, true, true);
        assertEquals(-63, Surfaces.y(s));
        assertEquals(Footing.CLIMB, Surfaces.footing(s));
        assertEquals(Surfaces.MAX_HEADROOM, Surfaces.headroom(s));
        assertTrue(Surfaces.door(s));
        assertTrue(Surfaces.halfSupport(s));
    }

    @Test
    void groundOnAFullBlockWithOpenSky() {
        int[] s = surfaces(0, "S...");
        assertArrayEquals(new int[]{1}, ys(s));
        assertEquals(Footing.GROUND, Surfaces.footing(s[0]));
        assertEquals(Surfaces.MAX_HEADROOM, Surfaces.headroom(s[0]));
        assertFalse(Surfaces.door(s[0]));
        assertFalse(Surfaces.halfSupport(s[0]));
    }

    @Test
    void lowCeilingLimitsHeadroomAndTheRoofIsItsOwnSurface() {
        int[] s = surfaces(0, "S..S");
        assertArrayEquals(new int[]{1, 4}, ys(s));
        assertEquals(2, Surfaces.headroom(s[0]));
    }

    @Test
    void slabIsAHalfSupport() {
        int[] s = surfaces(0, "H..");
        assertArrayEquals(new int[]{1}, ys(s));
        assertTrue(Surfaces.halfSupport(s[0]));
    }

    @Test
    void doorwayIsMarked() {
        int[] s = surfaces(0, "SD.");
        assertArrayEquals(new int[]{1}, ys(s));
        assertTrue(Surfaces.door(s[0]));
    }

    @Test
    void fencesCanNeitherBeStoodOnNorWalkedThrough() {
        assertEquals(0, surfaces(0, "F...").length);
        assertEquals(0, surfaces(0, "SF..").length);
    }

    @Test
    void hazardsCanNeitherBeStoodOnNorEntered() {
        assertEquals(0, surfaces(0, "X...").length);
        assertEquals(0, surfaces(0, "SX..").length);
    }

    @Test
    void onlyTheTopOfAWaterColumnIsASurface() {
        int[] s = surfaces(0, "SWW.");
        assertArrayEquals(new int[]{2}, ys(s));
        assertEquals(Footing.WATER, Surfaces.footing(s[0]));
        assertTrue(Surfaces.headroom(s[0]) >= 3);
    }

    @Test
    void ladderIsGroundAtItsFootThenClimbable() {
        int[] s = surfaces(0, "SLLL..");
        assertArrayEquals(new int[]{1, 2, 3}, ys(s));
        assertEquals(Footing.GROUND, Surfaces.footing(s[0]));
        assertEquals(Footing.CLIMB, Surfaces.footing(s[1]));
        assertEquals(Footing.CLIMB, Surfaces.footing(s[2]));
    }

    @Test
    void basementAndRooftopInTheSameColumn() {
        int[] s = surfaces(0, "S..SSSSS..");
        assertArrayEquals(new int[]{1, 8}, ys(s));
    }

    @Test
    void worksBelowZero() {
        int[] s = surfaces(-64, "S..");
        assertArrayEquals(new int[]{-63}, ys(s));
    }

    @Test
    void columnsOfUndecodedChunksAreUnknown() {
        Terrain t = BlockWorld.column(0, "S..").terrain();
        assertEquals(null, t.surfaces(16, 0));
        assertEquals(0, t.surfaces(5, 5).length);
    }
}
