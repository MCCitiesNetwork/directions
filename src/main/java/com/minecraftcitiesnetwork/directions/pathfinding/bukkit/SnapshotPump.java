package com.minecraftcitiesnetwork.directions.pathfinding.bukkit;

import org.bukkit.Bukkit;
import org.bukkit.ChunkSnapshot;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The only place chunk snapshots are taken: on the main thread, at most a fixed number per tick.
 * Search threads queue requests and wait on the returned future. A chunk that is not loaded when
 * its turn comes completes with {@code null} so the caller can try the region file instead.
 */
public final class SnapshotPump {
    private record Request(World world, int chunkX, int chunkZ, CompletableFuture<ChunkSnapshot> future) {
    }

    private final Plugin plugin;
    private final int perTick;
    private final ConcurrentLinkedQueue<Request> queue = new ConcurrentLinkedQueue<>();
    private final AtomicLong served = new AtomicLong();
    private final AtomicLong totalNanos = new AtomicLong();
    private volatile long maxTickNanos;
    private volatile int queued;
    private BukkitTask task;

    public SnapshotPump(Plugin plugin, int perTick) {
        this.plugin = plugin;
        this.perTick = perTick;
    }

    public void start() {
        if (task == null) {
            task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
        }
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        Request r;
        while ((r = queue.poll()) != null) {
            r.future.complete(null);
        }
    }

    /** Safe from any thread. On the main thread the snapshot is taken immediately. */
    public CompletableFuture<ChunkSnapshot> request(World world, int chunkX, int chunkZ) {
        CompletableFuture<ChunkSnapshot> future = new CompletableFuture<>();
        if (Bukkit.isPrimaryThread()) {
            future.complete(take(world, chunkX, chunkZ));
            return future;
        }
        queue.add(new Request(world, chunkX, chunkZ, future));
        return future;
    }

    private void tick() {
        long start = System.nanoTime();
        int n = 0;
        Request r;
        while (n < perTick && (r = queue.poll()) != null) {
            try {
                r.future.complete(take(r.world, r.chunkX, r.chunkZ));
            } catch (RuntimeException e) {
                r.future.completeExceptionally(e);
            }
            n++;
        }
        queued = queue.size();
        if (n > 0) {
            long nanos = System.nanoTime() - start;
            served.addAndGet(n);
            totalNanos.addAndGet(nanos);
            if (nanos > maxTickNanos) {
                maxTickNanos = nanos;
            }
        }
    }

    private static ChunkSnapshot take(World world, int chunkX, int chunkZ) {
        if (!world.isChunkLoaded(chunkX, chunkZ)) {
            return null;
        }
        return world.getChunkAt(chunkX, chunkZ).getChunkSnapshot(true, false, false);
    }

    public long served() {
        return served.get();
    }

    public double averageMicrosPerSnapshot() {
        long s = served.get();
        return s == 0 ? 0 : totalNanos.get() / 1000.0 / s;
    }

    public double maxTickMillis() {
        return maxTickNanos / 1_000_000.0;
    }

    public int queued() {
        return queued;
    }
}
