package com.minecraftcitiesnetwork.directions.walk;

/** A particle as configured: a Bukkit particle name, a {@code #RRGGBB} colour for dust, and a dust size. */
public record ParticleSpec(String type, String color, double size) {
}
