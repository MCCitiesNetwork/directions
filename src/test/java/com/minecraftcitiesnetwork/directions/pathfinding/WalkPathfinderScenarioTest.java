package com.minecraftcitiesnetwork.directions.pathfinding;

import com.minecraftcitiesnetwork.directions.pathfinding.grid.ArrayBlockGrid;
import com.minecraftcitiesnetwork.directions.pathfinding.grid.AsciiGrid;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Readable terrain scenarios. Each fixture draws only the cells the walker moves through. */
class WalkPathfinderScenarioTest {
    private static final WalkerProfile P = WalkerProfile.DEFAULT;
    private final WalkPathfinder finder = new WalkPathfinder(P, 200_000);

    private WalkResult run(AsciiGrid fixture) {
        return finder.find(fixture.grid(), WalkRequest.of(fixture.start(), fixture.target()));
    }

    @Test
    void straightCorridor() {
        AsciiGrid f = AsciiGrid.parse("""
                y=64
                #########
                #S.....T#
                #########
                """);
        WalkPath path = PathAssertions.assertFound(run(f), f.grid(), P);
        assertEquals(7, path.size());
        assertEquals(6.0, path.totalDistance(), 1e-9);
    }

    @Test
    void detoursAroundWall() {
        AsciiGrid f = AsciiGrid.parse("""
                y=64
                ###########
                #S...#....#
                #....#....#
                #....#..T.#
                #....#....#
                #.........#
                ###########
                """);
        WalkPath path = PathAssertions.assertFound(run(f), f.grid(), P);
        PathAssertions.assertAvoids(path, BlockFlags.SOLID, f.grid());
        assertTrue(path.totalDistance() > 8.0, "must go around the wall, not through it");
    }

    @Test
    void walksThroughDoor() {
        AsciiGrid f = AsciiGrid.parse("""
                y=64
                #########
                #S..#..T#
                #...D...#
                #...#...#
                #########
                """);
        WalkPath path = PathAssertions.assertFound(run(f), f.grid(), P);
        PathAssertions.assertVisits(path, BlockFlags.OPENABLE, f.grid());
    }

    @Test
    void swimsWhenNoBridgeButPrefersBridge() {
        // A canal three wide; row z=3 has solid floor under it (a bridge) while the others are water.
        AsciiGrid f = AsciiGrid.parse("""
                y=63
                ###########
                ####~~~####
                ####~~~####
                ###########
                ####~~~####
                ###########
                y=64
                ###########
                #S..~~~...#
                #...~~~...#
                #........T#
                #...~~~...#
                ###########
                """);
        WalkPath path = PathAssertions.assertFound(run(f), f.grid(), P);
        PathAssertions.assertAvoids(path, BlockFlags.LIQUID, f.grid());
    }

    @Test
    void swimsAcrossCanalWhenNecessary() {
        AsciiGrid f = AsciiGrid.parse("""
                y=64
                #########
                #S.~~~.T#
                #########
                """);
        WalkPath path = PathAssertions.assertFound(run(f), f.grid(), P);
        PathAssertions.assertVisits(path, BlockFlags.LIQUID, f.grid());
    }

    @Test
    void cannotSwimWhenProfileForbidsIt() {
        WalkerProfile noSwim = new WalkerProfile(2, true, 3, false, 1.5, 2.5, 2.0, 6);
        WalkPathfinder dry = new WalkPathfinder(noSwim, 50_000);
        AsciiGrid f = AsciiGrid.parse("""
                y=64
                #########
                #S.~~~.T#
                #########
                """);
        WalkResult result = dry.find(f.grid(), WalkRequest.of(f.start(), f.target()));
        assertFalse(result.found(), "no route without swimming: " + result);
    }

    @Test
    void avoidsHazards() {
        AsciiGrid f = AsciiGrid.parse("""
                y=64
                #########
                #S.XXX.T#
                #......X#
                #.XXXXXX#
                #.......#
                #########
                """);
        WalkPath path = PathAssertions.assertFound(run(f), f.grid(), P);
        PathAssertions.assertAvoids(path, BlockFlags.HAZARD, f.grid());
    }

    @Test
    void climbsOneBlockStep() {
        // A raised platform on the right: solid at y=64, so the walker stands on it at y=65.
        AsciiGrid f = AsciiGrid.parse("""
                y=64
                #########
                #S..#####
                #...#####
                #########
                y=65
                #########
                #......T#
                #.......#
                #########
                """);
        WalkPath path = PathAssertions.assertFound(run(f), f.grid(), P);
        assertEquals(64, path.start().y());
        assertEquals(65, path.end().y());
    }

    @Test
    void cannotClimbTwoBlocks() {
        AsciiGrid f = AsciiGrid.parse("""
                y=64
                #########
                #S..#####
                #########
                y=65
                #########
                #...#####
                #########
                y=66
                #########
                #......T#
                #########
                """);
        WalkResult result = run(f);
        assertFalse(result.found(), "a two-block cliff is not climbable: " + result);
    }

    @Test
    void dropsDownWithinLimit() {
        // Start on a platform two blocks above the target floor.
        AsciiGrid f = AsciiGrid.parse("""
                y=64
                #########
                ####....#
                ####...T#
                #########
                y=65
                #########
                ####....#
                ####....#
                #########
                y=66
                #########
                #S......#
                #.......#
                #########
                """);
        WalkPath path = PathAssertions.assertFound(run(f), f.grid(), P);
        assertEquals(66, path.start().y());
        assertEquals(64, path.end().y());
    }

