package com.minecraftcitiesnetwork.directions.walk.bukkit;

import com.minecraftcitiesnetwork.directions.i18n.LangService;
import com.minecraftcitiesnetwork.directions.walk.ColumnChunk;
import com.minecraftcitiesnetwork.directions.walk.ColumnDecoder;
import com.minecraftcitiesnetwork.directions.walk.ColumnStore;
import com.minecraftcitiesnetwork.directions.walk.Terrain;
import com.minecraftcitiesnetwork.directions.walk.WalkRules;
import com.minecraftcitiesnetwork.directions.walk.WalkSettings;
import com.minecraftcitiesnetwork.directions.walk.coarse.BuildOrder;
import com.minecraftcitiesnetwork.directions.walk.coarse.BuildProgress;
import com.minecraftcitiesnetwork.directions.walk.coarse.BuildRows;
import com.minecraftcitiesnetwork.directions.walk.coarse.ChunkEdges;
import com.minecraftcitiesnetwork.directions.walk.coarse.ChunkKeys;
import com.minecraftcitiesnetwork.directions.walk.coarse.ChunkPos;
import com.minecraftcitiesnetwork.directions.walk.coarse.CoarseGraph;
import com.minecraftcitiesnetwork.directions.walk.coarse.Component;
import com.minecraftcitiesnetwork.directions.walk.coarse.ComponentCache;
import com.minecraftcitiesnetwork.directions.walk.coarse.RegionHeaders;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.ChunkSnapshot;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.stream.Stream;

/**
 * Builds the coarse graph of a whole world in the background: every chunk that exists in the
 * world's region files is loaded (never generated), snapshotted on the main thread, and decoded
 * and linked on a builder-owned thread. One build at a time. Main thread unless a method says
 * otherwise.
 */
public final class CoarseBuilder {
    private static final int REGION_WIDTH = 32;
    private static final int BUILD_STORE_CHUNKS = 4096;
    private static final int SAVE_EVERY_CHUNKS = 2000;
    private static final long STOP_WAIT_SECONDS = 10;

    public enum StartResult { STARTED, RESUMED, ALREADY_RUNNING, LOADING, DISABLED }

    /** A region file's chunks still to build, as local indices (row * 32 + column), in row order. */
    private record Region(int x, int z, short[] chunks) {
        ChunkPos chunk(int i) {
            short local = chunks[i];
            return new ChunkPos(x * REGION_WIDTH + (local & 31), z * REGION_WIDTH + (local >> 5));
        }

        List<ChunkPos> all() {
            List<ChunkPos> all = new ArrayList<>(chunks.length);
            for (int i = 0; i < chunks.length; i++) {
                all.add(chunk(i));
            }
            return all;
        }
    }

    /**
     * The regions to build in order, and per region the chunks an earlier, interrupted build already
     * built (as a set of local indices); empty for a build from the start.
     */
    private record Plan(List<Region> regions, Map<ChunkPos, BitSet> builtBefore) {
        boolean builtBefore(ChunkPos chunk) {
            BitSet built = builtBefore.get(new ChunkPos(Math.floorDiv(chunk.x(), REGION_WIDTH), Math.floorDiv(chunk.z(), REGION_WIDTH)));
            return built != null && built.get(Math.floorMod(chunk.z(), REGION_WIDTH) * REGION_WIDTH + Math.floorMod(chunk.x(), REGION_WIDTH));
        }
    }

    /** A chunk the builder thread has finished with; {@code built} is false when it failed. */
    private record Finished(ChunkPos chunk, boolean built) {
    }

    private final JavaPlugin plugin;
    private final CoarseGraphs graphs;
    private final LangService lang;
    private final MaterialKinds kinds = new MaterialKinds();
    private WalkRules rules = new WalkRules(WalkSettings.DEFAULTS.walker());
    private int chunksPerTick = WalkSettings.CoarseSettings.DEFAULTS.buildChunksPerTick();
    private boolean enabled = WalkSettings.CoarseSettings.DEFAULTS.enabled();
    private @Nullable Build build;

    public CoarseBuilder(JavaPlugin plugin, CoarseGraphs graphs, LangService lang) {
        this.plugin = plugin;
        this.graphs = graphs;
        this.lang = lang;
    }

