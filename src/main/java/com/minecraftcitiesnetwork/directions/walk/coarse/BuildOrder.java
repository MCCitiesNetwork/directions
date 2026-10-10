package com.minecraftcitiesnetwork.directions.walk.coarse;

import java.util.ArrayList;
import java.util.List;

/**
 * Which chunks of a region a world build still has to do. Regions are built in order of
 * (region x, region z), and each region row by row.
 */
public final class BuildOrder {
    private static final int REGION_WIDTH = 32;

    private BuildOrder() {
    }

    /**
     * The chunks of region ({@code regionX}, {@code regionZ}) still to do, given saved progress:
     * none for a region before the saved one (or when the build is done), the rows after
     * {@code progress.row()} for the saved region, and all of them for a later region.
     */
    public static List<ChunkPos> after(List<ChunkPos> chunksOfRegion, BuildProgress progress, int regionX, int regionZ) {
        if (progress.done()) {
            return List.of();
        }
        if (progress.equals(BuildProgress.NONE)) {
            return List.copyOf(chunksOfRegion);
        }
        int order = regionX != progress.regionX()
                ? Integer.compare(regionX, progress.regionX())
                : Integer.compare(regionZ, progress.regionZ());
        if (order < 0) {
            return List.of();
        }
        if (order > 0) {
            return List.copyOf(chunksOfRegion);
        }
        List<ChunkPos> left = new ArrayList<>();
        for (ChunkPos chunk : chunksOfRegion) {
            if (Math.floorMod(chunk.z(), REGION_WIDTH) > progress.row()) {
                left.add(chunk);
            }
        }
        return left;
    }
}
