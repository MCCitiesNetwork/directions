package com.minecraftcitiesnetwork.directions.walk.bukkit;

import com.minecraftcitiesnetwork.directions.walk.coarse.BuildProgress;
import com.minecraftcitiesnetwork.directions.walk.coarse.CoarseGraph;
import com.minecraftcitiesnetwork.directions.walk.coarse.CoarseGraphCodec;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.Nullable;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * The coarse graph of every world, with loading and saving. Owned by the plugin, so it outlives
 * config reloads. Main thread unless a method says otherwise.
 */
public final class CoarseGraphs implements Listener {
    private static final long SAVE_PERIOD_TICKS = 10 * 60 * 20L;
    private static final String EXTENSION = ".coarse";
    /** How long the plugin's disable waits for the last saves; a whole-world graph takes seconds to write. */
    private static final long STOP_WAIT_SECONDS = 60;
    private static final long SLOW_SAVE_NANOS = 1_000_000_000L;

    /** One world's graph and its save state. The monitor serialises writes of this world's file. */
    private static final class WorldGraph {
        volatile @Nullable CoarseGraph graph;
        volatile BuildProgress progress = BuildProgress.NONE;
        volatile boolean dirty;
        /** While the file is being read: a graph made meanwhile is replaced, and nothing is saved. */
        volatile boolean loading;
    }