    /**
     * Takes the new settings. A running build keeps going at the new rate, but keeps the walking
     * rules it started with so the whole graph is linked under one set of rules.
     */
    public void configure(WalkSettings settings) {
        this.chunksPerTick = settings.coarse().buildChunksPerTick();
        this.rules = new WalkRules(settings.walker());
        this.enabled = settings.coarse().enabled();
    }

    /**
     * Starts building the world, or resumes from its saved progress. Reads every region file header
     * of the world first, so the chunk count is known when this returns.
     */
    public StartResult start(World world, CommandSender requester) {
        if (!enabled) {
            return StartResult.DISABLED;
        }
        if (build != null) {
            return StartResult.ALREADY_RUNNING;
        }
        UUID id = world.getUID();
        if (graphs.loading(id)) {
            return StartResult.LOADING;
        }
        BuildProgress saved = graphs.progress(id);
        boolean resume = !saved.equals(BuildProgress.NONE) && !saved.done();
        if (!resume && saved.done()) {
            // A finished build is started over from the beginning: a full rebuild.
            graphs.setProgress(id, BuildProgress.NONE);
        }
        BuildProgress from = resume ? saved : BuildProgress.NONE;
        graphs.graphOrCreate(id);
        Build started = new Build(world, requester, plan(world, from));
        build = started;
        started.timer = Bukkit.getScheduler().runTaskTimer(plugin, started::tick, 1L, 1L);
        plugin.getLogger().info((resume ? "Resuming" : "Starting") + " the coarse graph build of " + world.getName()
                + ": " + started.total + " chunks in " + started.regions.size() + " region files");
        return resume ? StartResult.RESUMED : StartResult.STARTED;
    }

    /** Chunks the running build set out to do (what was left when it started or resumed); 0 when none runs. */
    public int total() {
        return build == null ? 0 : build.total;
    }

    /**
     * Stops the running build: no more chunks are requested, the chunks being decoded finish, and the
     * progress is saved. False when no build is running.
     */
    public boolean stop() {
        Build running = build;
        if (running == null) {
            return false;
        }
        build = null;
        running.end();
        running.drainFinished();
        graphs.saveAsync(running.worldId);
        plugin.getLogger().info("Stopped the coarse graph build of " + running.worldName + " after "
                + running.done + " of " + running.total + " chunks");
        return true;
    }

    /** Stops any running build and saves its progress. Safe to call when nothing runs. */
    public void shutdown() {
        stop();
    }

    public boolean running() {
        return build != null;
    }

    public List<String> describe() {
        Build running = build;
        if (running == null) {
            return List.of("Build: none running");
        }
        return List.of(running.describe());
    }

