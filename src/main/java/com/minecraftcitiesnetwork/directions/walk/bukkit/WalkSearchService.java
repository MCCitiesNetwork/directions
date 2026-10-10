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
import com.minecraftcitiesnetwork.directions.walk.coarse.ChunkEdges;
import com.minecraftcitiesnetwork.directions.walk.coarse.CoarseGraph;
import com.minecraftcitiesnetwork.directions.walk.coarse.Component;
import com.minecraftcitiesnetwork.directions.walk.coarse.ComponentCache;
import com.minecraftcitiesnetwork.directions.walk.coarse.GuidedSearch;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
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
    /** Live graph updates, apart from the search threads and below them in priority. */
    private final ExecutorService linkExecutor;
    /** Per world: components of recently linked chunks, so a neighbour is not flooded once per side. */
    private final Map<UUID, ComponentCache> componentCaches = new ConcurrentHashMap<>();
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
        this.linkExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "Directions-CoarseLink");
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
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
        linkExecutor.shutdownNow();
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
        CoarseGraph graph = settings.coarse().enabled() ? graphs.graphForPlanning(worldId) : null;
        cache.capture(world, window).thenRunAsync(() -> {
            long budget = settings.timeBudgetMillis() * 1_000_000L;
            // GuidedSearch gives every phase its own deadline of the budget; this one is not read.
            SearchRequest request = new SearchRequest(x, y, z, goal, window, routeEnd, settings.maxExpansions(),
                    System.nanoTime() + budget);
            SearchResult r = search.search(store.terrain(worldId), request, graph, settings.coarse().maxExpansions(),
                    budget);
            stats.record(r);
            if (r.coarseNanos() > 0) {
                stats.recordCoarse(r.coarseNanos());
            }
            boolean directlyReached = goal.mayOverlap(window) && r.status() == SearchResult.Status.COMPLETE;
            if (graph != null && !graph.isEmpty() && !r.coarse() && !directlyReached) {
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

    /**
     * Main thread. Brings the graph's nodes for a freshly captured chunk up to date, on the pool.
     * Only a graph that already exists is updated: a world gets one from a build, never from captures.
     */
    private void captured(UUID world, int chunkX, int chunkZ) {
        if (!running || !settings.coarse().enabled()) {
            return;
        }
        ComponentCache components = componentCaches.computeIfAbsent(world, id -> new ComponentCache());
        // Even without a graph: the chunk's terrain is new, so its cached components are stale.
        components.invalidate(chunkX, chunkZ);
        CoarseGraph graph = graphs.graph(world);
        if (graph == null) {
            return;
        }
        try {
            linkExecutor.execute(() -> link(graph, components, world, chunkX, chunkZ));
        } catch (RejectedExecutionException e) {
            // Shutting down.
        }
    }

    /** Link thread. */
    private void link(CoarseGraph graph, ComponentCache cache, UUID world, int chunkX, int chunkZ) {
        try {
            Terrain terrain = store.terrain(world);
            ChunkEdges.Linked linked;
            try {
                // Again here: a link of a neighbour may have cached this chunk from its old terrain meanwhile.
                cache.invalidate(chunkX, chunkZ);
                List<Component> components = cache.of(terrain, chunkX, chunkZ, rules);
                linked = ChunkEdges.compute(terrain, rules, chunkX, chunkZ, components,
                        (x, z) -> cache.of(terrain, x, z, rules));
            } catch (IllegalStateException e) {
                return; // The chunk was evicted meanwhile; its next capture updates the graph.
            }
            graph.replace(chunkX, chunkZ, linked.nodes(), linked.sides());
            graphs.markDirty(world);
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to update the coarse graph for chunk "
                    + chunkX + "," + chunkZ + " in world " + world, e);
        }
    }

    private void toMain(Runnable task) {
        if (plugin.isEnabled()) {
            Bukkit.getScheduler().runTask(plugin, task);
        }
    }
}
