package com.minecraftcitiesnetwork.directions.pathfinding.bukkit;

import com.minecraftcitiesnetwork.directions.pathfinding.PathfindingSettings;
import com.minecraftcitiesnetwork.directions.pathfinding.grid.ChunkFlags;
import com.minecraftcitiesnetwork.directions.pathfinding.grid.RegionFileReader;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Material;
import org.bukkit.World;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Per-world cache of decoded chunk flags, resolved through three tiers: loaded chunks are
 * snapshotted on the main thread by the {@link SnapshotPump}; generated-but-unloaded chunks are
 * read from region files by the {@link RegionFileReader}; anything else is {@link ChunkFlags#UNLOADED}
 * for a while. Directions never asks the server to load a chunk.
 */
public final class ChunkFlagCache {
    private final class Entry {
        final CompletableFuture<ChunkFlags> future = new CompletableFuture<>();
        volatile long lastAccessNanos = System.nanoTime();
        volatile long unloadedUntilNanos;

        ChunkFlags now() {
            return future.getNow(null);
        }
    }

    private final class WorldCache {
        final World world;
        final Map<Long, Entry> entries = new ConcurrentHashMap<>();
        final SnapshotDecoder decoder;
        final RegionFileReader reader;

        WorldCache(World world) {
            this.world = world;
            this.decoder = new SnapshotDecoder(materials, world.getMinHeight(), world.getMaxHeight() - 1, settings.chunks().flagBandHeight());
            this.reader = settings.chunks().diskReader()
                    ? new RegionFileReader(regionDirectory(world), materials.classifier(), world.getMinHeight(), world.getMaxHeight() - 1, settings.chunks().flagBandHeight())
                    : null;
        }
    }

    private final PathfindingSettings settings;
    private final MaterialFlags materials;
    private final SnapshotPump pump;
    private final Map<UUID, WorldCache> worlds = new ConcurrentHashMap<>();
    private final AtomicLong hits = new AtomicLong();
    private final AtomicLong snapshotLoads = new AtomicLong();
    private final AtomicLong diskLoads = new AtomicLong();
    private final AtomicLong unloadedMisses = new AtomicLong();
    private final AtomicLong timeouts = new AtomicLong();

    public ChunkFlagCache(PathfindingSettings settings, MaterialFlags materials, SnapshotPump pump) {
        this.settings = settings;
        this.materials = materials;
        this.pump = pump;
    }

    public static long key(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
    }

    /**
     * Flags for a chunk, resolving through the tiers if needed. Blocks the calling search thread up
     * to {@code deadlineNanos}; returns {@link ChunkFlags#UNLOADED} on timeout or when unavailable.
     */
    public ChunkFlags resolve(World world, int chunkX, int chunkZ, long deadlineNanos) {
        WorldCache cache = worlds.computeIfAbsent(world.getUID(), id -> new WorldCache(world));
        long key = key(chunkX, chunkZ);
        Entry entry = cache.entries.get(key);
        long now = System.nanoTime();
        if (entry != null) {
            ChunkFlags ready = entry.now();
            if (ready != null) {
                if (ready.isUnloaded() && now >= entry.unloadedUntilNanos) {
                    cache.entries.remove(key, entry);
                    entry = null;
                } else {
                    entry.lastAccessNanos = now;
                    hits.incrementAndGet();
                    return ready;
                }
            }
        }
        if (entry == null) {
            Entry fresh = new Entry();
            Entry existing = cache.entries.putIfAbsent(key, fresh);
            if (existing == null) {
                load(cache, chunkX, chunkZ, fresh, deadlineNanos);
                entry = fresh;
            } else {
                entry = existing;
            }
        }
        try {
            long remaining = deadlineNanos - System.nanoTime();
            if (remaining <= 0) {
                timeouts.incrementAndGet();
                return ChunkFlags.UNLOADED;
            }
            ChunkFlags flags = entry.future.get(remaining, TimeUnit.NANOSECONDS);
            entry.lastAccessNanos = System.nanoTime();
            return flags;
        } catch (TimeoutException e) {
            timeouts.incrementAndGet();
            return ChunkFlags.UNLOADED;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ChunkFlags.UNLOADED;
        } catch (ExecutionException e) {
            return ChunkFlags.UNLOADED;
        }
    }

    /** Ask for a chunk without waiting; a later {@link #resolve} finds it ready. */
    public void prefetch(World world, int chunkX, int chunkZ) {
        WorldCache cache = worlds.computeIfAbsent(world.getUID(), id -> new WorldCache(world));
        long key = key(chunkX, chunkZ);
        if (cache.entries.containsKey(key)) {
            return;
        }
        Entry fresh = new Entry();
        if (cache.entries.putIfAbsent(key, fresh) == null) {
            // Snapshot tier only; the disk tier would block this thread, and the search thread
            // that eventually needs the chunk will do that itself.
            pump.request(world, chunkX, chunkZ).whenComplete((snapshot, error) -> {
                if (snapshot == null) {
                    cache.entries.remove(key, fresh);
                    fresh.future.complete(ChunkFlags.UNLOADED);
                } else {
                    snapshotLoads.incrementAndGet();
                    fresh.future.complete(cache.decoder.decode(snapshot));
                }
            });
        }
    }

    private void load(WorldCache cache, int chunkX, int chunkZ, Entry entry, long deadlineNanos) {
        try {
            ChunkFlags flags = null;
            if (cache.world.isChunkLoaded(chunkX, chunkZ)) {
                long remaining = deadlineNanos - System.nanoTime();
                if (remaining > 0) {
                    ChunkSnapshot snapshot = pump.request(cache.world, chunkX, chunkZ).get(remaining, TimeUnit.NANOSECONDS);
                    if (snapshot != null) {
                        flags = cache.decoder.decode(snapshot);
                        snapshotLoads.incrementAndGet();
                    }
                }
            }
            if (flags == null && cache.reader != null) {
                flags = cache.reader.read(chunkX, chunkZ);
                if (flags != null) {
                    diskLoads.incrementAndGet();
                }
            }
            if (flags == null) {
                flags = ChunkFlags.UNLOADED;
                unloadedMisses.incrementAndGet();
                entry.unloadedUntilNanos = System.nanoTime() + settings.chunks().unloadedRetry().toNanos();
            }
            entry.future.complete(flags);
        } catch (TimeoutException e) {
            // Leave the chunk unknown for this request but do not poison the cache: drop the entry
            // so the next request tries again.
            cache.entries.remove(key(chunkX, chunkZ), entry);
            entry.future.complete(ChunkFlags.UNLOADED);
            timeouts.incrementAndGet();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            cache.entries.remove(key(chunkX, chunkZ), entry);
            entry.future.complete(ChunkFlags.UNLOADED);
        } catch (Exception e) {
            cache.entries.remove(key(chunkX, chunkZ), entry);
            entry.future.completeExceptionally(e);
        }
    }

    // ---- invalidation (main thread) ----------------------------------------------------------

    /** A single block changed: patch it in place, or drop the chunk when the change is outside the band. */
    public void blockChanged(World world, int x, int y, int z, Material newType) {
        WorldCache cache = worlds.get(world.getUID());
        if (cache == null) {
            return;
        }
        long key = key(x >> 4, z >> 4);
        Entry entry = cache.entries.get(key);
        if (entry == null) {
            return;
        }
        ChunkFlags flags = entry.now();
        if (flags == null || flags.isUnloaded()) {
            return;
        }
        if (flags.covers(y)) {
            flags.set(x & 15, y, z & 15, materials.flags(newType));
        } else {
            cache.entries.remove(key, entry);
        }
    }

    public void chunkChanged(World world, int chunkX, int chunkZ) {
        WorldCache cache = worlds.get(world.getUID());
        if (cache != null) {
            cache.entries.remove(key(chunkX, chunkZ));
        }
    }

    /** Drops idle entries and enforces the size ceiling. Cheap; call every few seconds. */
    public void sweep() {
        long idleNanos = settings.chunks().cacheIdle().toNanos();
        long now = System.nanoTime();
        int max = settings.chunks().cacheMaxChunks();
        for (WorldCache cache : worlds.values()) {
            cache.entries.entrySet().removeIf(e -> {
                ChunkFlags f = e.getValue().now();
                return f != null && (now - e.getValue().lastAccessNanos > idleNanos || (f.isUnloaded() && now >= e.getValue().unloadedUntilNanos));
            });
            int over = cache.entries.size() - max;
            if (over > 0) {
                List<Map.Entry<Long, Entry>> byAge = new ArrayList<>(cache.entries.entrySet());
                byAge.sort(Comparator.comparingLong(e -> e.getValue().lastAccessNanos));
                for (int i = 0; i < over && i < byAge.size(); i++) {
                    cache.entries.remove(byAge.get(i).getKey(), byAge.get(i).getValue());
                }
            }
        }
    }

    public void clear() {
        worlds.clear();
    }

    public int cachedChunks() {
        int n = 0;
        for (WorldCache c : worlds.values()) {
            n += c.entries.size();
        }
        return n;
    }

    public long approximateBytes() {
        long bytes = 0;
        for (WorldCache c : worlds.values()) {
            for (Entry e : c.entries.values()) {
                ChunkFlags f = e.now();
                if (f != null) {
                    bytes += f.byteSize();
                }
            }
        }
        return bytes;
    }

    public long hits() {
        return hits.get();
    }

    public long snapshotLoads() {
        return snapshotLoads.get();
    }

    public long diskLoads() {
        return diskLoads.get();
    }

    public long unloadedMisses() {
        return unloadedMisses.get();
    }

    public long timeouts() {
        return timeouts.get();
    }

    private static Path regionDirectory(World world) {
        Path folder = world.getWorldFolder().toPath();
        return switch (world.getEnvironment()) {
            case NETHER -> folder.resolve("DIM-1").resolve("region");
            case THE_END -> folder.resolve("DIM1").resolve("region");
            default -> folder.resolve("region");
        };
    }
}
