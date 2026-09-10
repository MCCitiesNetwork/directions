package com.minecraftcitiesnetwork.directions.pathfinding;

import com.minecraftcitiesnetwork.directions.pathfinding.grid.ArrayBlockGrid;
import com.minecraftcitiesnetwork.directions.pathfinding.grid.AsciiGrid;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StandingPositionsTest {
    private static final WalkerProfile P = WalkerProfile.DEFAULT;

    @Test
    void keepsAStandingCell() {
        AsciiGrid f = AsciiGrid.parse("""
                y=64
                #####
                #S..#
                #####
                """);
        assertEquals(Optional.of(new Pos(1, 64, 1)), StandingPositions.snap(f.grid(), P, new Pos(1, 64, 1)));
    }

    @Test
    void prefersVerticalAdjustmentOverSideways() {
        // Feet inside a single raised block (a slab, say): on top of it beats the cell beside it.
        ArrayBlockGrid grid = new ArrayBlockGrid(0, 64, 0, 5, 1, 3);
        grid.set(2, 64, 1, BlockFlags.SOLID);
        assertEquals(Optional.of(new Pos(2, 65, 1)), StandingPositions.snap(grid, P, new Pos(2, 64, 1)));
    }

    @Test
    void fallsFromMidAirToTheGround() {
        ArrayBlockGrid grid = new ArrayBlockGrid(0, 64, 0, 5, 10, 5);
        assertEquals(Optional.of(new Pos(2, 64, 2)), StandingPositions.snap(grid, P, new Pos(2, 68, 2)));
    }

    @Test
    void giveUpBeyondRadius() {
        ArrayBlockGrid grid = new ArrayBlockGrid(0, 64, 0, 40, 1, 40);
        for (int x = 0; x < 40; x++) {
            for (int z = 0; z < 40; z++) {
                grid.set(x, 64, z, BlockFlags.SOLID);
            }
        }
        // Everything at y=64 is solid; the only standing cells are at y=65 on top, which is within radius 1.
        assertEquals(Optional.of(new Pos(20, 65, 20)), StandingPositions.snap(grid, P, new Pos(20, 64, 20)));
        WalkerProfile zero = new WalkerProfile(2, true, 3, true, 1.5, 2.5, 2.0, 0);
        assertTrue(StandingPositions.snap(grid, zero, new Pos(20, 64, 20)).isEmpty());
    }

    @Test
    void prefersPlatformBesideOverRoofAbove() {
        // A metro-like station: two platforms at y=74 separated by a one-wide wall, roof at y=76.
        AsciiGrid f = AsciiGrid.parse("""
                y=74
                #########
                #...#...#
                #########
                y=75
                #########
                #...#...#
                #########
                y=76
                #########
                #########
                #########
                y=77
                .........
                .........
                .........
                """);
        // The region centre falls on the dividing wall at y=75.
        Pos snapped = StandingPositions.snap(f.grid(), P, new Pos(4, 75, 1)).orElseThrow();
        assertEquals(74, snapped.y(), "should snap onto a platform, not the roof: " + snapped);
        assertTrue(snapped.x() == 3 || snapped.x() == 5);
    }

    @Test
    void surfaceFindsTopOfColumn() {
        ArrayBlockGrid grid = new ArrayBlockGrid(0, 60, 0, 3, 40, 3);
        for (int y = 60; y < 72; y++) {
            grid.set(1, y, 1, BlockFlags.SOLID);
        }
        assertEquals(Optional.of(new Pos(1, 72, 1)), StandingPositions.surface(grid, P, 1, 1, 99));
    }

    @Test
    void swimmingCountsAsStanding() {
        AsciiGrid f = AsciiGrid.parse("""
                y=64
                #####
                #~~~#
                #####
                """);
        assertEquals(Optional.of(new Pos(2, 64, 1)), StandingPositions.snap(f.grid(), P, new Pos(2, 64, 1)));
    }
}
