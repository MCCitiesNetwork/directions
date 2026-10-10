package com.minecraftcitiesnetwork.directions.walk.coarse;

import com.minecraftcitiesnetwork.directions.walk.SearchRequest;
import com.minecraftcitiesnetwork.directions.walk.SearchResult;
import com.minecraftcitiesnetwork.directions.walk.Terrain;
import com.minecraftcitiesnetwork.directions.walk.WalkRules;
import com.minecraftcitiesnetwork.directions.walk.WindowSearch;

/**
 * The window search, led by the coarse graph when the goal is out of reach of the window: the goal
 * directly when the window can reach it, else a waypoint on the coarse path, else the best exit.
 * Safe to share between threads.
 */
public final class GuidedSearch {
    private final WindowSearch window;
    private final WalkRules rules;

    public GuidedSearch(WindowSearch window, WalkRules rules) {
        this.window = window;
        this.rules = rules;
    }

    /**
     * The request's own deadline is ignored: each phase (the direct search, the coarse search, the
     * search to the waypoint, the fallback) gets its own deadline of {@code timeBudgetNanos} from
     * when it starts, so a slow phase cannot leave the next one without time.
     *
     * @param graph the world's coarse graph, or null when it has none; without nodes the result is
     *              the window search's own
     */
    public SearchResult search(Terrain terrain, SearchRequest request, CoarseGraph graph, int coarseMaxExpansions,
                               long timeBudgetNanos) {
        if (graph == null || graph.isEmpty()) {
            return window.search(terrain, request.withDeadline(deadline(timeBudgetNanos)));
        }
        SearchResult direct = null;
        if (request.goal().mayOverlap(request.window())) {
            direct = window.searchDirect(terrain, request.withDeadline(deadline(timeBudgetNanos)));
            if (direct.status() == SearchResult.Status.COMPLETE) {
                return direct;
            }
        }
        WindowSearch.Start start = WindowSearch.snap(terrain, request.x(), request.y(), request.z(), request.window());
        if (start == null) {
            return direct != null ? direct : window.search(terrain, request.withDeadline(deadline(timeBudgetNanos)));
        }
        long coarseStarted = System.nanoTime();
        CoarsePlanner.Plan plan = CoarsePlanner.plan(graph, terrain, rules, start, request.goal(), request.window(),
                coarseMaxExpansions, plus(coarseStarted, timeBudgetNanos));
        long coarseNanos = Math.max(1L, System.nanoTime() - coarseStarted);
        if (plan != null) {
            SearchResult guided = window.searchDirect(terrain,
                    request.toward(plan.goal(), deadline(timeBudgetNanos)));
            if (guided.status() == SearchResult.Status.COMPLETE) {
                return new SearchResult(SearchResult.Status.PARTIAL, guided.route().asPartial(plan.remaining()),
                        guided.cost(), guided.expansions(), guided.nanos(), true, coarseNanos);
            }
        }
        SearchRequest fallback = request.withDeadline(deadline(timeBudgetNanos));
        // The direct phase already ran: only the exit phase is left to do.
        SearchResult result = direct != null ? window.searchOnward(terrain, fallback) : window.search(terrain, fallback);
        return result.withCoarseNanos(coarseNanos);
    }

    /** A deadline {@code budget} from now, saturating rather than overflowing. */
    private static long deadline(long budget) {
        return plus(System.nanoTime(), budget);
    }

    private static long plus(long now, long budget) {
        try {
            return Math.addExact(now, budget);
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }
}
