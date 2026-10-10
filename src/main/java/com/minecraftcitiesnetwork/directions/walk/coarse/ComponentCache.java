package com.minecraftcitiesnetwork.directions.walk.coarse;

import com.minecraftcitiesnetwork.directions.walk.Terrain;
import com.minecraftcitiesnetwork.directions.walk.WalkRules;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The components of recently linked chunks, so that linking a chunk does not flood each of its
 * neighbours again. One cache serves one world and one set of walking rules; a chunk whose terrain
 * changes must be invalidated. Thread-safe; a miss is computed outside the lock.
 */
public final class ComponentCache {
    public static final int DEFAULT_CAPACITY = 512;

    private final Map<Long, List<Component>> entries;

    public ComponentCache() {
        this(DEFAULT_CAPACITY);
    }

    public ComponentCache(int capacity) {
        this.entries = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Long, List<Component>> eldest) {
                return size() > capacity;
            }
        };
    }

    /** The chunk's components, from the cache or computed with {@link ChunkComponents#of}. */
    public List<Component> of(Terrain terrain, int chunkX, int chunkZ, WalkRules rules) {
        long key = ChunkKeys.key(chunkX, chunkZ);
        synchronized (entries) {
            List<Component> cached = entries.get(key);
            if (cached != null) {
                return cached;
            }
        }
        List<Component> computed = List.copyOf(ChunkComponents.of(terrain, chunkX, chunkZ, rules));
        synchronized (entries) {
            entries.put(key, computed);
        }
        return computed;
    }

    /** Forgets the chunk, whose terrain has changed. */
    public void invalidate(int chunkX, int chunkZ) {
        synchronized (entries) {
            entries.remove(ChunkKeys.key(chunkX, chunkZ));
        }
    }

    public int size() {
        synchronized (entries) {
            return entries.size();
        }
    }
}
