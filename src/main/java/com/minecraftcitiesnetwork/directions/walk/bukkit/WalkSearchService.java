package com.minecraftcitiesnetwork.directions.walk.bukkit;

import com.minecraftcitiesnetwork.directions.walk.Cell;
import com.minecraftcitiesnetwork.directions.walk.ColumnStore;
import com.minecraftcitiesnetwork.directions.walk.Goal;
import com.minecraftcitiesnetwork.directions.walk.SearchRequest;
import com.minecraftcitiesnetwork.directions.walk.SearchResult;
import com.minecraftcitiesnetwork.directions.walk.Terrain;
import com.minecraftcitiesnetwork.directions.walk.WalkRules;
import com.minecraftcitiesnetwork.directions.walk.WalkSettings;
import com.minecraftcitiesnetwork.directions.walk.WalkStats;
import com.minecraftcitiesnetwork.directions.walk.Window;
import com.minecraftcitiesnetwork.directions.walk.WindowSearch;
import com.minecraftcitiesnetwork.directions.walk.coarse.ChunkComponents;
import com.minecraftcitiesnetwork.directions.walk.coarse.ChunkEdges;
import com.minecraftcitiesnetwork.directions.walk.coarse.CoarseGraph;
import com.minecraftcitiesnetwork.directions.walk.coarse.Component;
import com.minecraftcitiesnetwork.directions.walk.coarse.GuidedSearch;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;

/**
 * The façade navigation uses for walking routes: owns the two search threads, the chunk cache and
 * the stats. One instance per config load.
 */
public final class WalkSearchService {
    private static final int THREADS = 2;

    private final JavaPlugin plugin;
    private final WalkSettings settings;
    private final CoarseGraphs graphs;
    private final ExecutorService executor;
    private final WalkStats stats = new WalkStats(System::currentTimeMillis);
    private final ColumnStore store;
    private final ChunkColumnCache cache;
    private final ChunkChangeListener listener;
    private final WalkRules rules;
    private final GuidedSearch search;
    private volatile boolean running;

    public WalkSearchService(JavaPlugin plugin, WalkSettings settings, CoarseGraphs graphs) {
        this.plugin = plugin;
        this.settings = settings;
        this.graphs = graphs;
        AtomicInteger threadNumber = new AtomicInteger();
        this.executor = Executors.newFixedThreadPool(THREADS, r -> {
            Thread t = new Thread(r, "Directions-Walk-" + threadNumber.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
        this.store = new ColumnStore(settings.cacheMaxChunks(), System::currentTimeMillis);
        this.cache = new ChunkColumnCache(plugin, store, new MaterialKinds(), executor, settings, stats,
                this::captured);
        this.listener = new ChunkChangeListener(cache);
        this.rules = new WalkRules(settings.walker());
        this.search = new GuidedSearch(new WindowSearch(rules, System::nanoTime), rules);
    }

    public void start() {
        running = true;
        cache.start();
        Bukkit.getPluginManager().registerEvents(listener, plugin);
    }

    public void shutdown() {
        running = false;
        HandlerList.unregisterAll(listener);
        cache.stop();
        executor.shutdownNow();
    }

    public WalkSettings settings() {
        return settings;
    }

    public WalkStats stats() {
        return stats;
    }

    public int cachedChunks() {
        return store.size();
    }

    /** Main thread. A fresh view of the captured terrain; do not keep it across ticks. */
    public Terrain terrain(World world) {
        return store.terrain(world.getUID());
    }

    /**
     * Main thread. Captures the window around the position, searches it on the pool, and completes
     * the returned future on the main thread. Never completes exceptionally.
     *
     * @param routeEnd the end of the route the player is following, or null when they have none
     */
    public CompletableFuture<SearchResult> plan(World world, double x, double y, double z, Goal goal, Cell routeEnd) {
        CompletableFuture<SearchResult> result = new CompletableFuture<>();
        if (!running) {
            result.complete(SearchResult.failed(SearchResult.Status.NO_ROUTE, 0, 0));
            return result;
        }
        UUID worldId = world.getUID();
        int radius = Math.min(settings.windowRadius(), Math.max(16, (world.getViewDistance() - 1) * 16));
        Window window = Window.around((int) Math.floor(x), (int) Math.floor(z), radius);
        CoarseGraph graph = settings.coarse().enabled() ? graphs.graph(worldId) : null;
        cache.capture(world, window).thenRunAsync(() -> {
            long deadline = System.nanoTime() + settings.timeBudgetMillis() * 1_000_000L;
            SearchRequest request = new SearchRequest(x, y, z, goal, window, routeEnd, settings.maxExpansions(), deadline);
            SearchResult r = search.search(store.terrain(worldId), request, graph, settings.coarse().maxExpansions());
            stats.record(r);
            if (graph != null && !graph.isEmpty() && !goal.mayOverlap(window) && !r.coarse()) {
                stats.recordFallback();
            }
            toMain(() -> result.complete(r));
        }, executor).exceptionally(error -> {
            if (running) {
                plugin.getLogger().log(Level.WARNING, "Walking route search failed", error);
            }
            toMain(() -> result.complete(SearchResult.failed(SearchResult.Status.NO_ROUTE, 0, 0)));
            return null;
        });
        return result;
    }

    /** Main thread. Brings the graph's nodes for a freshly captured chunk up to date, on the pool. */
    private void captured(UUID world, int chunkX, int chunkZ) {
        if (!running || !settings.coarse().enabled()) {
            return;
        }
        CoarseGraph graph = graphs.graphOrCreate(world);
        try {
            executor.execute(() -> link(graph, world, chunkX, chunkZ));
        } catch (RejectedExecutionException e) {
            // Shutting down.
        }
    }

    private void link(CoarseGraph graph, UUID world, int chunkX, int chunkZ) {
        Terrain terrain = store.terrain(world);
        ChunkEdges.Linked linked;
        try {
            List<Component> components = ChunkComponents.of(terrain, chunkX, chunkZ, rules);
            linked = ChunkEdges.compute(terrain, rules, chunkX, chunkZ, components);
        } catch (IllegalStateException e) {
            return; // The chunk was evicted meanwhile; its next capture updates the graph.
        }
        graph.replace(chunkX, chunkZ, linked.nodes(), linked.sides());
        graphs.markDirty(world);
    }

    private void toMain(Runnable task) {
        if (plugin.isEnabled()) {
            Bukkit.getScheduler().runTask(plugin, task);
        }
    }
}
