package com.minecraftcitiesnetwork.directions.pathfinding.engine;

import com.minecraftcitiesnetwork.directions.pathfinding.BlockGrid;
import com.minecraftcitiesnetwork.directions.pathfinding.Pos;
import com.minecraftcitiesnetwork.directions.pathfinding.WalkPath;
import com.minecraftcitiesnetwork.directions.pathfinding.WalkerProfile;
import de.bsommerfeld.pathetic.api.pathing.Pathfinder;
import de.bsommerfeld.pathetic.api.pathing.configuration.PathfinderConfiguration;
import de.bsommerfeld.pathetic.api.pathing.heuristic.HeuristicStrategies;
import de.bsommerfeld.pathetic.api.pathing.heuristic.HeuristicWeights;
import de.bsommerfeld.pathetic.api.pathing.hook.PathfinderHook;
import de.bsommerfeld.pathetic.api.pathing.hook.PathfindingContext;
import de.bsommerfeld.pathetic.api.pathing.result.Path;
import de.bsommerfeld.pathetic.api.pathing.result.PathfinderResult;
import de.bsommerfeld.pathetic.api.wrapper.PathPosition;
import de.bsommerfeld.pathetic.engine.factory.AStarPathfinderFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * The one place Directions talks to the Pathetic engine.
 *
 * <p>Configuration choices, and why:
 * <ul>
 *   <li>Synchronous engine. Directions owns its threads and completes results on the main thread
 *       itself, so the engine's own pool is never created.</li>
 *   <li>Octile-only heuristic. With Euclidean step costs and 26 unit moves the 3D octile distance is
 *       exactly the free-space optimum, so it is admissible and paths are optimal. The engine's
 *       default weights over-estimate by design and would trade optimality for speed.</li>
 *   <li>Fallback enabled so a timed-out or exhausted search still returns its best partial path
 *       for diagnostics.</li>
 * </ul>
 */
public final class EngineBridge {
    private final Pathfinder pathfinder;
    private final IterationCounter counter = new IterationCounter();

    public EngineBridge(int maxIterations, boolean flatOnly) {
        PathfinderConfiguration config = PathfinderConfiguration.builder()
                .provider(new GridNavigationProvider())
                .validationProcessors(List.of(new WalkMoveValidator()))
                .costProcessor(List.of(new TransitionCost()))
                .neighborStrategy(new WalkerNeighbors(flatOnly))
                .heuristicStrategy(HeuristicStrategies.LINEAR)
                .heuristicWeights(HeuristicWeights.create(0.0, 1.0, 0.0, 0.0))
                .maxIterations(Math.max(1, maxIterations))
                .fallback(true)
                .async(false)
                .pathfindingHooks(List.of(counter))
                .build();
        this.pathfinder = new AStarPathfinderFactory().createPathfinder(config);
    }

    public enum State { FOUND, TIMEOUT, EXHAUSTED, NONE, ERROR }

    public record Outcome(State state, WalkPath path, int iterations, String detail) {
    }

    public Outcome search(BlockGrid grid, WalkerProfile profile, Pos start, Pos target, long deadlineNanos) {
        GridEnvironment env = new GridEnvironment(grid, profile, deadlineNanos);
        SearchClock.enter(env);
        counter.reset();
        try {
            PathfinderResult result = pathfinder
                    .findPath(position(start), position(target), env)
                    .resultBlocking();
            WalkPath path = toWalkPath(result.getPath());
            int iterations = counter.value();
            return switch (result.getPathState()) {
                case FOUND -> new Outcome(State.FOUND, path, iterations, "");
                case MAX_ITERATIONS_REACHED -> env.expired()
                        ? new Outcome(State.TIMEOUT, path, iterations, "timeout")
                        : new Outcome(State.EXHAUSTED, path, iterations, "iteration cap");
                case FALLBACK, FAILED, ABORTED, LENGTH_LIMITED -> env.expired()
                        ? new Outcome(State.TIMEOUT, path, iterations, "timeout")
                        : new Outcome(State.NONE, path, iterations, result.getPathState().name().toLowerCase());
            };
        } catch (RuntimeException e) {
            return new Outcome(State.ERROR, null, counter.value(), e.getClass().getSimpleName() + ": " + e.getMessage());
        } finally {
            SearchClock.exit();
        }
    }

    private static PathPosition position(Pos p) {
        return new PathPosition(p.x(), p.y(), p.z());
    }

    private static WalkPath toWalkPath(Path path) {
        if (path == null || path.length() == 0) {
            return null;
        }
        List<Pos> nodes = new ArrayList<>(path.length());
        for (PathPosition p : path) {
            nodes.add(new Pos(p.getFlooredX(), p.getFlooredY(), p.getFlooredZ()));
        }
        return new WalkPath(nodes);
    }

    /** Counts expansions of the search running on this thread. */
    private static final class IterationCounter implements PathfinderHook {
        private final ThreadLocal<int[]> count = ThreadLocal.withInitial(() -> new int[1]);

        void reset() {
            count.get()[0] = 0;
        }

        int value() {
            return count.get()[0];
        }

        @Override
        public void onPathfindingStep(PathfindingContext pathfindingContext) {
            count.get()[0]++;
        }
    }
}
