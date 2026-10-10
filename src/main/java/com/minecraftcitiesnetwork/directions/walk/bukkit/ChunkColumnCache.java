package com.minecraftcitiesnetwork.directions.walk.bukkit;

import com.minecraftcitiesnetwork.directions.walk.ColumnChunk;
import com.minecraftcitiesnetwork.directions.walk.ColumnDecoder;
import com.minecraftcitiesnetwork.directions.walk.ColumnStore;
import com.minecraftcitiesnetwork.directions.walk.WalkSettings;
import com.minecraftcitiesnetwork.directions.walk.WalkStats;
import com.minecraftcitiesnetwork.directions.walk.Window;
import org.bukkit.Bukkit;
import org.bukkit.ChunkSnapshot;
import org.bukkit.World;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * Keeps {@link ColumnStore} filled with the loaded chunks searches need. Snapshots are taken on the
 * main thread, at most {@code snapshots-per-tick} per tick, and decoded on the search pool. Never
 * loads a chunk. Changed chunks are re-captured at most once per second.
 */
public final class ChunkColumnCache {
    static final long STALE_AFTER_MILLIS = 5 * 60 * 1000;
    private static final long RECAPTURE_INTERVAL_MILLIS = 1000;

    record ChunkKey(UUID world, int x, int z) {
    }

    /** Told about every chunk stored in the {@link ColumnStore}, on the main thread, right after it is stored. */
    interface CapturedListener {
        void captured(UUID world, int chunkX, int chunkZ);
    }

    private final JavaPlugin plugin;
    private final ColumnStore store;
    private final MaterialKinds kinds;
    private final Executor decoder;
    private final WalkSettings settings;
    private final WalkStats stats;
    private final CapturedListener onCaptured;
    private final ArrayDeque<ChunkKey> queue = new ArrayDeque<>();
    private final Set<ChunkKey> dirty = new HashSet<>();
    private final Map<ChunkKey, CompletableFuture<Void>> pending = new ConcurrentHashMap<>();
    private final Map<ChunkKey, Long> capturedAt = new ConcurrentHashMap<>();
    private BukkitTask task;

    ChunkColumnCache(JavaPlugin plugin, ColumnStore store, MaterialKinds kinds, Executor decoder,
                     WalkSettings settings, WalkStats stats, CapturedListener onCaptured) {
        this.plugin = plugin;
        this.store = store;
        this.kinds = kinds;
        this.decoder = decoder;
        this.settings = settings;
        this.stats = stats;
        this.onCaptured = onCaptured;
    }

