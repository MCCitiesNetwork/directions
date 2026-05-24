package com.minecraftcitiesnetwork.directions.navigation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WaypointCoordinatesTest {

    @Test
    void withinRadiusHorizontal() {
        Waypoint.Coordinates target = new Waypoint.Coordinates(103.0, 64.0, 200.0, false);
        assertTrue(target.isWithinRadius(100.0, 64.0, 200.0, 5.0));
        assertFalse(target.isWithinRadius(100.0, 64.0, 200.0, 2.0));
    }

    @Test
    void withinRadiusIncludesY() {
        Waypoint.Coordinates target = new Waypoint.Coordinates(100.0, 70.0, 200.0, true);
        assertFalse(target.isWithinRadius(100.0, 64.0, 200.0, 5.0));
        assertTrue(target.isWithinRadius(100.0, 64.0, 200.0, 7.0));
    }
}
