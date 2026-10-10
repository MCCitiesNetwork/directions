package com.minecraftcitiesnetwork.directions.walk.coarse;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

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

    /**
     * The west and north neighbours of the chunks to do that an earlier, interrupted build already
     * built, in the order the chunks are done. A resumed build decodes these first (without changing
     * the graph) so its first chunks link across the point where the earlier build stopped.
     */
    public static List<ChunkPos> builtNeighbours(List<ChunkPos> toDo, Predicate<ChunkPos> builtBefore) {
        Set<ChunkPos> neighbours = new LinkedHashSet<>();
        for (ChunkPos chunk : toDo) {
            ChunkPos west = new ChunkPos(chunk.x() - 1, chunk.z());
            ChunkPos north = new ChunkPos(chunk.x(), chunk.z() - 1);
            if (builtBefore.test(west)) {
                neighbours.add(west);
            }
            if (builtBefore.test(north)) {
                neighbours.add(north);
            }
        }
        return List.copyOf(neighbours);
    }
}
