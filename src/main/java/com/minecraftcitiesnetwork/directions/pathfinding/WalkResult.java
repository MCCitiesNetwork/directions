package com.minecraftcitiesnetwork.directions.pathfinding;

import java.util.Optional;

/**
 * Outcome of a {@link WalkRequest}.
 *
 * @param status  what happened
 * @param path    the walkable path when {@code status} is {@link Status#FOUND}; otherwise the best
 *                partial path the engine could offer, or null
 * @param start   the standing cell the search started from, when one was found
 * @param target  the standing cell the search aimed for, when one was found
 * @param nanos   wall-clock duration of the whole request, including snapping
 * @param detail  short human-readable note for logs and the stats command
 */
public record WalkResult(Status status, WalkPath path, Pos start, Pos target, long nanos, String detail) {

    public enum Status {
        /** A complete path from start to target. */
        FOUND,
        /** The search space was exhausted without reaching the target. */
        NONE,
        /** The wall-clock budget ran out. */
        TIMEOUT,
        /** The endpoints are further apart than the request allows. */
        TOO_FAR,
        /** The iteration cap was hit. Treated like NONE by callers. */
        EXHAUSTED,
        /** No standing cell near the start. */
        INVALID_START,
        /** No standing cell near the target. */
        INVALID_TARGET,
        /** The engine reported a failure. */
        ERROR
    }

    public boolean found() {
        return status == Status.FOUND && path != null;
    }

    public Optional<WalkPath> foundPath() {
        return found() ? Optional.of(path) : Optional.empty();
    }

    public double millis() {
        return nanos / 1_000_000.0;
    }

    public static WalkResult of(Status status, Pos start, Pos target, long nanos, String detail) {
        return new WalkResult(status, null, start, target, nanos, detail);
    }

    @Override
    public String toString() {
        return status + (path != null ? " " + path : "") + " in " + String.format("%.1f", millis()) + " ms"
                + (detail == null || detail.isEmpty() ? "" : " (" + detail + ")");
    }
}
