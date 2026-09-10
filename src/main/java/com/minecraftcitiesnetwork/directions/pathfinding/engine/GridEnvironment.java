package com.minecraftcitiesnetwork.directions.pathfinding.engine;

import com.minecraftcitiesnetwork.directions.pathfinding.BlockGrid;
import com.minecraftcitiesnetwork.directions.pathfinding.WalkerProfile;
import de.bsommerfeld.pathetic.api.pathing.context.EnvironmentContext;

/**
 * Per-search payload handed to the engine and read back by the provider, validator and cost
 * processor: the world to search, the walker's rules, and the deadline.
 */
public final class GridEnvironment implements EnvironmentContext {
    private final BlockGrid grid;
    private final WalkerProfile profile;
    private final long deadlineNanos;
    private volatile boolean expired;

    public GridEnvironment(BlockGrid grid, WalkerProfile profile, long deadlineNanos) {
        this.grid = grid;
        this.profile = profile;
        this.deadlineNanos = deadlineNanos;
    }

    public BlockGrid grid() {
        return grid;
    }

    public WalkerProfile profile() {
        return profile;
    }

    /** True once the wall-clock budget has run out. Sticky. */
    public boolean expired() {
        if (expired) {
            return true;
        }
        if (System.nanoTime() >= deadlineNanos) {
            expired = true;
        }
        return expired;
    }

    public static GridEnvironment of(EnvironmentContext context) {
        if (context instanceof GridEnvironment env) {
            return env;
        }
        throw new IllegalStateException("Search was started without a GridEnvironment");
    }
}
