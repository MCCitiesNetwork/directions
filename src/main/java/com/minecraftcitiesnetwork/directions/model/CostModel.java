package com.minecraftcitiesnetwork.directions.model;

import java.util.Locale;
import java.util.Optional;

/**
 * How a line's edges are weighted for routing.
 *
 * <p>{@link #DISTANCE} weights each hop by the 2D distance between stops (plus the transfer penalty),
 * so longer rides cost more. {@link #FIXED} charges a flat transfer penalty per hop regardless of
 * distance, modelling a flat-fare line where ride length is irrelevant.
 */
public enum CostModel {
    DISTANCE,
    FIXED;

    public static final CostModel DEFAULT = DISTANCE;

    /**
     * Parses the config value. {@code null} yields {@link #DEFAULT}; an unrecognised value yields
     * {@link Optional#empty()} so the caller can report it.
     */
    public static Optional<CostModel> fromConfig(String raw) {
        if (raw == null) {
            return Optional.of(DEFAULT);
        }
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "distance" -> Optional.of(DISTANCE);
            case "fixed" -> Optional.of(FIXED);
            default -> Optional.empty();
        };
    }
}
