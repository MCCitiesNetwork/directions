package com.minecraftcitiesnetwork.directions.config;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import com.minecraftcitiesnetwork.directions.model.CostModel;
import com.minecraftcitiesnetwork.directions.model.Line;
import com.minecraftcitiesnetwork.directions.model.Stop;
import com.minecraftcitiesnetwork.directions.pathfinding.PathfindingSettings;
import com.minecraftcitiesnetwork.directions.pathfinding.WalkerProfile;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.plugin.java.JavaPlugin;
import org.spongepowered.configurate.CommentedConfigurationNode;
import org.spongepowered.configurate.ConfigurateException;
import org.spongepowered.configurate.objectmapping.ConfigSerializable;
import org.spongepowered.configurate.objectmapping.meta.Setting;
import org.spongepowered.configurate.yaml.NodeStyle;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class ConfigLoader {
    private final JavaPlugin plugin;

    public ConfigLoader(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public LoadedData loadAndValidate() {
        plugin.saveDefaultConfig();
        Path configPath = plugin.getDataFolder().toPath().resolve("config.yml");
        Path namesPath = plugin.getDataFolder().toPath().resolve("names.yml");
        if (!namesPath.toFile().exists()) {
            plugin.saveResource("names.yml", false);
        }

        try {
            MainConfig root = requireConfig(loadYaml(configPath), MainConfig.class, "config.yml");
            NamesConfig namesRoot = requireConfig(loadYaml(namesPath), NamesConfig.class, "names.yml");
            double transferPenalty = root.transferPenalty;
            double maxWalkingDistance = root.maxWalkingDistance;
            double stopArrivalBuffer = root.stopArrivalBuffer;
            double nextStopNotifyDistance = root.nextStopNotifyDistance;
            double departureClearanceRadius = root.departureClearanceRadius;
            double coordinateArrivalRadius = root.coordinateArrivalRadius;
            String bossbarFormat = root.bossbarFormat;
            if (bossbarFormat == null || bossbarFormat.isBlank()) {
                bossbarFormat = "Next: <stop>  <remaining>m";
            }
            String gpsBossbarFormat = root.gpsBossbarFormat;
            if (gpsBossbarFormat == null || gpsBossbarFormat.isBlank()) {
                gpsBossbarFormat = "Destination: <stop>  <remaining>m";
            }
            if (transferPenalty < 0) {
                throw new IllegalStateException("transfer-penalty must be >= 0");
            }
            if (maxWalkingDistance < 0) {
                throw new IllegalStateException("max-walking-distance must be >= 0");
            }
            if (stopArrivalBuffer < 0) {
                throw new IllegalStateException("stop-arrival-buffer must be >= 0");
            }
            if (nextStopNotifyDistance < 0) {
                throw new IllegalStateException("next-stop-notify-distance must be >= 0");
            }
            if (departureClearanceRadius < 0) {
                throw new IllegalStateException("departure-clearance-radius must be >= 0");
            }
            if (coordinateArrivalRadius < 0) {
                throw new IllegalStateException("coordinate-arrival-radius must be >= 0");
            }
            String walkingTransferPolicy = root.walkingTransferPolicy;
            if (walkingTransferPolicy == null || walkingTransferPolicy.isBlank()) {
                walkingTransferPolicy = "shared-mode-only";
            }
            walkingTransferPolicy = walkingTransferPolicy.toLowerCase(Locale.ROOT);
            if (!walkingTransferPolicy.equals("shared-mode-only") && !walkingTransferPolicy.equals("strict")) {
                throw new IllegalStateException("Invalid walking-transfer-policy. Use 'shared-mode-only' or 'strict'.");
            }
            PathfindingSettings pathfinding = readPathfinding(root.pathfinding == null ? new PathfindingSection() : root.pathfinding);
            Map<String, String> lineDisplayNames = readDisplayNameMap(namesRoot.lineDisplayNames);
            Map<String, String> stopDisplayNames = readDisplayNameMap(namesRoot.stopDisplayNames);

            if (root.lines == null || root.lines.isEmpty()) {
                throw new IllegalStateException("Missing 'lines' section in config.yml");
            }

            List<Line> lines = new ArrayList<>();
            Map<String, Set<String>> stopToLines = new HashMap<>();
            Set<String> allStopIds = new HashSet<>();

            for (Map.Entry<String, LineSection> lineEntry : root.lines.entrySet()) {
                String lineId = lineEntry.getKey();
                LineSection lineSection = lineEntry.getValue();
                if (lineId == null || lineId.isBlank()) {
                    throw new IllegalStateException("Line id cannot be blank.");
                }
                if (lineSection == null) {
                    throw new IllegalStateException("Line '" + lineId + "' has no config section.");
                }
                String type = (lineSection.type == null ? "bus" : lineSection.type).toLowerCase(Locale.ROOT);
                if (!type.equals("bus") && !type.equals("train")) {
                    throw new IllegalStateException("Line '" + lineId + "' has invalid type '" + type + "'. Use bus or train.");
                }
                CostModel costModel = CostModel.fromConfig(lineSection.costModel)
                        .orElseThrow(() -> new IllegalStateException("Line '" + lineId
                                + "' has invalid cost-model '" + lineSection.costModel + "'. Use distance or fixed."));

                List<String> stops = new ArrayList<>();
                for (String stop : lineSection.stops == null ? List.<String>of() : lineSection.stops) {
                    if (stop != null && !stop.isBlank()) {
                        stops.add(stop.toLowerCase(Locale.ROOT));
                    }
                }

                if (stops.isEmpty()) {
                    throw new IllegalStateException("Line '" + lineId + "' has no stops.");
                }

                lines.add(new Line(lineId, type, costModel, List.copyOf(stops)));
                for (String stop : stops) {
                    allStopIds.add(stop);
                    stopToLines.computeIfAbsent(stop, __ -> new HashSet<>()).add(lineId);
                }
            }

            Map<String, Stop> stops = new HashMap<>();
            for (String stopId : allStopIds) {
                Stop stop = findStopAcrossWorlds(stopId, stopToLines.getOrDefault(stopId, Set.of()));
                if (stop == null) {
                    throw new IllegalStateException("Stop region '" + stopId + "' was not found in any world.");
                }
                stops.put(stopId, stop);
            }

            return new LoadedData(
                    transferPenalty,
                    maxWalkingDistance,
                    stopArrivalBuffer,
                    nextStopNotifyDistance,
                    departureClearanceRadius,
                    coordinateArrivalRadius,
                    bossbarFormat,
                    gpsBossbarFormat,
                    walkingTransferPolicy,
                    lineDisplayNames,
                    stopDisplayNames,
                    lines,
                    stops,
                    pathfinding
            );
        } catch (ConfigurateException e) {
            throw new IllegalStateException("Failed to load config.yml with Configurate", e);
        }
    }

    private static CommentedConfigurationNode loadYaml(Path path) throws ConfigurateException {
        return YamlConfigurationLoader.builder()
                .path(path)
                .nodeStyle(NodeStyle.BLOCK)
                .build()
                .load();
    }

    private static <T> T requireConfig(CommentedConfigurationNode node, Class<T> clazz, String fileName) throws ConfigurateException {
        T mapped = node.get(clazz);
        if (mapped == null) {
            throw new IllegalStateException("Failed to map " + fileName + " to " + clazz.getSimpleName());
        }
        return mapped;
    }

    private static Map<String, String> readDisplayNameMap(Map<String, String> source) {
        Map<String, String> out = new HashMap<>();
        if (source == null || source.isEmpty()) {
            return out;
        }
        for (Map.Entry<String, String> entry : source.entrySet()) {
            String key = entry.getKey() == null ? "" : entry.getKey().toLowerCase(Locale.ROOT);
            String value = entry.getValue();
            if (value != null && !value.isBlank()) {
                out.put(key, value);
            }
        }
        return out;
    }

    private Stop findStopAcrossWorlds(String stopId, Set<String> lines) {
        for (World world : Bukkit.getWorlds()) {
            RegionManager manager = WorldGuard.getInstance().getPlatform().getRegionContainer()
                    .get(BukkitAdapter.adapt(world));
            if (manager == null) {
                continue;
            }
            ProtectedRegion region = manager.getRegion(stopId);
            if (region == null) {
                continue;
            }
            BlockVector3 min = region.getMinimumPoint();
            BlockVector3 max = region.getMaximumPoint();
            double cx = (min.x() + max.x()) / 2.0;
            double cz = (min.z() + max.z()) / 2.0;
            boolean fullHeight = min.y() <= world.getMinHeight() && max.y() >= world.getMaxHeight() - 1;
            double cy = fullHeight ? Double.NaN : (min.y() + max.y()) / 2.0;
            return new Stop(stopId, world.getName(), cx, cy, cz, lines);
        }
        return null;
    }

    static PathfindingSettings readPathfinding(PathfindingSection s) {
        try {
            WalkerProfile walker = new WalkerProfile(
                    s.walker.height, s.walker.stepUp, s.walker.maxDrop, s.walker.canSwim,
                    s.walker.doorCost, s.walker.waterCost, s.walker.climbCost, s.walker.snapRadius);
            return new PathfindingSettings(
                    s.enabled,
                    new HashSet<>(s.worlds == null ? List.of() : s.worlds),
                    new PathfindingSettings.Routing(s.routing.enabled, s.routing.maxLegDistance,
                            Duration.ofMillis(s.routing.timeBudgetMs), s.routing.precomputeStopEdges),
                    new PathfindingSettings.Guidance(s.guidance.enabled, s.guidance.lookAhead, s.guidance.replanDistance,
                            Duration.ofMillis(s.guidance.replanCooldownMs), s.guidance.maxSearchDistance,
                            Duration.ofMillis(s.guidance.timeBudgetMs)),
                    walker,
                    new PathfindingSettings.Engine(s.engine.threads, s.engine.maxIterations),
                    new PathfindingSettings.Chunks(s.chunks.snapshotsPerTick, s.chunks.diskReader, s.chunks.flagBandHeight,
                            s.chunks.cacheMaxChunks, Duration.ofSeconds(s.chunks.cacheIdleSeconds),
                            Duration.ofSeconds(s.chunks.unloadedRetrySeconds)),
                    new PathfindingSettings.Debug(s.debug.particles, s.debug.logSearches)
            );
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("Invalid pathfinding section in config.yml: " + e.getMessage(), e);
        }
    }

    public record LoadedData(double transferPenalty,
                             double maxWalkingDistance,
                             double stopArrivalBuffer,
                             double nextStopNotifyDistance,
                             double departureClearanceRadius,
                             double coordinateArrivalRadius,
                             String bossbarFormat,
                             String gpsBossbarFormat,
                             String walkingTransferPolicy,
                             Map<String, String> lineDisplayNames,
                             Map<String, String> stopDisplayNames,
                             List<Line> lines,
                             Map<String, Stop> stopsById,
                             PathfindingSettings pathfinding) {
        public LoadedData(double transferPenalty,
                          double maxWalkingDistance,
                          double stopArrivalBuffer,
                          double nextStopNotifyDistance,
                          double departureClearanceRadius,
                          double coordinateArrivalRadius,
                          String bossbarFormat,
                          String gpsBossbarFormat,
                          String walkingTransferPolicy,
                          Map<String, String> lineDisplayNames,
                          Map<String, String> stopDisplayNames,
                          List<Line> lines,
                          Map<String, Stop> stopsById) {
            this(transferPenalty, maxWalkingDistance, stopArrivalBuffer, nextStopNotifyDistance, departureClearanceRadius,
                    coordinateArrivalRadius, bossbarFormat, gpsBossbarFormat, walkingTransferPolicy, lineDisplayNames,
                    stopDisplayNames, lines, stopsById, PathfindingSettings.DISABLED);
        }

        public String displayLine(String lineId) {
            String key = lineId.toLowerCase(Locale.ROOT);
            return lineDisplayNames.getOrDefault(key, humanize(key));
        }

        public String displayStop(String stopId) {
            String key = stopId.toLowerCase(Locale.ROOT);
            return stopDisplayNames.getOrDefault(key, humanize(key));
        }

        public boolean hasNamedStopDisplay(String regionId) {
            String key = regionId.toLowerCase(Locale.ROOT);
            return stopsById.containsKey(key) || stopDisplayNames.containsKey(key);
        }

        private static String humanize(String key) {
            String[] parts = key.replace('-', ' ').replace('_', ' ').trim().split("\\s+");
            StringBuilder sb = new StringBuilder();
            for (String part : parts) {
                if (part.isEmpty()) {
                    continue;
                }
                if (sb.length() > 0) {
                    sb.append(' ');
                }
                sb.append(Character.toUpperCase(part.charAt(0)));
                if (part.length() > 1) {
                    sb.append(part.substring(1));
                }
            }
            return sb.toString();
        }
    }

    @ConfigSerializable
    public static final class MainConfig {
        @Setting("transfer-penalty")
        public double transferPenalty = 50.0;
        @Setting("max-walking-distance")
        public double maxWalkingDistance = 150.0;
        @Setting("stop-arrival-buffer")
        public double stopArrivalBuffer = 5.0;
        @Setting("next-stop-notify-distance")
        public double nextStopNotifyDistance = 15.0;
        @Setting("departure-clearance-radius")
        public double departureClearanceRadius = 5.0;
        @Setting("coordinate-arrival-radius")
        public double coordinateArrivalRadius = 5.0;
        @Setting("bossbar-format")
        public String bossbarFormat = "Next: <stop>  <remaining>m";
        @Setting("gps-bossbar-format")
        public String gpsBossbarFormat = "Destination: <stop>  <remaining>m";
        @Setting("walking-transfer-policy")
        public String walkingTransferPolicy = "shared-mode-only";
        @Setting("lines")
        public Map<String, LineSection> lines = Collections.emptyMap();
        @Setting("pathfinding")
        public PathfindingSection pathfinding = new PathfindingSection();
    }

    @ConfigSerializable
    public static final class PathfindingSection {
        @Setting("enabled")
        public boolean enabled = false;
        @Setting("worlds")
        public List<String> worlds = Collections.emptyList();
        @Setting("routing")
        public RoutingSection routing = new RoutingSection();
        @Setting("guidance")
        public GuidanceSection guidance = new GuidanceSection();
        @Setting("walker")
        public WalkerSection walker = new WalkerSection();
        @Setting("engine")
        public EngineSection engine = new EngineSection();
        @Setting("chunks")
        public ChunksSection chunks = new ChunksSection();
        @Setting("debug")
        public DebugSection debug = new DebugSection();
    }

    @ConfigSerializable
    public static final class RoutingSection {
        @Setting("enabled")
        public boolean enabled = true;
        @Setting("max-leg-distance")
        public double maxLegDistance = 300;
        @Setting("time-budget-ms")
        public long timeBudgetMs = 250;
        @Setting("precompute-stop-edges")
        public boolean precomputeStopEdges = true;
    }

    @ConfigSerializable
    public static final class GuidanceSection {
        @Setting("enabled")
        public boolean enabled = true;
        @Setting("look-ahead")
        public double lookAhead = 6;
        @Setting("replan-distance")
        public double replanDistance = 8;
        @Setting("replan-cooldown-ms")
        public long replanCooldownMs = 2000;
        @Setting("max-search-distance")
        public double maxSearchDistance = 600;
        @Setting("time-budget-ms")
        public long timeBudgetMs = 400;
    }

    @ConfigSerializable
    public static final class WalkerSection {
        @Setting("height")
        public int height = 2;
        @Setting("step-up")
        public boolean stepUp = true;
        @Setting("max-drop")
        public int maxDrop = 3;
        @Setting("can-swim")
        public boolean canSwim = true;
        @Setting("door-cost")
        public double doorCost = 1.5;
        @Setting("water-cost")
        public double waterCost = 2.5;
        @Setting("climb-cost")
        public double climbCost = 2.0;
        @Setting("snap-radius")
        public int snapRadius = 6;
    }

    @ConfigSerializable
    public static final class EngineSection {
        @Setting("threads")
        public int threads = 2;
        @Setting("max-iterations")
        public int maxIterations = 200_000;
    }

    @ConfigSerializable
    public static final class ChunksSection {
        @Setting("snapshots-per-tick")
        public int snapshotsPerTick = 8;
        @Setting("disk-reader")
        public boolean diskReader = true;
        @Setting("flag-band-height")
        public int flagBandHeight = 64;
        @Setting("cache-max-chunks")
        public int cacheMaxChunks = 2048;
        @Setting("cache-idle-seconds")
        public int cacheIdleSeconds = 90;
        @Setting("unloaded-retry-seconds")
        public int unloadedRetrySeconds = 60;
    }

    @ConfigSerializable
    public static final class DebugSection {
        @Setting("particles")
        public boolean particles = false;
        @Setting("log-searches")
        public boolean logSearches = false;
    }

    @ConfigSerializable
    public static final class NamesConfig {
        @Setting("line-display-names")
        public Map<String, String> lineDisplayNames = Collections.emptyMap();
        @Setting("stop-display-names")
        public Map<String, String> stopDisplayNames = Collections.emptyMap();
    }

    @ConfigSerializable
    public static final class LineSection {
        @Setting("type")
        public String type = "bus";
        @Setting("cost-model")
        public String costModel = "distance";
        @Setting("stops")
        public List<String> stops = Collections.emptyList();
    }
}
