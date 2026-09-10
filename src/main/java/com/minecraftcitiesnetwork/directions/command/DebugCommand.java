package com.minecraftcitiesnetwork.directions.command;

import com.minecraftcitiesnetwork.directions.DirectionsPlugin;
import com.minecraftcitiesnetwork.directions.i18n.LangService;
import com.minecraftcitiesnetwork.directions.pathfinding.BlockFlags;
import com.minecraftcitiesnetwork.directions.pathfinding.Pos;
import com.minecraftcitiesnetwork.directions.pathfinding.WalkResult;
import com.minecraftcitiesnetwork.directions.pathfinding.bukkit.CachedBlockGrid;
import com.minecraftcitiesnetwork.directions.pathfinding.bukkit.PathParticles;
import com.minecraftcitiesnetwork.directions.pathfinding.bukkit.PathfindingMetrics;
import com.minecraftcitiesnetwork.directions.pathfinding.bukkit.WalkPathService;
import com.minecraftcitiesnetwork.directions.pathfinding.bukkit.WalkingDistanceCache;
import com.minecraftcitiesnetwork.directions.pathfinding.bukkit.WorldCapture;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;

/** {@code /directions debug ...}: path preview, terrain capture, flag inspection, statistics. */
public class DebugCommand {
    private final DirectionsPlugin plugin;
    private final LangService lang;

    public DebugCommand(DirectionsPlugin plugin) {
        this.plugin = plugin;
        this.lang = plugin.getLang();
    }

    private WalkPathService service(CommandSender sender) {
        WalkPathService service = plugin.getWalkPathService();
        if (service == null) {
            lang.send(sender, "debug.disabled", lang.placeholderRaw("prefix", lang.raw("prefix")));
        }
        return service;
    }

    public void path(Player player, double x, double z) {
        WalkPathService service = service(player);
        if (service == null) {
            return;
        }
        World world = player.getWorld();
        Pos to = new Pos((int) Math.floor(x), world.getMaxHeight() - 1, (int) Math.floor(z));
        runPath(player, service, to, x + ", " + z);
    }

    public void pathToRegion(Player player, String regionId) {
        WalkPathService service = service(player);
        if (service == null) {
            return;
        }
        RegionManager manager = WorldGuard.getInstance().getPlatform().getRegionContainer()
                .get(BukkitAdapter.adapt(player.getWorld()));
        ProtectedRegion region = manager == null ? null : manager.getRegion(regionId.toLowerCase(Locale.ROOT));
        if (region == null) {
            lang.send(player, "errors.region-not-found",
                    lang.placeholderRaw("prefix", lang.raw("prefix")), lang.placeholder("region", regionId));
            return;
        }
        var min = region.getMinimumPoint();
        var max = region.getMaximumPoint();
        Pos to = new Pos((min.x() + max.x()) / 2, (min.y() + max.y()) / 2, (min.z() + max.z()) / 2);
        runPath(player, service, to, regionId);
    }

    private void runPath(Player player, WalkPathService service, Pos to, String label) {
        Location loc = player.getLocation();
        Pos from = Pos.floor(loc.getX(), loc.getY(), loc.getZ());
        lang.send(player, "debug.path-started", lang.placeholderRaw("prefix", lang.raw("prefix")), lang.placeholder("target", label));
        service.find(player.getWorld(), from, to, 0, Duration.ofSeconds(5)).thenAccept(result -> {
            lang.send(player, "debug.path-result",
                    lang.placeholderRaw("prefix", lang.raw("prefix")),
                    lang.placeholder("result", result.toString()));
            if (result.found()) {
                PathParticles.show(plugin, player, result.path(), Duration.ofSeconds(20));
            } else if (result.path() != null) {
                PathParticles.show(plugin, player, result.path(), Duration.ofSeconds(10));
            }
        });
    }

