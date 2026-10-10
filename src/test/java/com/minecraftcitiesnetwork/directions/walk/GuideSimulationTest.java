package com.minecraftcitiesnetwork.directions.walk;

import com.minecraftcitiesnetwork.directions.walk.WalkSimulation.City;
import com.minecraftcitiesnetwork.directions.walk.WalkSimulation.Report;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Whole walks through {@link GuidePolicy} and {@link WindowSearch}: the trail must keep leading
 * the player onward, and turn them around only when the way ahead is really a dead end.
 */
class GuideSimulationTest {
    private static final int FULL_WINDOW = 80;
    private static final int SMALL_WINDOW = 48;
    private static final double START_X = 3.5;
    private static final double START_Z = 4.5;
    private static final Goal FAR_EAST = new PointGoal(1000, 64, 4, 3, false);
    private static final double SECONDS = 400;

    private static final City OPEN_FIELD = (x, z) -> true;
    /** Roads 8 wide every 48 blocks in both directions. */
    private static final City GRID_CITY = (x, z) -> Math.floorMod(x, 48) < 8 || Math.floorMod(z, 48) < 8;
    /** One road 8 wide along z = 0..7. */
    private static final City STRAIGHT_ROAD = (x, z) -> z >= 0 && z < 8;
    /** Open ground with a river at x = 300..309 and one bridge at z = 120..127. */
    private static final City RIVER_WITH_BRIDGE = (x, z) -> !(x >= 300 && x < 310) || (z >= 120 && z < 128);
    /** The road ends at x = 200; a side street at x = −100 leads to a parallel road at z = 200. */
    private static final City DEAD_END_WITH_BRANCH = (x, z) -> (z >= 0 && z < 8 && x < 200)
            || (x >= -100 && x < -92 && z >= 0 && z < 208)
            || (z >= 200 && z < 208);

    @Test
    void openFieldArrivesWithoutTurningBack() {
        for (int window : new int[] {FULL_WINDOW, SMALL_WINDOW}) {
            Report report = walk(OPEN_FIELD, window);
            assertTrue(report.arrived(), "window " + window + ": " + report);
            assertEquals(0, report.reversals(), "window " + window + ": " + report);
        }
    }

    @Test
    void gridCityArrivesWithoutTurningBack() {
        for (int window : new int[] {FULL_WINDOW, SMALL_WINDOW}) {
            Report report = walk(GRID_CITY, window);
            assertTrue(report.arrived(), "window " + window + ": " + report);
            assertEquals(0, report.reversals(), "window " + window + ": " + report);
        }
    }

    @Test
    void straightRoadInASmallWindowArrivesWithoutTurningBack() {
        Report report = walk(STRAIGHT_ROAD, SMALL_WINDOW);
        assertTrue(report.arrived(), report.toString());
        assertEquals(0, report.reversals(), report.toString());
        assertEquals(0, report.noRouteResults(), report.toString());
    }

    @Test
    void riverBankIsFollowedWithoutBouncing() {
        Report report = walk(RIVER_WITH_BRIDGE, FULL_WINDOW);
        assertEquals(0, report.reversals(), report.toString());
        assertEquals(0, report.noRouteResults(), report.toString());
    }

    @Test
    void deadEndTurnsThePlayerBackOnceAndTakesTheBranch() {
        Report report = walk(DEAD_END_WITH_BRANCH, FULL_WINDOW);
        assertEquals(1, report.reversals(), report.toString());
        assertEquals(0, report.noRouteResults(), report.toString());
        assertTrue(report.z() >= 200, "should have reached the parallel road: " + report);
    }

    private static Report walk(City city, int window) {
        return WalkSimulation.walk(city, window, START_X, START_Z, FAR_EAST, SECONDS);
    }
}
