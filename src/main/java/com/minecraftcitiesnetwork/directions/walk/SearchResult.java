package com.minecraftcitiesnetwork.directions.walk;

/**
 * @param route the route for COMPLETE and PARTIAL, otherwise null
 * @param cost   walking cost of the route
 * @param coarse      whether the route leads to a waypoint chosen with the coarse graph
 * @param coarseNanos time spent planning over the coarse graph; 0 when the graph was not consulted
 */
public record SearchResult(Status status, Route route, double cost, int expansions, long nanos, boolean coarse,
                           long coarseNanos) {
    public enum Status {
        /** The route reaches the goal. */
        COMPLETE,
        /** The goal is outside the window; the route ends at the best exit. */
        PARTIAL,
        /** Nothing reachable inside the window leads on. */
        NO_ROUTE,
        /** The expansion cap or the time budget ran out. */
        BUDGET,
        /** No surface near the player to start from. */
        INVALID_START,
        /** The player's own column is not captured yet; try again shortly. */
        UNCAPTURED_START
    }

    public SearchResult(Status status, Route route, double cost, int expansions, long nanos, boolean coarse) {
        this(status, route, cost, expansions, nanos, coarse, 0L);
    }

    public SearchResult(Status status, Route route, double cost, int expansions, long nanos) {
        this(status, route, cost, expansions, nanos, false);
    }

    /** This result, reporting the given coarse planning time. */
    public SearchResult withCoarseNanos(long nanos) {
        return new SearchResult(status, route, cost, expansions, this.nanos, coarse, nanos);
    }

    public boolean found() {
        return status == Status.COMPLETE || status == Status.PARTIAL;
    }

    public static SearchResult failed(Status status, int expansions, long nanos) {
        return new SearchResult(status, null, 0.0, expansions, nanos);
    }
}
