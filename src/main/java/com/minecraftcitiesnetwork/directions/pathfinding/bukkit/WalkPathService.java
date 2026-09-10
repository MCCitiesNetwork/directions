package com.minecraftcitiesnetwork.directions.pathfinding.bukkit;

import com.minecraftcitiesnetwork.directions.pathfinding.MainThread;
import com.minecraftcitiesnetwork.directions.pathfinding.PathfindingSettings;
import com.minecraftcitiesnetwork.directions.pathfinding.Pos;
import com.minecraftcitiesnetwork.directions.pathfinding.WalkPathfinder;
import com.minecraftcitiesnetwork.directions.pathfinding.WalkRequest;
import com.minecraftcitiesnetwork.directions.pathfinding.WalkResult;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;

/**
 * The façade the rest of the plugin uses for walking paths. Owns the search threads, the chunk
 * cache and pump, and the metrics. Every future it returns is completed on the main thread.
 */
public final class WalkPathService {
    private final JavaPlugin plugin;
    private final PathfindingSettings settings;
    private final WalkPathfinder pathfinder;
    private final MaterialFlags materials = new MaterialFlags();
    private final SnapshotPump pump;
    private final ChunkFlagCache cache;
    private final PathfindingMetrics metrics = new PathfindingMetrics();
    private final MainThread mainThread;
    private final ExecutorService executor;
    private final BlockChangeListener listener;
    private final AtomicInteger inFlight = new AtomicInteger();
    private BukkitTask sweeper;
    private volatile boolean running;

    public WalkPathService(JavaPlugin plugin, PathfindingSettings settings) {
        this.plugin = plugin;
        this.settings = settings;
        this.pathfinder = new WalkPathfinder(settings.walker(), settings.engine().maxIterations());
        this.pump = new SnapshotPump(plugin, settings.chunks().snapshotsPerTick());
        this.cache = new ChunkFlagCache(settings, materials, pump);
        this.mainThread = new BukkitMainThread(plugin);
        this.listener = new BlockChangeListener(cache);
        AtomicInteger n = new AtomicInteger();
        this.executor = Executors.newFixedThreadPool(settings.engine().threads(), r -> {
            Thread t = new Thread(r, "Directions-Path-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
    }

    public void start() {
        running = true;
        pump.start();
        Bukkit.getPluginManager().registerEvents(listener, plugin);
        sweeper = Bukkit.getScheduler().runTaskTimer(plugin, cache::sweep, 20L * 15, 20L * 15);
    }

    public void shutdown() {
        running = false;
        if (sweeper != null) {
            sweeper.cancel();
            sweeper = null;
        }
        HandlerList.unregisterAll(listener);
        pump.stop();
        executor.shutdownNow();
        try {
            executor.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        cache.clear();
    }

    public PathfindingSettings settings() {
        return settings;
    }

    public WalkPathfinder pathfinder() {
        return pathfinder;
    }

    public ChunkFlagCache cache() {
        return cache;
    }

    public SnapshotPump pump() {
        return pump;
    }

    public PathfindingMetrics metrics() {
        return metrics;
    }

    public MaterialFlags materials() {
        return materials;
    }

    public int inFlight() {
        return inFlight.get();
    }

    public MainThread mainThread() {
        return mainThread;
    }

    /** Runs work on a search thread. For batch jobs such as the stop-distance precompute. */
    public void submit(Runnable task) {
        if (!running) {
            return;
        }
        executor.execute(() -> {
            try {
                task.run();
            } catch (RuntimeException e) {
                plugin.getLogger().log(Level.WARNING, "Pathfinding task failed", e);
            }
        });
    }

    public boolean enabledIn(World world) {
        return running && settings.enabledIn(world.getName());
    }

    /**
     * Find a walking path. Safe to call from the main thread; the returned future completes on the
     * main thread. The corridor between the endpoints is pre-warmed through the pump first.
     */
    public CompletableFuture<WalkResult> find(World world, Location from, Location to, double maxDistance, Duration budget) {
        return find(world, Pos.floor(from.getX(), from.getY(), from.getZ()), Pos.floor(to.getX(), to.getY(), to.getZ()), maxDistance, budget);
    }

    public CompletableFuture<WalkResult> find(World world, Pos from, Pos to, double maxDistance, Duration budget) {
        CompletableFuture<WalkResult> result = new CompletableFuture<>();
        if (!running) {
            result.complete(WalkResult.of(WalkResult.Status.ERROR, from, to, 0, "pathfinding is not running"));
            return result;
        }
        prewarmCorridor(world, from, to);
        inFlight.incrementAndGet();
        executor.execute(() -> {
            WalkResult r;
            try {
                r = findBlocking(world, from, to, maxDistance, budget);
            } catch (RuntimeException e) {
                plugin.getLogger().log(Level.WARNING, "Path search failed", e);
                r = WalkResult.of(WalkResult.Status.ERROR, from, to, 0, e.toString());
            } finally {
                inFlight.decrementAndGet();
            }
            WalkResult done = r;
            mainThread.run(() -> result.complete(done));
        });
        return result;
    }

    /** Runs the search on the calling thread. Never call on the main thread. */
    public WalkResult findBlocking(World world, Pos from, Pos to, double maxDistance, Duration budget) {
        if (Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("findBlocking must not run on the main thread");
        }
        long deadline = System.nanoTime() + budget.toNanos();
        CachedBlockGrid grid = new CachedBlockGrid(world, cache, deadline);
        WalkResult r = pathfinder.find(grid, new WalkRequest(from, to, maxDistance, budget));
        metrics.record(r);
        if (settings.debug().logSearches()) {
            plugin.getLogger().info("[path] " + world.getName() + " " + from + " -> " + to + ": " + r);
        }
        return r;
    }

    /** Queues snapshots for loaded chunks along the straight line, two chunks wide, so the search rarely waits. */
    public void prewarmCorridor(World world, Pos from, Pos to) {
        int cx0 = from.x() >> 4;
        int cz0 = from.z() >> 4;
        int cx1 = to.x() >> 4;
        int cz1 = to.z() >> 4;
        int steps = Math.max(Math.abs(cx1 - cx0), Math.abs(cz1 - cz0));
        int budget = 64;
        for (int i = 0; i <= steps && budget > 0; i++) {
            double t = steps == 0 ? 0 : (double) i / steps;
            int cx = (int) Math.round(cx0 + (cx1 - cx0) * t);
            int cz = (int) Math.round(cz0 + (cz1 - cz0) * t);
            for (int dx = -1; dx <= 1 && budget > 0; dx++) {
                for (int dz = -1; dz <= 1 && budget > 0; dz++) {
                    if (world.isChunkLoaded(cx + dx, cz + dz)) {
                        cache.prefetch(world, cx + dx, cz + dz);
                        budget--;
                    }
                }
            }
        }
    }
}
