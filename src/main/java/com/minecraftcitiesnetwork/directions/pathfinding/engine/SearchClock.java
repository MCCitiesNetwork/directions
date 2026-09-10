package com.minecraftcitiesnetwork.directions.pathfinding.engine;

/**
 * Makes the current search's {@link GridEnvironment} visible to extension points that the engine
 * calls without a context, such as the neighbour strategy. The engine runs a synchronous search on
 * the calling thread, so a thread-local is exact.
 */
final class SearchClock {
    private static final ThreadLocal<GridEnvironment> CURRENT = new ThreadLocal<>();

    private SearchClock() {
    }

    static void enter(GridEnvironment env) {
        CURRENT.set(env);
    }

    static void exit() {
        CURRENT.remove();
    }

    static boolean expired() {
        GridEnvironment env = CURRENT.get();
        return env != null && env.expired();
    }
}
