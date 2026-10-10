package com.minecraftcitiesnetwork.directions.walk;

/**
 * One window search.
 *
 * @param x             the player's position; snapped to a surface within {@link WindowSearch#SNAP_RADIUS}
 * @param routeEnd      the end of the route the player is following, or null when they have none;
 *                      exits that lead away from it cost extra, so a re-plan keeps the player going
 *                      unless turning back saves more than that
 * @param maxExpansions node expansions allowed before giving up with BUDGET
 * @param deadlineNanos {@link System#nanoTime()} value after which the search gives up with BUDGET
 */
public record SearchRequest(double x, double y, double z, Goal goal, Window window, Cell routeEnd,
                            int maxExpansions, long deadlineNanos) {
}
