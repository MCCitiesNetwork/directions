package com.minecraftcitiesnetwork.directions.navigation;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.function.BiPredicate;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LegPlannerTest {
    private static final String START = "__start__";
    private static final String DEST = "__dest__";
    private static final Waypoint.Coordinates HOME = new Waypoint.Coordinates(500, 64, 500, false);

    private static BiPredicate<String, String> rides(String... pairs) {
        Set<String> set = Set.of(pairs);
        return (a, b) -> set.contains(a + ">" + b);
    }

    private static Leg walk(String stop) {
        return Leg.walk(new Waypoint.Region(stop));
    }

    private static Leg ride(String stop) {
        return Leg.ride(new Waypoint.Region(stop));
    }

    @Test
    void walkRideRideWalk() {
        List<Leg> legs = LegPlanner.plan(List.of(START, "a", "b", "c", DEST), START, DEST, HOME, rides("a>b", "b>c"));
        assertEquals(List.of(walk("a"), ride("b"), ride("c"), Leg.walk(HOME)), legs);
    }

    @Test
    void walkingTransferBetweenLinesIsAWalk() {
        List<Leg> legs = LegPlanner.plan(List.of(START, "a", "b", "x", "y", DEST), START, DEST, HOME, rides("a>b", "x>y"));
        assertEquals(List.of(walk("a"), ride("b"), walk("x"), ride("y"), Leg.walk(HOME)), legs);
    }

    @Test
    void destinationThatIsTheLastStopAddsNoExtraLeg() {
        Waypoint.Region last = new Waypoint.Region("c");
        List<Leg> legs = LegPlanner.plan(List.of(START, "a", "c", DEST), START, DEST, last, rides("a>c"));
        assertEquals(List.of(walk("a"), ride("c")), legs);
    }

    @Test
    void walkOnlyRouteIsOneWalk() {
        assertEquals(List.of(Leg.walk(HOME)), LegPlanner.plan(List.of(START, DEST), START, DEST, HOME, rides()));
    }

    @Test
    void theFirstLegIsAlwaysAWalk() {
        List<Leg> legs = LegPlanner.plan(List.of(START, "a", DEST), START, DEST, HOME, (x, y) -> true);
        assertEquals(List.of(walk("a"), Leg.walk(HOME)), legs);
    }
}
