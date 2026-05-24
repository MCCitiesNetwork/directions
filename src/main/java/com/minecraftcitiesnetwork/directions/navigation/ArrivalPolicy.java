package com.minecraftcitiesnetwork.directions.navigation;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import org.bukkit.Location;
import org.bukkit.entity.Player;

final class ArrivalPolicy {
    private ArrivalPolicy() {
    }

    static boolean shouldAdvanceAtWaypoint(
            SessionView session,
            Player player,
            RegionManager regionManager,
            Waypoint waypoint,
            boolean isConfiguredStop,
            double stopArrivalBuffer,
            double coordinateArrivalRadius,
            double departureClearanceRadius
    ) {
        return switch (waypoint) {
            case Waypoint.Coordinates coords -> {
                if (!isWithinCoordinateRadius(player.getLocation(), coords, coordinateArrivalRadius)) {
                    yield false;
                }
                yield isClearOfPreviousWaypoint(session, player, regionManager, departureClearanceRadius);
            }
            case Waypoint.Region region -> {
                double buffer = isConfiguredStop ? stopArrivalBuffer : 0.0;
                if (!isInsideRegion(player, regionManager, region.id(), buffer)) {
                    yield false;
                }
                yield isClearOfPreviousWaypoint(session, player, regionManager, departureClearanceRadius);
            }
        };
    }

    static boolean isInsideRegion(Player player, RegionManager regionManager, String regionId, double buffer) {
        ProtectedRegion target = regionManager.getRegion(regionId);
        if (target == null) {
            return false;
        }
        boolean inside = regionManager.getApplicableRegions(BukkitAdapter.asBlockVector(player.getLocation()))
                .getRegions()
                .stream()
                .anyMatch(r -> r.getId().equalsIgnoreCase(regionId));
        if (inside) {
            return true;
        }
        if (buffer <= 0.0) {
            return false;
        }
        double bufferSq = buffer * buffer;
        return distanceSquaredToRegion(player.getLocation(), target, false) <= bufferSq;
    }

    static double distanceToRegion(Location from, ProtectedRegion region, boolean includeY) {
        return Math.sqrt(distanceSquaredToRegion(from, region, includeY));
    }

    static double distanceSquaredToRegion(Location from, ProtectedRegion region, boolean includeY) {
        double x = from.getX();
        double y = from.getY();
        double z = from.getZ();
        double minX = region.getMinimumPoint().x();
        double maxX = region.getMaximumPoint().x();
        double minY = region.getMinimumPoint().y();
        double maxY = region.getMaximumPoint().y();
        double minZ = region.getMinimumPoint().z();
        double maxZ = region.getMaximumPoint().z();

        double dx = axisDistance(x, minX, maxX);
        double dy = axisDistance(y, minY, maxY);
        double dz = axisDistance(z, minZ, maxZ);
        return includeY
                ? (dx * dx + dy * dy + dz * dz)
                : (dx * dx + dz * dz);
    }

    static double distanceToCoordinate(Location from, Waypoint.Coordinates target) {
        return Math.sqrt(distanceSquaredToCoordinate(from, target));
    }

    static double distanceSquaredToCoordinate(Location from, Waypoint.Coordinates target) {
        return target.distanceSquared(from);
    }

    static boolean isWithinCoordinateRadius(Location from, Waypoint.Coordinates target, double radius) {
        return target.isWithinRadius(from, radius);
    }

    private static double axisDistance(double value, double min, double max) {
        if (value < min) {
            return min - value;
        }
        if (value > max) {
            return value - max;
        }
        return 0.0;
    }

    static boolean isClearOfPreviousWaypoint(
            SessionView session,
            Player player,
            RegionManager regionManager,
            double departureClearanceRadius
    ) {
        Waypoint previous = session.previousWaypoint();
        if (previous == null) {
            return true;
        }
        if (departureClearanceRadius <= 0.0) {
            return true;
        }
        double clearanceSq = departureClearanceRadius * departureClearanceRadius;
        return switch (previous) {
            case Waypoint.Region region -> {
                ProtectedRegion previousRegion = regionManager.getRegion(region.id());
                if (previousRegion == null) {
                    yield true;
                }
                yield distanceSquaredToRegion(player.getLocation(), previousRegion, false) > clearanceSq;
            }
            case Waypoint.Coordinates coords ->
                    distanceSquaredToCoordinate(player.getLocation(), coords) > clearanceSq;
        };
    }
}
