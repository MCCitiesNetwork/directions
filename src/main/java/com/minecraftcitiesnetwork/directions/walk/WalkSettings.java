package com.minecraftcitiesnetwork.directions.walk;

/** Everything under {@code walking-paths:} in config.yml, validated. Immutable. */
public record WalkSettings(
        boolean enabled,
        int windowRadius,
        long timeBudgetMillis,
        int maxExpansions,
        int snapshotsPerTick,
        long captureWaitMillis,
        int cacheMaxChunks,
        int maxDrop,
        double waterCost,
        double climbCost,
        double doorCost,
        double extendDistance,
        double offRouteDistance,
        long replanCooldownMillis,
        int trailLength,
        int trailRefreshTicks,
        int pulseStep,
        double arrowLookAhead,
        ParticleSpec trailParticle,
        ParticleSpec pulseParticle,
        ParticleSpec endParticle
) {
    public static final WalkSettings DEFAULTS = new WalkSettings(
            true, 80, 50, 200_000, 6, 1500, 4096, 3, 2.5, 2.0, 1.5, 48, 6, 2000, 40, 10, 4, 6,
            new ParticleSpec("DUST", "#5BC0EB", 1.0),
            new ParticleSpec("DUST", "#FFFFFF", 1.4),
            new ParticleSpec("HAPPY_VILLAGER", "", 1.0));

    public WalkerProfile walker() {
        return new WalkerProfile(maxDrop, waterCost, climbCost, doorCost);
    }

    public GuidePolicy.Tuning tuning() {
        return new GuidePolicy.Tuning(extendDistance, offRouteDistance, replanCooldownMillis);
    }
}
