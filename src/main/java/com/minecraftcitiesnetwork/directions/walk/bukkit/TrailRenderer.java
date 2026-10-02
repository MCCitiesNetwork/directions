package com.minecraftcitiesnetwork.directions.walk.bukkit;

import com.minecraftcitiesnetwork.directions.walk.Route;
import com.minecraftcitiesnetwork.directions.walk.WalkSettings;
import org.bukkit.entity.Player;

/**
 * Draws the next stretch of a guide's route for its player only: one particle per cell, a brighter
 * pulse travelling toward the goal, and a marker at the end of a complete route.
 */
public final class TrailRenderer {
    private static final double HEIGHT = 0.2;
    private static final int END_COUNT = 6;

    private final TrailStyle style;
    private final WalkSettings settings;

    public TrailRenderer(TrailStyle style, WalkSettings settings) {
        this.style = style;
        this.settings = settings;
    }

    /** Call every tick; draws every {@code trail-refresh-ticks}. */
    public void render(Player player, WalkGuide guide, long tick) {
        if (tick % settings.trailRefreshTicks() != 0) {
            return;
        }
        Route route = guide.visibleRoute();
        if (route == null) {
            return;
        }
        int from = guide.cursor();
        int to = Math.min(route.size(), from + settings.trailLength());
        int count = to - from;
        if (count <= 0) {
            return;
        }
        long frame = tick / settings.trailRefreshTicks();
        int pulse = from + (int) ((frame * settings.pulseStep()) % count);
        for (int i = from; i < to; i++) {
            ParticleStyle particle = i == pulse ? style.pulse() : style.trail();
            particle.spawn(player, route.x(i) + 0.5, route.y(i) + HEIGHT, route.z(i) + 0.5, 1);
        }
        int last = route.size() - 1;
        if (route.complete() && last < to) {
            style.end().spawn(player, route.x(last) + 0.5, route.y(last) + 0.5, route.z(last) + 0.5, END_COUNT);
        }
    }
}
