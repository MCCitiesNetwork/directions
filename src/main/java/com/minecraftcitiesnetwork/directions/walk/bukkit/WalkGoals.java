package com.minecraftcitiesnetwork.directions.walk.bukkit;

import com.minecraftcitiesnetwork.directions.config.ConfigLoader;
import com.minecraftcitiesnetwork.directions.navigation.Waypoint;
import com.minecraftcitiesnetwork.directions.walk.BoxGoal;
import com.minecraftcitiesnetwork.directions.walk.Goal;
import com.minecraftcitiesnetwork.directions.walk.PointGoal;
import com.minecraftcitiesnetwork.directions.walk.PolygonGoal;
import com.minecraftcitiesnetwork.directions.walk.StopAreaGoal;
import com.sk89q.worldedit.math.BlockVector2;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.ProtectedPolygonalRegion;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * The goal for a walking leg, built to match {@code ArrivalPolicy} so the trail ends where the leg
 * counts as done: configured stops include the arrival buffer when there is one, other regions (and
 * stops without a buffer) are their exact shape, coordinates use the coordinate arrival radius.
 * Main thread (reads WorldGuard).
 */
public final class WalkGoals {
    private WalkGoals() {
    }

    public static @Nullable Goal forWaypoint(Waypoint waypoint, RegionManager regions, ConfigLoader.LoadedData data) {
        return switch (waypoint) {
            case Waypoint.Coordinates c -> new PointGoal(c.x(), c.y(), c.z(), data.coordinateArrivalRadius(), c.includeY());
            case Waypoint.Region r -> forRegion(r.id(), regions, data);
        };
    }

    private static @Nullable Goal forRegion(String id, RegionManager regions, ConfigLoader.LoadedData data) {
        ProtectedRegion region = regions.getRegion(id);
        if (region == null) {
            return null;
        }
        BlockVector3 min = region.getMinimumPoint();
        BlockVector3 max = region.getMaximumPoint();
        if (data.stopArrivalBuffer() > 0 && data.stopsById().containsKey(id)) {
            return new StopAreaGoal(min.x(), min.z(), max.x(), max.z(), data.stopArrivalBuffer());
        }
        if (region instanceof ProtectedPolygonalRegion polygon) {
            List<BlockVector2> points = polygon.getPoints();
            int[] xs = new int[points.size()];
            int[] zs = new int[points.size()];
            for (int i = 0; i < points.size(); i++) {
                xs[i] = points.get(i).x();
                zs[i] = points.get(i).z();
            }
            return new PolygonGoal(xs, zs, min.y(), max.y());
        }
        return new BoxGoal(min.x(), min.y(), min.z(), max.x(), max.y(), max.z());
    }
}
