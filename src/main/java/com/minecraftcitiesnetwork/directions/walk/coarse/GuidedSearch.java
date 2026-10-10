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
     * @param graph the world's coarse graph, or null when it has none; without nodes the result is
     *              the window search's own
     */
    public SearchResult search(Terrain terrain, SearchRequest request, CoarseGraph graph, int coarseMaxExpansions) {
        if (graph == null || graph.size() == 0) {
            return window.search(terrain, request);
        }
        if (request.goal().mayOverlap(request.window())) {
            SearchResult direct = window.searchDirect(terrain, request);
            if (direct.status() == SearchResult.Status.COMPLETE) {
                return direct;
            }
        }
        WindowSearch.Start start = WindowSearch.snap(terrain, request.x(), request.y(), request.z(), request.window());
        if (start == null) {
            return window.search(terrain, request);
        }
        CoarsePlanner.Plan plan = CoarsePlanner.plan(graph, terrain, rules, start, request.goal(), request.window(),
                coarseMaxExpansions);
        if (plan != null) {
            SearchRequest toWaypoint = new SearchRequest(request.x(), request.y(), request.z(), plan.goal(),
                    request.window(), null, request.maxExpansions(), request.deadlineNanos());
            SearchResult guided = window.searchDirect(terrain, toWaypoint);
            if (guided.status() == SearchResult.Status.COMPLETE) {
                return new SearchResult(SearchResult.Status.PARTIAL, guided.route().asPartial(plan.remaining()),
                        guided.cost(), guided.expansions(), guided.nanos(), true);
            }
        }
        return window.search(terrain, request);
    }
}
