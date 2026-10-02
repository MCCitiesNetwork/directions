package com.minecraftcitiesnetwork.directions.walk;

import java.util.List;

/**
 * One window search.
 *
 * @param x             the player's position; snapped to a surface within {@link WindowSearch#SNAP_RADIUS}
 * @param spentExits    exits this leg has already led the player to; edges near them are not offered again
 * @param maxExpansions node expansions allowed before giving up with BUDGET
 * @param deadlineNanos {@link System#nanoTime()} value after which the search gives up with BUDGET
 */
public record SearchRequest(double x, double y, double z, Goal goal, Window window, List<Cell> spentExits,
                            int maxExpansions, long deadlineNanos) {
    public SearchRequest {
        spentExits = List.copyOf(spentExits);
    }
}