    private final JavaPlugin plugin;
    private final File directory;
    private final Map<UUID, WorldGraph> worlds = new ConcurrentHashMap<>();
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Directions-Coarse-IO");
        t.setDaemon(true);
        return t;
    });
    private BukkitTask timer;
    private volatile boolean stopped;

    public CoarseGraphs(JavaPlugin plugin) {
        this.plugin = plugin;
        this.directory = new File(plugin.getDataFolder(), "walk");
    }

    public void start() {
        for (World world : Bukkit.getWorlds()) {
            loadAsync(world);
        }
        Bukkit.getPluginManager().registerEvents(this, plugin);
        timer = Bukkit.getScheduler().runTaskTimer(plugin, this::saveDirtyAsync, SAVE_PERIOD_TICKS, SAVE_PERIOD_TICKS);
    }

    /** Saves every changed world before returning, then stops the save thread. */
    public void stop() {
        stopped = true;
        if (timer != null) {
            timer.cancel();
            timer = null;
        }
        HandlerList.unregisterAll(this);
        io.shutdown();
        worlds.forEach((id, state) -> {
            if (state.dirty && !state.loading && state.graph != null) {
                state.dirty = false;
                write(id, state, state.graph);
            }
        });
        try {
            if (!io.awaitTermination(STOP_WAIT_SECONDS, TimeUnit.SECONDS)) {
                plugin.getLogger().warning("Coarse graph saving did not finish within " + STOP_WAIT_SECONDS + " seconds");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @EventHandler
    public void onWorldLoad(WorldLoadEvent event) {
        loadAsync(event.getWorld());
    }

    /** Any thread. Null until loaded, or when the world has no file and nothing was captured yet. */
    public @Nullable CoarseGraph graph(UUID world) {
        WorldGraph state = worlds.get(world);
        return state == null ? null : state.graph;
    }

    /**
     * Any thread. The graph searches may use: only once a build of the world has finished, since
     * until then unbuilt chunks would read as impassable. Null otherwise.
     */
    public @Nullable CoarseGraph graphForPlanning(UUID world) {
        WorldGraph state = worlds.get(world);
        if (state == null || state.loading || !state.progress.done()) {
            return null;
        }
        return state.graph;
    }

    /** The world's graph, made empty on first use. Only the builder creates graphs. */
    public CoarseGraph graphOrCreate(UUID world) {
        WorldGraph state = state(world);
        CoarseGraph graph = state.graph;
        if (graph == null) {
            graph = new CoarseGraph();
            state.graph = graph;
        }
        return graph;
    }

    /** True while the world's graph file is being read; its graph and progress are not known yet. */
    public boolean loading(UUID world) {
        WorldGraph state = worlds.get(world);
        return state != null && state.loading;
    }

    public BuildProgress progress(UUID world) {
        return state(world).progress;
    }

    public void setProgress(UUID world, BuildProgress progress) {
        WorldGraph state = state(world);
        state.progress = progress;
        state.dirty = true;
    }

    /** Any thread. The world's graph changed and should be saved. */
    public void markDirty(UUID world) {
        state(world).dirty = true;
    }

    /** Copies the graph and writes it on the save thread, replacing the file only once it is complete. */
    public void saveAsync(UUID world) {
        WorldGraph state = worlds.get(world);
        if (state == null || state.loading || state.graph == null || stopped) {
            return;
        }
        CoarseGraph graph = state.graph;
        state.dirty = false;
        io.execute(() -> write(world, state, graph));
    }

    /** One line per loaded world. */
    public List<String> describe() {
        List<String> lines = new ArrayList<>();
        for (World world : Bukkit.getWorlds()) {
            WorldGraph state = worlds.get(world.getUID());
            CoarseGraph graph = state == null ? null : state.graph;
            if (graph == null) {
                lines.add("Coarse graph " + world.getName() + ": no graph");
                continue;
            }
            lines.add("Coarse graph " + world.getName() + ": " + graph.size() + " nodes in " + graph.chunkCount()
                    + " chunks, build " + describe(state.progress));
        }
        return lines;
    }

    private static String describe(BuildProgress progress) {
        if (progress.done()) {
            return "done";
        }
        if (progress.row() < 0) {
            return "not started";
        }
        return "row " + progress.row() + " of region " + progress.regionX() + "," + progress.regionZ();
    }

    private WorldGraph state(UUID world) {
        return worlds.computeIfAbsent(world, id -> new WorldGraph());
    }

    private void saveDirtyAsync() {
        worlds.forEach((id, state) -> {
            if (state.dirty) {
                saveAsync(id);
            }
        });
    }

    private void loadAsync(World world) {
        UUID id = world.getUID();
        WorldGraph state = state(id);
        if (state.loading || state.graph != null) {
            return;
        }
        state.loading = true;
        io.execute(() -> {
            Path file = file(id);
            CoarseGraphCodec.Loaded loaded = Files.exists(file) ? read(id, file) : null;
            if (stopped || !plugin.isEnabled()) {
                return;
            }
            try {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    state.loading = false;
                    if (loaded != null) {
                        state.graph = loaded.graph();
                        state.progress = loaded.progress();
                        state.dirty = false;
                    }
                });
            } catch (IllegalPluginAccessException e) {
                // Disabled between the check and the hand-off.
            }
        });
    }

    /** Save thread. Null after logging a warning when the file cannot be read. */
    private @Nullable CoarseGraphCodec.Loaded read(UUID world, Path file) {
        try (InputStream in = new BufferedInputStream(Files.newInputStream(file))) {
            CoarseGraphCodec.Loaded loaded = CoarseGraphCodec.read(in);
            if (!loaded.world().equals(world)) {
                throw new IOException("the file belongs to world " + loaded.world());
            }
            return loaded;
        } catch (IOException | RuntimeException e) {
            plugin.getLogger().warning("Could not read coarse graph " + file + ": " + e
                    + "; the world runs without a graph until it is rebuilt");
            return null;
        }
    }

    /** Any thread; writes of one world never overlap. On failure the world is marked changed again. */
    private void write(UUID world, WorldGraph state, CoarseGraph graph) {
        synchronized (state) {
            Path target = file(world);
            Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
            long started = System.nanoTime();
            try {
                // Progress read before the nodes, so it never claims rows whose nodes the copy lacks.
                BuildProgress progress = state.progress;
                CoarseGraph copy = CoarseGraph.of(graph.snapshot());
                Files.createDirectories(directory.toPath());
                try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(tmp))) {
                    CoarseGraphCodec.write(world, copy, progress, out);
                }
                try {
                    Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
                }
                long nanos = System.nanoTime() - started;
                if (nanos > SLOW_SAVE_NANOS) {
                    plugin.getLogger().info(String.format(Locale.ROOT,
                            "Saved coarse graph %s: %d nodes in %d chunks, %d KB, in %.1f s", target.getFileName(),
                            copy.size(), copy.chunkCount(), Files.size(target) / 1024, nanos / 1e9));
                }
            } catch (IOException | RuntimeException e) {
                state.dirty = true;
                try {
                    Files.deleteIfExists(tmp);
                } catch (IOException ignored) {
                    // The next save overwrites it.
                }
                plugin.getLogger().log(Level.WARNING, "Could not save coarse graph " + target, e);
            }
        }
    }

    private Path file(UUID world) {
        return new File(directory, world + EXTENSION).toPath();
    }
}
