package com.minecraftcitiesnetwork.directions.resolver;

import com.sk89q.worldedit.math.BlockVector2;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import org.jetbrains.annotations.NotNull;

import java.util.List;

public class DestinationResolver {
    public @NotNull Destination resolve(@NotNull ProtectedRegion region) {
        BlockVector3 min = region.getMinimumPoint();
        BlockVector3 max = region.getMaximumPoint();

        // Centroid of the footprint vertices. For a cuboid getPoints() returns its 4 corners, so this is
        // identical to the bounding-box midpoint; for polygons it stays inside the shape instead of the
        // AABB midpoint, which can fall outside a concave region.
        List<BlockVector2> points = region.getPoints();
        double sumX = 0;
        double sumZ = 0;
        for (BlockVector2 p : points) {
            sumX += p.x();
            sumZ += p.z();
        }
        double cx = sumX / points.size();
        double cz = sumZ / points.size();
        double cy = (min.y() + max.y()) / 2.0;

        return new Destination(region.getId(), cx, cy, cz);
    }

    public record Destination(
            @NotNull String requestedRegionId,
            double x,
            double y,
            double z
    ) {
    }
}