    @Test
    void refusesDropBeyondLimit() {
        WalkerProfile timid = new WalkerProfile(2, true, 1, true, 1.5, 2.5, 2.0, 6);
        WalkPathfinder careful = new WalkPathfinder(timid, 50_000);
        AsciiGrid f = AsciiGrid.parse("""
                y=64
                #########
                ####....#
                ####...T#
                #########
                y=65
                #########
                ####....#
                ####....#
                #########
                y=66
                #########
                #S......#
                #.......#
                #########
                """);
        WalkResult result = careful.find(f.grid(), WalkRequest.of(f.start(), f.target()));
        assertFalse(result.found(), "a two-block drop exceeds maxDrop=1: " + result);
    }

    @Test
    void climbsLadder() {
        // Ladder column at x=4 rising from y=64 to y=66; target on a ledge at y=67.
        AsciiGrid f = AsciiGrid.parse("""
                y=64
                #########
                #S..L####
                #########
                y=65
                #########
                #...L####
                #########
                y=66
                #########
                #...L####
                #########
                y=67
                #########
                #......T#
                #########
                """);
        WalkPath path = PathAssertions.assertFound(run(f), f.grid(), P);
        PathAssertions.assertVisits(path, BlockFlags.CLIMBABLE, f.grid());
        assertEquals(67, path.end().y());
    }

    @Test
    void doesNotCutThroughDiagonalWall() {
        AsciiGrid f = AsciiGrid.parse("""
                y=64
                ######
                #S#..#
                #.#..#
                #..#.#
                #...T#
                ######
                """);
        // There is a real way round via the bottom row, so the path must use it.
        WalkPath path = PathAssertions.assertFound(run(f), f.grid(), P);
        for (int i = 1; i < path.size(); i++) {
            Pos a = path.node(i - 1);
            Pos b = path.node(i);
            int dx = b.x() - a.x();
            int dz = b.z() - a.z();
            if (dx != 0 && dz != 0) {
                boolean openA = BlockFlags.canOccupy(f.grid().flags(a.x() + dx, a.y(), a.z()), true);
                boolean openB = BlockFlags.canOccupy(f.grid().flags(a.x(), a.y(), a.z() + dz), true);
                assertTrue(openA || openB, "corner cut between " + a + " and " + b);
            }
        }
    }

    @Test
    void unreachableTargetReportsNone() {
        AsciiGrid f = AsciiGrid.parse("""
                y=64
                #########
                #S..#..T#
                #...#...#
                #########
                """);
        WalkResult result = run(f);
        assertFalse(result.found());
        assertTrue(result.status() == WalkResult.Status.NONE || result.status() == WalkResult.Status.EXHAUSTED, result.toString());
    }

    @Test
    void snapsStartDownToGroundAndTargetOutOfWall() {
        AsciiGrid f = AsciiGrid.parse("""
                y=64
                #########
                #....#..#
                #.......#
                #########
                """);
        // Start one block above the floor (mid-jump); target inside the wall block at (5,64,1).
        Pos floating = new Pos(1, 65, 1);
        Pos inWall = new Pos(5, 64, 1);
        WalkResult result = finder.find(f.grid(), WalkRequest.of(floating, inWall));
        WalkPath path = PathAssertions.assertFound(result, f.grid(), P);
        assertEquals(new Pos(1, 64, 1), path.start());
        assertTrue(f.grid().flags(path.end().x(), path.end().y(), path.end().z()) == BlockFlags.PASSABLE);
        assertTrue(path.end().distance(inWall) <= 1.5, "target should snap to an adjacent cell, got " + path.end());
    }

    @Test
    void alreadyThereIsFoundWithSingleNode() {
        AsciiGrid f = AsciiGrid.parse("""
                y=64
                #####
                #S..#
                #####
                """);
        WalkResult result = finder.find(f.grid(), WalkRequest.of(f.start(), f.start()));
        assertTrue(result.found());
        assertEquals(1, result.path().size());
        assertEquals(0.0, result.path().totalDistance());
    }

    @Test
    void tooFarIsRefusedBeforeSearching() {
        ArrayBlockGrid grid = new ArrayBlockGrid(0, 64, 0, 400, 1, 3);
        WalkResult result = finder.find(grid, new WalkRequest(new Pos(1, 64, 1), new Pos(398, 64, 1), 100, Duration.ofSeconds(1)));
        assertEquals(WalkResult.Status.TOO_FAR, result.status());
    }

    @Test
    void invalidStartWhenNoStandingCellNearby() {
        // Nothing is known here: an unloaded area offers no ground and no enterable cells.
        ArrayBlockGrid grid = new ArrayBlockGrid(0, 64, 0, 20, 3, 20);
        for (int x = 0; x < 20; x++) {
            for (int z = 0; z < 20; z++) {
                for (int y = 64; y < 67; y++) {
                    grid.set(x, y, z, BlockFlags.UNLOADED);
                }
            }
        }
        WalkResult result = finder.find(grid, WalkRequest.of(new Pos(10, 65, 10), new Pos(12, 65, 10)));
        assertEquals(WalkResult.Status.INVALID_START, result.status());
    }

    @Test
    void unloadedTerrainIsImpassable() {
        AsciiGrid f = AsciiGrid.parse("""
                y=64
                #########
                #S..???T#
                #...???.#
                #########
                """);
        WalkResult result = run(f);
        assertFalse(result.found(), result.toString());
    }
}
