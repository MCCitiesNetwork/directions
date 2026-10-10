package com.minecraftcitiesnetwork.directions.navigation;

/** One part of a trip: where it ends and how it is travelled. */
public record Leg(Waypoint target, LegKind kind) {
    public static Leg walk(Waypoint target) {
        return new Leg(target, LegKind.WALK);
    }

    public static Leg ride(Waypoint target) {
        return new Leg(target, LegKind.RIDE);
    }
}
