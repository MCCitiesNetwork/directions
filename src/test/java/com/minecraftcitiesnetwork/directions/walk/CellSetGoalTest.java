package com.minecraftcitiesnetwork.directions.walk;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CellSetGoalTest {
    private static final double EPS = 1e-9;

    private static CellSetGoal twoCells() {
        return new CellSetGoal(Set.of(Cell.key(10, 65, 10), Cell.key(12, 65, 10)));
    }

    @Test
    void containsOnlyItsCells() {
        CellSetGoal goal = twoCells();
        assertTrue(goal.contains(10, 65, 10));
        assertTrue(goal.contains(12, 65, 10));
        // Inside the bounding box, but not one of the cells.
        assertFalse(goal.contains(11, 65, 10));
        assertFalse(goal.contains(10, 66, 10));
    }

    @Test
    void estimateIsDistanceToTheBoundingBox() {
        CellSetGoal goal = twoCells();
        assertEquals(10.0, goal.estimate(0, 65, 10), EPS);
        assertEquals(0.0, goal.estimate(11, 65, 10), EPS);
        assertEquals(new Bounds(10, 65, 10, 12, 65, 10), goal.bounds());
        assertTrue(goal.mayOverlap(new Window(0, 0, 10, 10)));
        assertFalse(goal.mayOverlap(new Window(0, 0, 9, 9)));
    }

    @Test
    void emptySetIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new CellSetGoal(Set.of()));
    }
}
