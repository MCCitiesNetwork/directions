package com.minecraftcitiesnetwork.directions.pathfinding.bukkit;

import com.minecraftcitiesnetwork.directions.pathfinding.BlockFlags;
import com.minecraftcitiesnetwork.directions.pathfinding.BlockGrid;
import com.minecraftcitiesnetwork.directions.pathfinding.grid.ChunkFlags;
import org.bukkit.World;

import java.util.HashMap;
import java.util.Map;

/**
 * A {@link BlockGrid} over one world for one search. Reads go through a per-search map of chunks
 * already resolved, then the shared cache. Chunks that cannot be had before the deadline read as
 * unloaded, which the walker treats as walls.
 */
public final class CachedBlockGrid implements BlockGrid {
    private final World world;
    private final ChunkFlagCache cache;
    private final long deadlineNanos;
    private final int minY;
    private final int maxY;
    private final Map<Long, ChunkFlags> local = new HashMap<>();
    private long lastKey = Long.MIN_VALUE;
    private ChunkFlags lastChunk;

    public CachedBlockGrid(World world, ChunkFlagCache cache, long deadlineNanos) {
        this.world = world;
        this.cache = cache;
        this.deadlineNanos = deadlineNanos;
        this.minY = world.getMinHeight();
        this.maxY = world.getMaxHeight() - 1;
    }

    @Override
    public byte flags(int x, int y, int z) {
        if (y < minY) {
            return BlockFlags.SOLID;
        }
        if (y > maxY) {
            return BlockFlags.PASSABLE;
        }
        int cx = x >> 4;
        int cz = z >> 4;
        long key = ChunkFlagCache.key(cx, cz);
        ChunkFlags chunk;
        if (key == lastKey) {
            chunk = lastChunk;
        } else {
            chunk = local.get(key);
            if (chunk == null) {
                chunk = cache.resolve(world, cx, cz, deadlineNanos);
                if (!chunk.isUnloaded()) {
                    local.put(key, chunk);
                }
            }
            lastKey = key;
            lastChunk = chunk;
        }
        return chunk.flags(x & 15, y, z & 15);
    }

    @Override
    public int minY() {
        return minY;
    }

    @Override
    public int maxY() {
        return maxY;
    }
}
