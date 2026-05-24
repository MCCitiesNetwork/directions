package com.minecraftcitiesnetwork.directions.navigation;

import com.minecraftcitiesnetwork.directions.config.ConfigLoader;
import org.bukkit.Location;
import org.jetbrains.annotations.NotNull;

public sealed interface Waypoint permits Waypoint.Region, Waypoint.Coordinates {

    @NotNull String displayLabel(@NotNull ConfigLoader.LoadedData loadedData);

    record Region(@NotNull String id) implements Waypoint {
        @Override
        public @NotNull String displayLabel(@NotNull ConfigLoader.LoadedData loadedData) {
            if (loadedData.stopsById().containsKey(id)) {
                return loadedData.displayStop(id);
            }
            return id;
        }
    }

    record Coordinates(double x, double y, double z, boolean includeY) implements Waypoint {
        @Override
        public @NotNull String displayLabel(@NotNull ConfigLoader.LoadedData loadedData) {
            if (includeY) {
                return format(x) + ", " + format(y) + ", " + format(z);
            }
            return format(x) + ", " + format(z);
        }

        public boolean isWithinRadius(@NotNull Location from, double radius) {
            return isWithinRadius(from.getX(), from.getY(), from.getZ(), radius);
        }

        public double distanceSquared(@NotNull Location from) {
            return distanceSquared(from.getX(), from.getY(), from.getZ());
        }

        public double distanceSquared(double fromX, double fromY, double fromZ) {
            double dx = fromX - x;
            double dz = fromZ - z;
            if (includeY) {
                double dy = fromY - y;
                return dx * dx + dy * dy + dz * dz;
            }
            return dx * dx + dz * dz;
        }

        public boolean isWithinRadius(double fromX, double fromY, double fromZ, double radius) {
            double distSq = distanceSquared(fromX, fromY, fromZ);
            if (radius <= 0.0) {
                return distSq <= 0.0001;
            }
            return distSq <= radius * radius;
        }

        private static String format(double value) {
            if (Math.rint(value) == value) {
                return String.valueOf((long) value);
            }
            return String.format("%.1f", value);
        }
    }
}