    /** The region files of the world with the chunks still to do, in build order. Unreadable files are skipped. */
    private Plan plan(World world, BuildProgress from) {
        Path folder = regionFolder(world);
        if (!Files.isDirectory(folder)) {
            return new Plan(List.of(), Map.of());
        }
        List<Path> files;
        try (Stream<Path> listing = Files.list(folder)) {
            files = listing.filter(p -> RegionHeaders.regionOf(String.valueOf(p.getFileName())) != null).toList();
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "Could not list the region files in " + folder, e);
            return new Plan(List.of(), Map.of());
        }
        List<Region> regions = new ArrayList<>();
        Map<ChunkPos, BitSet> builtBefore = new HashMap<>();
        for (Path file : files) {
            ChunkPos region = RegionHeaders.regionOf(String.valueOf(file.getFileName()));
            List<ChunkPos> existing;
            try {
                existing = RegionHeaders.chunksIn(file);
            } catch (IOException e) {
                plugin.getLogger().warning("Skipping unreadable region file " + file + ": " + e.getMessage());
                continue;
            }
            List<ChunkPos> toDo = BuildOrder.after(existing, from, region.x(), region.z());
            if (toDo.size() < existing.size()) {
                BitSet built = new BitSet(REGION_WIDTH * REGION_WIDTH);
                for (ChunkPos chunk : existing) {
                    built.set(local(chunk));
                }
                for (ChunkPos chunk : toDo) {
                    built.clear(local(chunk));
                }
                builtBefore.put(region, built);
            }
            if (toDo.isEmpty()) {
                continue;
            }
            short[] local = new short[toDo.size()];
            for (int i = 0; i < local.length; i++) {
                local[i] = (short) local(toDo.get(i));
            }
            regions.add(new Region(region.x(), region.z(), local));
        }
        regions.sort(Comparator.comparingInt(Region::x).thenComparingInt(Region::z));
        return new Plan(regions, builtBefore);
    }

    private static int local(ChunkPos chunk) {
        return Math.floorMod(chunk.z(), REGION_WIDTH) * REGION_WIDTH + Math.floorMod(chunk.x(), REGION_WIDTH);
    }

    private static Path regionFolder(World world) {
        Path root = world.getWorldFolder().toPath();
        return switch (world.getEnvironment()) {
            case NETHER -> root.resolve("DIM-1").resolve("region");
            case THE_END -> root.resolve("DIM1").resolve("region");
            default -> root.resolve("region");
        };
    }

    /** One run of a world build. Fields are main-thread only unless marked otherwise. */
    private final class Build {
        final World world;
        final UUID worldId;
        final String worldName;
        final CommandSender requester;
        final Plan plan;
        final List<Region> regions;
        final int total;
        /** The rules at the start; a reload during the build does not change them. */
        final WalkRules buildRules = rules;
        final long startedNanos = System.nanoTime();
        final BuildRows rows = new BuildRows();
        final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "Directions-CoarseBuild");
            t.setDaemon(true);
            return t;
        });
        /** Any thread: chunks the builder thread has finished, for the main thread to count. */
        final Queue<Finished> finished = new ConcurrentLinkedQueue<>();
        /**
         * Chunks an earlier build already built that border the next region's chunks. They are decoded
         * into the build store first, without touching the graph, so the region links across the point
         * where the earlier build stopped.
         */
        final ArrayDeque<ChunkPos> warmUp = new ArrayDeque<>();
        /** Builder thread only: components of recently built chunks, so a neighbour is not flooded once per side. */
        final ComponentCache components = new ComponentCache();
        /** Builder thread only: decoded chunks, so each chunk can be linked to the neighbours built before it. */
        final ColumnStore store = new ColumnStore(BUILD_STORE_CHUNKS, System::currentTimeMillis);
        /**
         * Builder thread only: the east column of every region in the previous region column. Regions
         * are built column by column, so a region's west neighbours were built a whole column of
         * regions ago and have usually left the build store; they are put back from here.
         */
        final Map<Long, ColumnChunk> eastColumns = new HashMap<>();
        int eastColumnsRegionX = Integer.MIN_VALUE;
        volatile boolean failureToBuildLogged;

        BukkitTask timer;
        int regionIndex;
        boolean regionOpened;
        int nextInRegion;
        /** Chunks requested and not yet finished by the builder thread. */
        int inFlight;
        /** Warm-up chunks requested and not yet handed to the builder thread. */
        int warming;
        int done;
        int skipped;
        int sinceSave;
        boolean failureToLoadLogged;
        BuildProgress lastProgress = BuildProgress.NONE;

        Build(World world, CommandSender requester, Plan plan) {
            this.world = world;
            this.worldId = world.getUID();
            this.worldName = world.getName();
            this.requester = requester;
            this.plan = plan;
            this.regions = plan.regions();
            int sum = 0;
            for (Region region : regions) {
                sum += region.chunks().length;
            }
            this.total = sum;
        }

        void tick() {
            if (build != this) {
                return;
            }
            if (Bukkit.getWorld(worldId) == null) {
                plugin.getLogger().warning("World " + worldName + " unloaded; stopping its coarse graph build");
                stop();
                return;
            }
            drainFinished();
            int perTick = chunksPerTick;
            int requested = 0;
            while (requested < perTick) {
                ChunkPos warm = warmUp.poll();
                if (warm != null) {
                    // Costs a request but is not counted as a chunk of the build.
                    requested++;
                    warming++;
                    request(warm, true);
                    continue;
                }
                // A region's chunks wait until the neighbours they link to are queued for decoding.
                // In flight counts until the builder thread is done with a chunk, so snapshots waiting
                // to be decoded never pile up beyond this.
                if (warming > 0 || inFlight >= perTick * 4 || regionIndex >= regions.size()) {
                    break;
                }
                Region region = regions.get(regionIndex);
                if (!regionOpened) {
                    regionOpened = true;
                    List<ChunkPos> chunks = region.all();
                    rows.open(region.x(), region.z(), chunks);
                    warmUp.addAll(BuildOrder.builtNeighbours(chunks, plan::builtBefore));
                    continue;
                }
                if (nextInRegion >= region.chunks().length) {
                    regionIndex++;
                    regionOpened = false;
                    nextInRegion = 0;
                    continue;
                }
                requested++;
                inFlight++;
                request(region.chunk(nextInRegion++), false);
            }
            if (inFlight == 0 && warming == 0 && regionIndex >= regions.size()) {
                complete();
            }
        }

        void request(ChunkPos chunk, boolean warm) {
            if (world.isChunkLoaded(chunk.x(), chunk.z())) {
                snapshot(chunk, warm, world.getChunkAt(chunk.x(), chunk.z()));
                return;
            }
            world.getChunkAtAsync(chunk.x(), chunk.z(), false).whenComplete((loaded, error) -> {
                if (Bukkit.isPrimaryThread()) {
                    arrived(chunk, warm, loaded, error);
                    return;
                }
                try {
                    Bukkit.getScheduler().runTask(plugin, () -> arrived(chunk, warm, loaded, error));
                } catch (IllegalPluginAccessException disabled) {
                    // The plugin is disabling; the build has been stopped.
                }
            });
        }

        void arrived(ChunkPos chunk, boolean warm, @Nullable Chunk loaded, @Nullable Throwable error) {
            if (build != this) {
                return;
            }
            if (loaded == null || error != null) {
                if (warm) {
                    // Nothing to link to on that side; the earlier build skipped it too.
                    warming--;
                    return;
                }
                if (!failureToLoadLogged) {
                    failureToLoadLogged = true;
                    plugin.getLogger().warning("Coarse graph build of " + worldName + ": could not load chunk "
                            + chunk.x() + "," + chunk.z() + " (" + (error == null ? "not generated" : error)
                            + "); it and any further chunks that fail are skipped");
                }
                finished.add(new Finished(chunk, false));
                drainFinished();
                return;
            }
            snapshot(chunk, warm, loaded);
        }

        void snapshot(ChunkPos chunk, boolean warm, Chunk loaded) {
            ChunkSnapshot snapshot = loaded.getChunkSnapshot(false, false, false);
            int minY = world.getMinHeight();
            int maxY = world.getMaxHeight();
            try {
                if (warm) {
                    executor.execute(() -> warmUp(chunk, snapshot, minY, maxY));
                } else {
                    CoarseGraph graph = graphs.graphOrCreate(worldId);
                    executor.execute(() -> link(chunk, snapshot, minY, maxY, graph));
                }
            } catch (RejectedExecutionException stopped) {
                // Only after the build ended; nothing counts any more.
            }
            if (warm) {
                // The builder thread runs tasks in order, so chunks requested from now on decode after this one.
                warming--;
            }
        }

        /** Builder thread: decodes a chunk an earlier build already built, so later chunks can link to it. */
        void warmUp(ChunkPos chunk, ChunkSnapshot snapshot, int minY, int maxY) {
            try {
                ColumnChunk decoded = ColumnDecoder.decode(chunk.x(), chunk.z(), new SnapshotBlocks(snapshot, kinds, minY, maxY));
                store.put(worldId, decoded);
                components.invalidate(chunk.x(), chunk.z());
                keepEastColumn(decoded);
            } catch (RuntimeException e) {
                logBuildFailure(chunk, e);
            }
        }

        /** Builder thread: decodes the chunk, works out its components and edges, and puts them in the graph. */
        void link(ChunkPos chunk, ChunkSnapshot snapshot, int minY, int maxY, CoarseGraph graph) {
            boolean built = false;
            try {
                int x = chunk.x();
                int z = chunk.z();
                ColumnChunk decoded = ColumnDecoder.decode(x, z, new SnapshotBlocks(snapshot, kinds, minY, maxY));
                store.put(worldId, decoded);
                keepEastColumn(decoded);
                restoreWestNeighbour(x, z);
                Terrain terrain = store.terrain(worldId);
                this.components.invalidate(x, z);
                List<Component> components = this.components.of(terrain, x, z, buildRules);
                ChunkEdges.Linked linked = ChunkEdges.compute(terrain, buildRules, x, z, components,
                        (nx, nz) -> this.components.of(terrain, nx, nz, buildRules));
                graph.replace(x, z, linked.nodes(), linked.sides());
                graphs.markDirty(worldId);
                built = true;
            } catch (RuntimeException e) {
                logBuildFailure(chunk, e);
            } finally {
                finished.add(new Finished(chunk, built));
            }
        }

        /** Builder thread. */
        void logBuildFailure(ChunkPos chunk, RuntimeException e) {
            if (!failureToBuildLogged) {
                failureToBuildLogged = true;
                plugin.getLogger().log(Level.WARNING, "Coarse graph build of " + worldName + ": could not build chunk "
                        + chunk.x() + "," + chunk.z() + "; it and any further chunks that fail are skipped", e);
            }
        }

        /** Builder thread. */
        void keepEastColumn(ColumnChunk chunk) {
            int regionX = Math.floorDiv(chunk.chunkX(), REGION_WIDTH);
            if (regionX > eastColumnsRegionX) {
                eastColumnsRegionX = regionX;
                eastColumns.keySet().removeIf(key -> Math.floorDiv(ChunkKeys.x(key), REGION_WIDTH) < regionX - 1);
            }
            if (Math.floorMod(chunk.chunkX(), REGION_WIDTH) == REGION_WIDTH - 1) {
                eastColumns.put(ChunkKeys.key(chunk.chunkX(), chunk.chunkZ()), chunk);
            }
        }

        /** Builder thread. */
        void restoreWestNeighbour(int x, int z) {
            if (Math.floorMod(x, REGION_WIDTH) != 0 || store.contains(worldId, x - 1, z)) {
                return;
            }
            ColumnChunk west = eastColumns.get(ChunkKeys.key(x - 1, z));
            if (west != null) {
                store.put(worldId, west);
            }
        }

        /** Counts the chunks the builder thread has finished, and records each row once all of it is done. */
        void drainFinished() {
            BuildProgress advanced = null;
            Finished next;
            while ((next = finished.poll()) != null) {
                inFlight--;
                done++;
                if (!next.built()) {
                    skipped++;
                }
                BuildProgress progress = rows.finish(next.chunk());
                if (progress != null) {
                    advanced = progress;
                }
                sinceSave++;
            }
            if (advanced != null) {
                lastProgress = advanced;
                graphs.setProgress(worldId, advanced);
            }
            if (sinceSave >= SAVE_EVERY_CHUNKS) {
                sinceSave = 0;
                graphs.saveAsync(worldId);
            }
        }

        void complete() {
            build = null;
            end();
            graphs.setProgress(worldId, new BuildProgress(lastProgress.regionX(), lastProgress.regionZ(), lastProgress.row(), true));
            graphs.saveAsync(worldId);
            plugin.getLogger().info("Coarse graph build of " + worldName + " done: " + done + " chunks ("
                    + skipped + " skipped) in " + elapsedSeconds() / 60 + " min");
            boolean present = requester instanceof ConsoleCommandSender
                    || (requester instanceof Player player && player.isOnline());
            if (present) {
                lang.send(requester, "walk.build-done",
                        lang.placeholderRaw("prefix", lang.raw("prefix")),
                        lang.placeholder("world", worldName),
                        lang.placeholder("chunks", String.valueOf(done)));
            }
        }

        /** Cancels the timer and lets the chunks being decoded finish. */
        void end() {
            if (timer != null) {
                timer.cancel();
                timer = null;
            }
            executor.shutdown();
            try {
                if (!executor.awaitTermination(STOP_WAIT_SECONDS, TimeUnit.SECONDS)) {
                    plugin.getLogger().warning("Coarse graph build chunks did not finish within " + STOP_WAIT_SECONDS + " seconds");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        long elapsedSeconds() {
            return (System.nanoTime() - startedNanos) / 1_000_000_000L;
        }

        String describe() {
            double seconds = Math.max(1e-3, (System.nanoTime() - startedNanos) / 1e9);
            double rate = done / seconds;
            String minutesLeft = rate > 0 ? String.valueOf(Math.round((total - done) / rate / 60)) : "?";
            return "Build " + worldName + ": " + done + "/" + total + " chunks, "
                    + String.format(Locale.ROOT, "%.1f", rate) + "/s, about " + minutesLeft + " min left, "
                    + skipped + " skipped";
        }
    }
}
