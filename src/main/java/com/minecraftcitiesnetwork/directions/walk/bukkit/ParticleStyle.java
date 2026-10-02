package com.minecraftcitiesnetwork.directions.walk.bukkit;

import com.minecraftcitiesnetwork.directions.walk.ParticleSpec;
import org.bukkit.Color;
import org.bukkit.Particle;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.logging.Logger;

/** A resolved particle: its type and, for dust, its colour and size. */
public record ParticleStyle(Particle particle, @Nullable Object data) {

    /** Shown to this player only. */
    public void spawn(Player player, double x, double y, double z, int count) {
        player.spawnParticle(particle, x, y, z, count, 0, 0, 0, 0, data);
    }

    static ParticleStyle parse(String name, ParticleSpec spec, ParticleSpec fallback, Logger log) {
        try {
            return of(spec);
        } catch (IllegalArgumentException e) {
            log.warning("walking-paths.particles." + name + ": " + e.getMessage() + "; using the default");
            return of(fallback);
        }
    }

    static ParticleStyle of(ParticleSpec spec) {
        Particle particle = Particle.valueOf(spec.type().trim().toUpperCase(Locale.ROOT));
        Class<?> dataType = particle.getDataType();
        if (dataType == Void.class) {
            return new ParticleStyle(particle, null);
        }
        if (dataType == Particle.DustOptions.class) {
            String hex = spec.color().trim().replace("#", "");
            if (hex.length() != 6) {
                throw new IllegalArgumentException("color must be #RRGGBB, got '" + spec.color() + "'");
            }
            if (spec.size() <= 0) {
                throw new IllegalArgumentException("size must be positive");
            }
            return new ParticleStyle(particle, new Particle.DustOptions(Color.fromRGB(Integer.parseInt(hex, 16)), (float) spec.size()));
        }
        throw new IllegalArgumentException(particle + " needs data this plugin cannot supply");
    }
}