    public void capture(Player player, String name, int radius) {
        WalkPathService service = service(player);
        if (service == null) {
            return;
        }
        int r = Math.max(8, Math.min(radius, 256));
        Location loc = player.getLocation();
        Pos centre = Pos.floor(loc.getX(), loc.getY(), loc.getZ());
        Path file = plugin.getDataFolder().toPath().resolve("captures").resolve(name.replaceAll("[^a-zA-Z0-9_-]", "_") + ".grid.gz");
        lang.send(player, "debug.capture-started", lang.placeholderRaw("prefix", lang.raw("prefix")), lang.placeholder("radius", String.valueOf(r)));
        new WorldCapture(service).capture(player.getWorld(), centre, r, file).whenComplete((result, error) -> {
            if (error != null) {
                lang.send(player, "debug.capture-failed", lang.placeholderRaw("prefix", lang.raw("prefix")), lang.placeholder("error", String.valueOf(error.getMessage())));
                return;
            }
            lang.send(player, "debug.capture-done",
                    lang.placeholderRaw("prefix", lang.raw("prefix")),
                    lang.placeholder("file", result.file().toString()),
                    lang.placeholder("size", result.sizeX() + "x" + (result.maxY() - result.minY() + 1) + "x" + result.sizeZ()),
                    lang.placeholder("offset", result.originX() + ", " + result.originZ()),
                    lang.placeholder("bytes", String.valueOf(result.bytes())));
        });
    }

    public void flags(Player player) {
        WalkPathService service = service(player);
        if (service == null) {
            return;
        }
        Location loc = player.getLocation();
        Pos at = Pos.floor(loc.getX(), loc.getY(), loc.getZ());
        World world = player.getWorld();
        service.submit(() -> {
            long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
            CachedBlockGrid grid = new CachedBlockGrid(world, service.cache(), deadline);
            StringBuilder sb = new StringBuilder();
            for (int dy = 1; dy >= -1; dy--) {
                sb.append("y=").append(at.y() + dy).append('\n');
                for (int dz = -5; dz <= 5; dz++) {
                    for (int dx = -5; dx <= 5; dx++) {
                        byte f = grid.flags(at.x() + dx, at.y() + dy, at.z() + dz);
                        sb.append(dx == 0 && dz == 0 && dy == 0 ? '@' : BlockFlags.symbol(f));
                    }
                    sb.append('\n');
                }
            }
            String text = sb.toString();
            service.mainThread().run(() -> lang.send(player, "debug.flags",
                    lang.placeholderRaw("prefix", lang.raw("prefix")),
                    lang.placeholder("at", at.toString()),
                    lang.placeholder("grid", text)));
        });
    }

    public void stats(CommandSender sender) {
        WalkPathService service = plugin.getWalkPathService();
        if (service == null) {
            lang.send(sender, "debug.disabled", lang.placeholderRaw("prefix", lang.raw("prefix")));
            return;
        }
        PathfindingMetrics m = service.metrics();
        WalkingDistanceCache w = plugin.getWalkingDistanceCache();
        String lines = String.join("\n",
                "searches: " + m.total() + " (found " + m.count(WalkResult.Status.FOUND)
                        + ", none " + m.count(WalkResult.Status.NONE)
                        + ", timeout " + m.count(WalkResult.Status.TIMEOUT)
                        + ", exhausted " + m.count(WalkResult.Status.EXHAUSTED)
                        + ", too far " + m.count(WalkResult.Status.TOO_FAR)
                        + ", invalid " + (m.count(WalkResult.Status.INVALID_START) + m.count(WalkResult.Status.INVALID_TARGET))
                        + ", error " + m.count(WalkResult.Status.ERROR) + ")",
                String.format(Locale.ROOT, "latency: avg %.1f ms, p50 %.1f ms, p95 %.1f ms, in flight %d",
                        m.averageMillis(), m.percentileMillis(50), m.percentileMillis(95), service.inFlight()),
                String.format(Locale.ROOT, "chunk cache: %d chunks, %.1f MB, hits %d, snapshot loads %d, disk loads %d, unloaded %d, timeouts %d",
                        service.cache().cachedChunks(), service.cache().approximateBytes() / 1_048_576.0,
                        service.cache().hits(), service.cache().snapshotLoads(), service.cache().diskLoads(),
                        service.cache().unloadedMisses(), service.cache().timeouts()),
                String.format(Locale.ROOT, "pump: %d snapshots, avg %.0f us each, worst tick %.2f ms, queued %d",
                        service.pump().served(), service.pump().averageMicrosPerSnapshot(),
                        service.pump().maxTickMillis(), service.pump().queued()),
                "stop edges: " + (w == null ? "n/a" : w.summary() + (w.computing() ? " (recomputing)" : "")));
        lang.send(sender, "debug.stats", lang.placeholderRaw("prefix", lang.raw("prefix")), lang.placeholder("stats", lines));
    }
}
