package com.minecraftcitiesnetwork.directions.pathfinding;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Everything under {@code pathfinding:} in config.yml, validated. Immutable so a reload swaps the
 * whole object at once.
 */
public record PathfindingSettings(
        boolean enabled,
        Set<String> worlds,
        Routing routing,
        Guidance guidance,
        WalkerProfile walker,
        Engine engine,
        Chunks chunks,
        Debug debug
) {
    public record Routing(boolean enabled, double maxLegDistance, Duration timeBudget, boolean precomputeStopEdges) {
    }

    public record Guidance(boolean enabled, double lookAhead, double replanDistance, Duration replanCooldown,
                           double maxSearchDistance, Duration timeBudget) {
    }

    public record Engine(int threads, int maxIterations) {
    }

    public record Chunks(int snapshotsPerTick, boolean diskReader, int flagBandHeight, int cacheMaxChunks,
                         Duration cacheIdle, Duration unloadedRetry) {
    }

    public record Debug(boolean particles, boolean logSearches) {
    }

    public static final PathfindingSettings DISABLED = new PathfindingSettings(
            false,
            Set.of(),
            new Routing(true, 300, Duration.ofMillis(250), true),
            new Guidance(true, 6, 8, Duration.ofMillis(2000), 600, Duration.ofMillis(400)),
            WalkerProfile.DEFAULT,
            new Engine(2, 400_000),
            new Chunks(8, true, 64, 2048, Duration.ofSeconds(90), Duration.ofSeconds(60)),
            new Debug(false, false)
    );

    public PathfindingSettings {
        worlds = Set.copyOf(worlds.stream().map(w -> w.toLowerCase(Locale.ROOT)).toList());
        if (routing.maxLegDistance() < 0 || guidance.maxSearchDistance() < 0) {
            throw new IllegalArgumentException("pathfinding distances must not be negative");
        }
        if (routing.timeBudget().isNegative() || routing.timeBudget().isZero()
                || guidance.timeBudget().isNegative() || guidance.timeBudget().isZero()) {
            throw new IllegalArgumentException("pathfinding time budgets must be positive");
        }
        if (guidance.lookAhead() <= 0 || guidance.replanDistance() <= 0) {
            throw new IllegalArgumentException("guidance look-ahead and replan-distance must be positive");
        }
        if (engine.threads() < 1 || engine.threads() > 8) {
            throw new IllegalArgumentException("engine.threads must be between 1 and 8");
        }
        if (engine.maxIterations() < 1000) {
            throw new IllegalArgumentException("engine.max-iterations must be at least 1000");
        }
        if (chunks.snapshotsPerTick() < 1 || chunks.flagBandHeight() < 8 || chunks.cacheMaxChunks() < 16) {
            throw new IllegalArgumentException("chunks settings out of range");
        }
    }

    public boolean enabledIn(String worldName) {
        return enabled && (worlds.isEmpty() || worlds.contains(worldName.toLowerCase(Locale.ROOT)));
    }

    public boolean routingEnabledIn(String worldName) {
        return enabledIn(worldName) && routing.enabled();
    }

    public boolean guidanceEnabledIn(String worldName) {
        return enabledIn(worldName) && guidance.enabled();
    }

    public static PathfindingSettings withEnabled(PathfindingSettings base, boolean enabled, List<String> worlds) {
        return new PathfindingSettings(enabled, Set.copyOf(worlds), base.routing, base.guidance, base.walker, base.engine, base.chunks, base.debug);
    }
}
