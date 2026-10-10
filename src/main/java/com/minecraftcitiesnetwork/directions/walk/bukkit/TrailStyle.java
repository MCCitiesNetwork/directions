package com.minecraftcitiesnetwork.directions.walk.bukkit;

import com.minecraftcitiesnetwork.directions.walk.WalkSettings;

import java.util.logging.Logger;

/** The three particles of a trail, resolved once per config load. */
public record TrailStyle(ParticleStyle trail, ParticleStyle pulse, ParticleStyle end) {
    public static TrailStyle from(WalkSettings settings, Logger log) {
        WalkSettings d = WalkSettings.DEFAULTS;
        return new TrailStyle(
                ParticleStyle.parse("trail", settings.trailParticle(), d.trailParticle(), log),
                ParticleStyle.parse("pulse", settings.pulseParticle(), d.pulseParticle(), log),
                ParticleStyle.parse("end", settings.endParticle(), d.endParticle(), log));
    }
}
