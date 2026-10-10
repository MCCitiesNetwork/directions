package com.minecraftcitiesnetwork.directions.walk;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GoalsTest {
    private static final double EPS = 1e-9;

    @Test
    void windowAroundContainsAndBorders() {
        Window w = Window.around(10, -10, 5);
        assertEquals(new Window(5, -15, 15, -5), w);
        assertTrue(w.contains(5, -15));
        assertFalse(w.contains(16, -10));
        assertTrue(w.onBorder(15, -10));
        assertFalse(w.onBorder(10, -10));
    }

    @Test
    void boxContainsAndEstimates() {
        BoxGoal box = new BoxGoal(10, 60, 0, 20, 70, 5);
        assertTrue(box.contains(10, 60, 5));
        assertFalse(box.contains(9, 65, 0));
        assertEquals(0.0, box.estimate(15, 65, 3), EPS);
        assertEquals(5.0, box.estimate(5, 65, 0), EPS);
        assertEquals(5.0, box.estimate(23, 74, 2), EPS);
        assertTrue(box.mayOverlap(new Window(0, 0, 10, 10)));
        assertFalse(box.mayOverlap(new Window(0, 0, 9, 9)));
    }

    @Test
    void stopAreaMatchesTheArrivalBuffer() {
        StopAreaGoal stop = new StopAreaGoal(100, 100, 110, 110, 5);
        assertTrue(stop.contains(110, 0, 110));
        assertTrue(stop.contains(114, 64, 105));
        assertFalse(stop.contains(116, 64, 105));
        assertEquals(0.0, stop.estimate(112, 64, 105), EPS);
    }

    @Test
    void stopAreaExcludesCornersBeyondTheMargin() {
        StopAreaGoal stop = new StopAreaGoal(100, 100, 110, 110, 5);
        // 4.5 blocks out on both axes is 6.4 blocks from the corner: outside the arrival buffer.
        assertFalse(stop.contains(114, 64, 114));
        assertTrue(stop.contains(113, 64, 113));
    }

    @Test
    void stopAreaWithoutMarginIsTheRegionsBlocks() {
        StopAreaGoal stop = new StopAreaGoal(100, 100, 110, 110, 0);
        assertTrue(stop.contains(110, 64, 110));
        assertFalse(stop.contains(111, 64, 110));
    }

    @Test
    void pointGoalHorizontalAndThreeDimensional() {
        PointGoal flat = new PointGoal(100.0, 999.0, 200.0, 5.0, false);
        assertTrue(flat.contains(103, 64, 200));
        assertFalse(flat.contains(106, 64, 200));
        assertEquals(Math.sqrt(30.5) - 5.0, flat.estimate(105, 64, 199), EPS);

        PointGoal withY = new PointGoal(100.0, 70.0, 200.0, 5.0, true);
        assertFalse(withY.contains(99, 64, 199));
        assertTrue(withY.contains(99, 67, 199));
        assertTrue(withY.mayOverlap(new Window(104, 195, 120, 205)));
        assertFalse(withY.mayOverlap(new Window(106, 195, 120, 205)));
    }

    @Test
    void polygonContainsInsideAndEdgesButNotTheNotch() {
        // L-shape: (0,0) (10,0) (10,4) (4,4) (4,10) (0,10)
        PolygonGoal l = new PolygonGoal(new int[]{0, 10, 10, 4, 4, 0}, new int[]{0, 0, 4, 4, 10, 10}, 60, 70);
        assertTrue(l.contains(2, 65, 2));
        assertTrue(l.contains(8, 65, 2));
        assertTrue(l.contains(2, 65, 8));
        assertTrue(l.contains(10, 65, 0));
        assertTrue(l.contains(4, 65, 7));
        assertFalse(l.contains(8, 65, 8));
        assertFalse(l.contains(2, 71, 2));
        assertEquals(0.0, l.estimate(8, 65, 8), EPS);
        assertEquals(5.0, l.estimate(15, 65, 0), EPS);
    }

    @Test
    void pointGoalBoundsCoverTheRadius() {
        PointGoal point = new PointGoal(10.5, 65, 10.5, 3, false);
        assertEquals(new Bounds(7, Short.MIN_VALUE, 7, 13, Short.MAX_VALUE, 13), point.bounds());
    }

    @Test
    void stopAreaBoundsIncludeTheMargin() {
        StopAreaGoal stop = new StopAreaGoal(100, 100, 110, 110, 2.5);
        assertEquals(new Bounds(96, Short.MIN_VALUE, 96, 114, Short.MAX_VALUE, 114), stop.bounds());
    }
}