    void start() {
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::pump, 1L, 1L);
    }

    void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        queue.clear();
        dirty.clear();
        pending.values().forEach(f -> f.complete(null));
        pending.clear();
        capturedAt.clear();
        store.clear();
    }

    /**
     * Main thread. Queues every loaded, uncaptured chunk under the window. Completes when they are
     * all captured, or after {@code capture-wait-ms}, whichever comes first.
     */
    CompletableFuture<Void> capture(World world, Window window) {
        UUID id = world.getUID();
        List<CompletableFuture<Void>> waits = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (int[] c : nearestFirst(window)) {
            int cx = c[0];
            int cz = c[1];
            ChunkKey key = new ChunkKey(id, cx, cz);
            if (store.contains(id, cx, cz)) {
                Long at = capturedAt.get(key);
                boolean stale = at != null && now - at > STALE_AFTER_MILLIS && world.isChunkLoaded(cx, cz);
                if (!stale) {
                    stats.cacheHit();
                    continue;
                }
            } else if (!world.isChunkLoaded(cx, cz)) {
                continue;
            }
            if (!pending.containsKey(key)) {
                stats.cacheMiss();
            }
            waits.add(enqueue(key));
        }
        if (waits.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        return CompletableFuture.allOf(waits.toArray(new CompletableFuture[0]))
                .completeOnTimeout(null, settings.captureWaitMillis(), TimeUnit.MILLISECONDS);
    }

    /** The window's chunks ordered by squared distance from its centre chunk, ties by x then z. */
    static List<int[]> nearestFirst(Window window) {
        int minCx = window.minX() >> 4;
        int maxCx = window.maxX() >> 4;
        int minCz = window.minZ() >> 4;
        int maxCz = window.maxZ() >> 4;
        int centreX = ((window.minX() + window.maxX()) / 2) >> 4;
        int centreZ = ((window.minZ() + window.maxZ()) / 2) >> 4;
        List<int[]> chunks = new ArrayList<>();
        for (int cx = minCx; cx <= maxCx; cx++) {
            for (int cz = minCz; cz <= maxCz; cz++) {
                chunks.add(new int[] {cx, cz});
            }
        }
        chunks.sort(Comparator
                .comparingLong((int[] c) -> (long) (c[0] - centreX) * (c[0] - centreX) + (long) (c[1] - centreZ) * (c[1] - centreZ))
                .thenComparingInt(c -> c[0])
                .thenComparingInt(c -> c[1]));
        return chunks;
    }

    /** Main thread. A block in this chunk changed. */
    void markDirty(World world, int chunkX, int chunkZ) {
        dirty.add(new ChunkKey(world.getUID(), chunkX, chunkZ));
    }

    /** Main thread. The chunk unloaded. */
    void evict(World world, int chunkX, int chunkZ) {
        ChunkKey key = new ChunkKey(world.getUID(), chunkX, chunkZ);
        store.remove(key.world(), chunkX, chunkZ);
        capturedAt.remove(key);
        dirty.remove(key);
    }

    private CompletableFuture<Void> enqueue(ChunkKey key) {
        return pending.computeIfAbsent(key, k -> {
            queue.add(k);
            return new CompletableFuture<>();
        });
    }

    private void pump() {
        long started = System.nanoTime();
        long now = System.currentTimeMillis();
        for (Iterator<ChunkKey> it = dirty.iterator(); it.hasNext(); ) {
            ChunkKey key = it.next();
            if (pending.containsKey(key)) {
                continue;
            }
            Long at = capturedAt.get(key);
            if (at != null && now - at < RECAPTURE_INTERVAL_MILLIS) {
                continue;
            }
            it.remove();
            if (store.contains(key.world(), key.x(), key.z())) {
                enqueue(key);
            }
        }
        int taken = 0;
        while (taken < settings.snapshotsPerTick() && !queue.isEmpty()) {
            ChunkKey key = queue.poll();
            World world = Bukkit.getWorld(key.world());
            if (world == null || !world.isChunkLoaded(key.x(), key.z())) {
                finish(key);
                continue;
            }
            ChunkSnapshot snapshot = world.getChunkAt(key.x(), key.z()).getChunkSnapshot(false, false, false);
            int minY = world.getMinHeight();
            int maxY = world.getMaxHeight();
            taken++;
            try {
                decoder.execute(() -> decode(key, snapshot, minY, maxY));
            } catch (RejectedExecutionException e) {
                finish(key);
            }
        }
        stats.recordPumpTick(System.nanoTime() - started);
    }

    private void decode(ChunkKey key, ChunkSnapshot snapshot, int minY, int maxY) {
        ColumnChunk decoded = null;
        try {
            decoded = ColumnDecoder.decode(key.x(), key.z(), new SnapshotBlocks(snapshot, kinds, minY, maxY));
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to decode chunk " + key, e);
        }
        ColumnChunk result = decoded;
        if (!plugin.isEnabled()) {
            finish(key);
            return;
        }
        Bukkit.getScheduler().runTask(plugin, () -> {
            try {
                World world = Bukkit.getWorld(key.world());
                if (result != null && world != null && world.isChunkLoaded(key.x(), key.z())) {
                    store.put(key.world(), result);
                    capturedAt.put(key, System.currentTimeMillis());
                    onCaptured.captured(key.world(), key.x(), key.z());
                }
            } finally {
                finish(key);
            }
        });
    }

    private void finish(ChunkKey key) {
        CompletableFuture<Void> waiting = pending.remove(key);
        if (waiting != null) {
            waiting.complete(null);
        }
    }
}
