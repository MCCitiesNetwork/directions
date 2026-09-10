package com.minecraftcitiesnetwork.directions.pathfinding;

import java.time.Duration;

/**
 * One search: where from, where to, and how hard to try.
 *
 * @param from              the player's position (any point; snapped to a standing cell)
 * @param to                the destination (any point; snapped to a standing cell)
 * @param maxSearchDistance straight-line distance beyond which the search is refused, or 0 for none
 * @param budget            wall-clock time the search may use before it is starved and reported as a timeout
 */
public record WalkRequest(Pos from, Pos to, double maxSearchDistance, Duration budget) {
    public WalkRequest {
        if (maxSearchDistance < 0) {
            throw new IllegalArgumentException("maxSearchDistance must not be negative");
        }
        if (budget == null || budget.isNegative() || budget.isZero()) {
            throw new IllegalArgumentException("budget must be positive");
        }
    }

    public static WalkRequest of(Pos from, Pos to) {
        return new WalkRequest(from, to, 0, Duration.ofMillis(250));
    }
}
