package com.minecraftcitiesnetwork.directions.config;

import com.minecraftcitiesnetwork.directions.walk.ParticleSpec;
import com.minecraftcitiesnetwork.directions.walk.WalkSettings;

import java.util.Locale;
import java.util.function.Consumer;

/** Validates the {@code walking-paths} section: each bad value is reported and replaced by its default. */
public final class WalkSettingsParser {
    private static final String PREFIX = "walking-paths.";

    private WalkSettingsParser() {
    }

    public static WalkSettings parse(ConfigLoader.WalkingPathsSection s, Consumer<String> warn) {
        WalkSettings d = WalkSettings.DEFAULTS;
        ConfigLoader.ParticlesSection particles = s.particles == null ? new ConfigLoader.ParticlesSection() : s.particles;
        return new WalkSettings(
                s.enabled,
                intIn(warn, "window-radius", s.windowRadius, 16, 160, d.windowRadius()),
                longIn(warn, "time-budget-ms", s.timeBudgetMs, 5, 500, d.timeBudgetMillis()),
                intIn(warn, "max-expansions", s.maxExpansions, 1_000, 2_000_000, d.maxExpansions()),
                intIn(warn, "snapshots-per-tick", s.snapshotsPerTick, 1, 64, d.snapshotsPerTick()),
                longIn(warn, "capture-wait-ms", s.captureWaitMs, 0, 10_000, d.captureWaitMillis()),
                intIn(warn, "cache-max-chunks", s.cacheMaxChunks, 256, 65_536, d.cacheMaxChunks()),
                intIn(warn, "max-drop", s.maxDrop, 0, 5, d.maxDrop()),
                doubleIn(warn, "water-cost", s.waterCost, 1, 10, d.waterCost()),
                doubleIn(warn, "climb-cost", s.climbCost, 1, 10, d.climbCost()),
                doubleIn(warn, "door-cost", s.doorCost, 1, 10, d.doorCost()),
                doubleIn(warn, "extend-distance", s.extendDistance, 8, 160, d.extendDistance()),
                doubleIn(warn, "off-route-distance", s.offRouteDistance, 2, 32, d.offRouteDistance()),
                longIn(warn, "replan-cooldown-ms", s.replanCooldownMs, 250, 30_000, d.replanCooldownMillis()),
                intIn(warn, "trail-length", s.trailLength, 4, 200, d.trailLength()),
                intIn(warn, "trail-refresh-ticks", s.trailRefreshTicks, 2, 100, d.trailRefreshTicks()),
                intIn(warn, "pulse-step", s.pulseStep, 1, 50, d.pulseStep()),
                doubleIn(warn, "arrow-look-ahead", s.arrowLookAhead, 1, 32, d.arrowLookAhead()),
                particle(particles.trail, d.trailParticle()),
                particle(particles.pulse, d.pulseParticle()),
                particle(particles.end, d.endParticle()),
                coarse(s.coarse == null ? new ConfigLoader.CoarseSection() : s.coarse, warn));
    }

    private static WalkSettings.CoarseSettings coarse(ConfigLoader.CoarseSection c, Consumer<String> warn) {
        WalkSettings.CoarseSettings d = WalkSettings.CoarseSettings.DEFAULTS;
        return new WalkSettings.CoarseSettings(
                c.enabled,
                intIn(warn, "coarse.build-chunks-per-tick", c.buildChunksPerTick, 1, 32, d.buildChunksPerTick()),
                intIn(warn, "coarse.max-expansions", c.maxExpansions, 1_000, 1_000_000, d.maxExpansions()));
    }

    private static int intIn(Consumer<String> warn, String key, int value, int min, int max, int fallback) {
        if (value < min || value > max) {
            warn.accept(String.format(Locale.ROOT, "%s%s must be between %d and %d; using %d", PREFIX, key, min, max, fallback));
            return fallback;
        }
        return value;
    }

    private static long longIn(Consumer<String> warn, String key, long value, long min, long max, long fallback) {
        if (value < min || value > max) {
            warn.accept(String.format(Locale.ROOT, "%s%s must be between %d and %d; using %d", PREFIX, key, min, max, fallback));
            return fallback;
        }
        return value;
    }

    private static double doubleIn(Consumer<String> warn, String key, double value, double min, double max, double fallback) {
        if (!(value >= min && value <= max)) {
            warn.accept(String.format(Locale.ROOT, "%s%s must be between %s and %s; using %s", PREFIX, key, min, max, fallback));
            return fallback;
        }
        return value;
    }

    private static ParticleSpec particle(ConfigLoader.ParticleSection section, ParticleSpec fallback) {
        if (section == null || section.type == null || section.type.isBlank()) {
            return fallback;
        }
        return new ParticleSpec(section.type.trim(), section.color == null ? "" : section.color.trim(), section.size);
    }
}
