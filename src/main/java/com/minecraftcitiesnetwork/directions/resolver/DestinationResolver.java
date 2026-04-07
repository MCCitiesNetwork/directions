package com.minecraftcitiesnetwork.directions.resolver;

import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

public class DestinationResolver {
    public @NotNull Destination resolve(@NotNull ProtectedRegion region) {
        BlockVector3 min = region.getMinimumPoint();
        BlockVector3 max = region.getMaximumPoint();
        boolean isFlat = min.y() == -64 && max.y() == 319;

        double cx = (min.x() + max.x()) / 2.0;
        double cy = (min.y() + max.y()) / 2.0;
        double cz = (min.z() + max.z()) / 2.0;

        ProtectedRegion navTarget = region;
        @Nullable String floorNote = null;
        if (!isFlat && region.getParent() != null) {
            // Parent is only used as a broader navigation anchor.
            // Callers should still complete on requestedRegionId.
            navTarget = region.getParent();
            floorNote = "Note: destination is inside '" + region.getId() + "' (y ~= " + Math.round(cy) + ")";
        } else if (!isFlat) {
            floorNote = "Note: destination y ~= " + Math.round(cy);
        }
        return new Destination(region.getId(), Objects.requireNonNull(navTarget).getId(), cx, cy, cz, isFlat, floorNote);
    }

    public record Destination(
            @NotNull String requestedRegionId,
            @NotNull String navigationRegionId,
            double x,
            double y,
            double z,
            boolean flat,
            @Nullable String floorNote
    ) {
    }
}
