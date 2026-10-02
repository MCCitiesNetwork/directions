package com.minecraftcitiesnetwork.directions.walk;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Decoded chunks for every world, shared by all players. Writers (decode threads) and readers
 * (searches, the main thread) may run concurrently. Holds at most {@code maxChunks}; beyond that
 * the least recently read are dropped, down to 90% of the limit.
 */
public final class ColumnStore {
    private record Key(UUID world, int x, int z) {
    }

    private static final class Entry {
        final ColumnChunk chunk;
        volatile long lastAccess;

        Entry(ColumnChunk chunk, long now) {
            this.chunk = chunk;
            this.lastAccess = now;
        }
    }

    private record Aged(Key key, Entry entry, long lastAccess) {
    }

    private final ConcurrentHashMap<Key, Entry> entries = new ConcurrentHashMap<>();
    private final int maxChunks;
    private final LongSupplier clock;

    public ColumnStore(int maxChunks, LongSupplier millisClock) {
        this.maxChunks = maxChunks;
        this.clock = millisClock;
    }

    public void put(UUID world, ColumnChunk chunk) {
        entries.put(new Key(world, chunk.chunkX(), chunk.chunkZ()), new Entry(chunk, clock.getAsLong()));
        if (entries.size() > maxChunks) {
            trim();
        }
    }

    public boolean contains(UUID world, int chunkX, int chunkZ) {
        return entries.containsKey(new Key(world, chunkX, chunkZ));
    }

    public void remove(UUID world, int chunkX, int chunkZ) {
        entries.remove(new Key(world, chunkX, chunkZ));
    }

    public void clear() {
        entries.clear();
    }

    public int size() {
        return entries.size();
    }

    /** A view for one thread. It remembers the last chunk it read, so make a new one per search. */
    public Terrain terrain(UUID world) {
        return new View(world);
    }

    private synchronized void trim() {
        if (entries.size() <= maxChunks) {
            return;
        }
        List<Aged> all = new ArrayList<>(entries.size());
        entries.forEach((key, entry) -> all.add(new Aged(key, entry, entry.lastAccess)));
        all.sort(Comparator.comparingLong(Aged::lastAccess));
        int target = maxChunks * 9 / 10;
        for (int i = 0; i < all.size() - target; i++) {
            entries.remove(all.get(i).key(), all.get(i).entry());
        }
    }

    private final class View implements Terrain {
        private final UUID world;
        private int lastX = Integer.MIN_VALUE;
        private int lastZ = Integer.MIN_VALUE;
        private ColumnChunk last;

        View(UUID world) {
            this.world = world;
        }

        @Override
        public int[] surfaces(int x, int z) {
            int cx = x >> 4;
            int cz = z >> 4;
            if (cx != lastX || cz != lastZ) {
                Entry entry = entries.get(new Key(world, cx, cz));
                lastX = cx;
                lastZ = cz;
                last = entry == null ? null : entry.chunk;
                if (entry != null) {
                    entry.lastAccess = clock.getAsLong();
                }
            }
            return last == null ? null : last.column(x & 15, z & 15);
        }
    }
}
