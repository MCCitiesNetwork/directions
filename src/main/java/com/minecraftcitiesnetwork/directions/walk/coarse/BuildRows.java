package com.minecraftcitiesnetwork.directions.walk.coarse;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Which rows of a world build are complete. Regions are opened in build order with all their chunks
 * to do; each finished chunk (built or skipped) is reported once. A row is complete when all its
 * chunks have finished and so have all rows before it, so saved progress never skips a gap.
 */
public final class BuildRows {
    private static final int REGION_WIDTH = 32;

    /** Outstanding chunks per row, keyed by the region's opening order * 32 + row. */
    private final TreeMap<Integer, Integer> outstanding = new TreeMap<>();
    private final List<ChunkPos> opened = new ArrayList<>();
    private final Map<ChunkPos, Integer> order = new HashMap<>();

    /** Opens the next region in build order, with the chunks of it still to do. */
    public void open(int regionX, int regionZ, List<ChunkPos> chunks) {
        ChunkPos region = new ChunkPos(regionX, regionZ);
        int index = opened.size();
        opened.add(region);
        order.put(region, index);
        for (ChunkPos chunk : chunks) {
            outstanding.merge(key(index, chunk), 1, Integer::sum);
        }
    }

    /**
     * Records a finished chunk of an opened region. Returns the progress to save when that completed
     * one or more rows (row 31 once the region has no rows left), or null when nothing new is complete.
     */
    public BuildProgress finish(ChunkPos chunk) {
        Integer index = order.get(new ChunkPos(Math.floorDiv(chunk.x(), REGION_WIDTH), Math.floorDiv(chunk.z(), REGION_WIDTH)));
        if (index == null) {
            throw new IllegalArgumentException("Chunk " + chunk + " is not in an opened region");
        }
        outstanding.merge(key(index, chunk), -1, Integer::sum);
        BuildProgress advanced = null;
        while (!outstanding.isEmpty() && outstanding.firstEntry().getValue() == 0) {
            int key = outstanding.pollFirstEntry().getKey();
            int regionIndex = key / REGION_WIDTH;
            boolean regionDone = outstanding.subMap(regionIndex * REGION_WIDTH, (regionIndex + 1) * REGION_WIDTH).isEmpty();
            ChunkPos region = opened.get(regionIndex);
            advanced = new BuildProgress(region.x(), region.z(), regionDone ? REGION_WIDTH - 1 : key % REGION_WIDTH, false);
        }
        return advanced;
    }

    private static int key(int regionIndex, ChunkPos chunk) {
        return regionIndex * REGION_WIDTH + Math.floorMod(chunk.z(), REGION_WIDTH);
    }
}
