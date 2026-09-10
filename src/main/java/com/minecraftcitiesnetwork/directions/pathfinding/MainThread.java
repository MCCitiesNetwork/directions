package com.minecraftcitiesnetwork.directions.pathfinding;

/**
 * The thread that owns game state. Results computed on search threads are handed back through it.
 * The Bukkit implementation uses the scheduler; tests use a synchronous one.
 */
@FunctionalInterface
public interface MainThread {
    void run(Runnable task);

    /** Runs tasks inline. For tests and for callers already on the main thread. */
    MainThread INLINE = Runnable::run;
}
