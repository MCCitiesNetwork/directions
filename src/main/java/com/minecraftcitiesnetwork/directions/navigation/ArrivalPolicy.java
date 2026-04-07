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
            String waypointRegionId,
            boolean isConfiguredStop,
            double stopArrivalBuffer,
            double departureClearanceRadius
    ) {
        double buffer = isConfiguredStop ? stopArrivalBuffer : 0.0;
        if (!isInsideRegion(player, regionManager, waypointRegionId, buffer)) {
            return false;
        }
        return isClearOfPreviousStop(session, player, regionManager, departureClearanceRadius);
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

    private static double axisDistance(double value, double min, double max) {
        if (value < min) {
            return min - value;
        }
        if (value > max) {
            return value - max;
        }
        return 0.0;
    }

    static boolean isClearOfPreviousStop(
            SessionView session,
            Player player,
            RegionManager regionManager,
            double departureClearanceRadius
    ) {
        String previous = session.previousWaypoint();
        if (previous == null) {
            return true;
        }
        ProtectedRegion previousRegion = regionManager.getRegion(previous);
        if (previousRegion == null) {
            return true;
        }
        if (departureClearanceRadius <= 0.0) {
            return true;
        }
        double clearanceSq = departureClearanceRadius * departureClearanceRadius;
        return distanceSquaredToRegion(player.getLocation(), previousRegion, false) > clearanceSq;
    }
}
