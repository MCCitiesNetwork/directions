package com.minecraftcitiesnetwork.directions.walk;

/**
 * Decides when one walking leg needs a new search and what to do with the answer. Pure: the caller
 * supplies time, position and search results. One instance per walking leg; not thread-safe.
 */
public final class GuidePolicy {
    public static final double TELEPORT_DISTANCE = 64;
    public static final long RETRY_MILLIS = 10_000;
    public static final double RETRY_DISTANCE = 16;
    public static final long MIN_INTERVAL_MILLIS = 250;
    public static final long TERRAIN_WAIT_MILLIS = 1000;

    public record Tuning(double extendDistance, double offRouteDistance, long cooldownMillis) {
    }

    public enum Reason {
        INITIAL, EXTEND, OFF_ROUTE, BLOCKED, RETRY
    }

    /** What the guide sees this tick. {@code blockedAhead}: a cell of the drawn trail became impassable. */
    public record Observation(long nowMillis, double x, double y, double z, boolean onFoot, boolean blockedAhead) {
    }

    /** @param routeEnd the end of the route being followed, or null for the leg's first search */
    public record SearchOrder(int generation, Reason reason, Cell routeEnd) {
    }

    private enum State {
        IDLE, FOLLOWING, FAILED
    }

    private final Tuning tuning;
    private State state = State.IDLE;
    private int generation;
    private Route route;
    private int cursor;
    private boolean inFlight;
    private Reason inFlightReason;
    private long lastSearchAt = Long.MIN_VALUE / 2;
    private long waitUntil = Long.MIN_VALUE;
    private long failedAt;
    private double failedX;
    private double failedZ;
    private double lastX;
    private double lastZ;
    private boolean announced;
    private boolean pendingMessage;

    public GuidePolicy(Tuning tuning) {
        this.tuning = tuning;
    }

    /** Updates position along the route and returns a search to start now, or null. */
    public SearchOrder tick(Observation o) {
        lastX = o.x();
        lastZ = o.z();
        double offRoute = 0.0;
        if (route != null) {
            cursor = route.nearestIndex(o.x(), o.y(), o.z(), cursor);
            offRoute = route.distanceTo(cursor, o.x(), o.y(), o.z());
            if (offRoute > TELEPORT_DISTANCE) {
                reset();
            }
        }
        Reason want = switch (state) {
            case IDLE -> o.nowMillis() >= waitUntil ? Reason.INITIAL : null;
            case FAILED -> retryDue(o) ? Reason.RETRY : null;
            case FOLLOWING -> followingReason(o, offRoute);
        };
        if (want == null || inFlight) {
            return null;
        }
        if ((want == Reason.INITIAL || want == Reason.RETRY) && !o.onFoot()) {
            return null;
        }
        long since = o.nowMillis() - lastSearchAt;
        if (since < MIN_INTERVAL_MILLIS) {
            return null;
        }
        boolean cooled = since >= tuning.cooldownMillis();
        if ((want == Reason.EXTEND || want == Reason.OFF_ROUTE) && !cooled) {
            return null;
        }
        inFlight = true;
        inFlightReason = want;
        lastSearchAt = o.nowMillis();
        return new SearchOrder(generation, want, route == null ? null : route.end());
    }

    public void onResult(int resultGeneration, SearchResult result, long nowMillis) {
        inFlight = false;
        if (resultGeneration != generation) {
            return;
        }
        if (result.found()) {
            route = result.route();
            cursor = 0;
            state = State.FOLLOWING;
            return;
        }
        if (route != null && (inFlightReason == Reason.EXTEND || inFlightReason == Reason.OFF_ROUTE)) {
            return;
        }
        if (result.status() == SearchResult.Status.UNCAPTURED_START) {
            route = null;
            state = State.IDLE;
            waitUntil = nowMillis + TERRAIN_WAIT_MILLIS;
            return;
        }
        route = null;
        state = State.FAILED;
        failedAt = nowMillis;
        failedX = lastX;
        failedZ = lastZ;
        if (!announced) {
            announced = true;
            pendingMessage = true;
        }
    }

    /** Forgets the route and starts the leg again, e.g. after a teleport or a world change. */
    public void reset() {
        generation++;
        route = null;
        cursor = 0;
        state = State.IDLE;
        waitUntil = Long.MIN_VALUE;
    }

    public Route route() {
        return state == State.FOLLOWING ? route : null;
    }

    public int cursor() {
        return cursor;
    }

    public boolean failed() {
        return state == State.FAILED;
    }

    /** True once per leg, after the first failed search. */
    public boolean takeNoRouteMessage() {
        boolean message = pendingMessage;
        pendingMessage = false;
        return message;
    }

    private Reason followingReason(Observation o, double offRoute) {
        if (o.blockedAhead()) {
            return Reason.BLOCKED;
        }
        if (!o.onFoot()) {
            return null;
        }
        if (offRoute > tuning.offRouteDistance()) {
            return Reason.OFF_ROUTE;
        }
        if (!route.complete() && route.remainingFrom(cursor, o.x(), o.y(), o.z()) <= tuning.extendDistance()) {
            return Reason.EXTEND;
        }
        return null;
    }

    private boolean retryDue(Observation o) {
        return o.nowMillis() - failedAt >= RETRY_MILLIS
                || Math.hypot(o.x() - failedX, o.z() - failedZ) >= RETRY_DISTANCE;
    }
}
