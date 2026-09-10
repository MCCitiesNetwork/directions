package com.minecraftcitiesnetwork.directions.pathfinding;

import com.minecraftcitiesnetwork.directions.pathfinding.engine.EngineBridge;

import java.util.Optional;

/**
 * Finds walking paths on a {@link BlockGrid}. Synchronous and thread-safe: call it from any search
 * thread with any grid. Everything Pathetic-specific sits behind {@link EngineBridge}; this class
 * owns the Directions-side contract: snapping endpoints, distance limits, budgets and result mapping.
 */
public final class WalkPathfinder {
    private final WalkerProfile profile;
    private final EngineBridge engine;

    public WalkPathfinder(WalkerProfile profile, int maxIterations) {
        this(profile, maxIterations, false);
    }

    /**
     * @param flatOnly restricts moves to the four cardinal directions. Only for reference tests
     *                 that compare against breadth-first search.
     */
    public WalkPathfinder(WalkerProfile profile, int maxIterations, boolean flatOnly) {
        this.profile = profile;
        this.engine = new EngineBridge(maxIterations, flatOnly);
    }

    public WalkerProfile profile() {
        return profile;
    }

    /** Nearest standing cell, or the surface of the column when the point is far above the ground. */
    private Optional<Pos> resolve(BlockGrid grid, Pos p) {
        return StandingPositions.snap(grid, profile, p)
                .or(() -> StandingPositions.surface(grid, profile, p.x(), p.z(), grid.maxY() + 1));
    }

    public WalkResult find(BlockGrid grid, WalkRequest request) {
        long t0 = System.nanoTime();

        Optional<Pos> start = resolve(grid, request.from());
        if (start.isEmpty()) {
            return WalkResult.of(WalkResult.Status.INVALID_START, null, null, System.nanoTime() - t0,
                    "no standing cell within " + profile.snapRadius() + " of " + request.from());
        }
        Optional<Pos> target = resolve(grid, request.to());
        if (target.isEmpty()) {
            return WalkResult.of(WalkResult.Status.INVALID_TARGET, start.get(), null, System.nanoTime() - t0,
                    "no standing cell within " + profile.snapRadius() + " of " + request.to());
        }

        Pos s = start.get();
        Pos t = target.get();
        if (request.maxSearchDistance() > 0 && s.distance(t) > request.maxSearchDistance()) {
            return WalkResult.of(WalkResult.Status.TOO_FAR, s, t, System.nanoTime() - t0,
                    Math.round(s.distance(t)) + " blocks apart, limit " + Math.round(request.maxSearchDistance()));
        }
        if (s.equals(t)) {
            return new WalkResult(WalkResult.Status.FOUND, new WalkPath(java.util.List.of(s)), s, t, System.nanoTime() - t0, "already there");
        }

        long deadline = t0 + request.budget().toNanos();
        EngineBridge.Outcome outcome = engine.search(grid, profile, s, t, deadline);
        long nanos = System.nanoTime() - t0;

        return switch (outcome.state()) {
            case FOUND -> new WalkResult(WalkResult.Status.FOUND, outcome.path(), s, t, nanos, outcome.iterations() + " iterations");
            case TIMEOUT -> new WalkResult(WalkResult.Status.TIMEOUT, outcome.path(), s, t, nanos, "budget " + request.budget().toMillis() + " ms");
            case EXHAUSTED -> new WalkResult(WalkResult.Status.EXHAUSTED, outcome.path(), s, t, nanos, "iteration cap");
            case NONE -> new WalkResult(WalkResult.Status.NONE, outcome.path(), s, t, nanos, outcome.detail());
            case ERROR -> new WalkResult(WalkResult.Status.ERROR, null, s, t, nanos, outcome.detail());
        };
    }
}
