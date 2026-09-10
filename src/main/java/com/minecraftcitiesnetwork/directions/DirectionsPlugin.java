package com.minecraftcitiesnetwork.directions;

import com.minecraftcitiesnetwork.directions.command.CompassBrigadierRegistrar;
import com.minecraftcitiesnetwork.directions.command.CompassCommand;
import com.minecraftcitiesnetwork.directions.command.DebugCommand;
import com.minecraftcitiesnetwork.directions.command.DirectionsBrigadierRegistrar;
import com.minecraftcitiesnetwork.directions.command.DirectionsCommand;
import com.minecraftcitiesnetwork.directions.command.GpsBrigadierRegistrar;
import com.minecraftcitiesnetwork.directions.command.GpsCommand;
import com.minecraftcitiesnetwork.directions.config.ConfigLoader;
import com.minecraftcitiesnetwork.directions.graph.TransitGraph;
import com.minecraftcitiesnetwork.directions.i18n.LangService;
import com.minecraftcitiesnetwork.directions.navigation.NavigationService;
import com.minecraftcitiesnetwork.directions.pathfinding.PathfindingSettings;
import com.minecraftcitiesnetwork.directions.pathfinding.bukkit.SelfTestRunner;
import com.minecraftcitiesnetwork.directions.pathfinding.bukkit.WalkPathService;
import com.minecraftcitiesnetwork.directions.pathfinding.bukkit.WalkingDistanceCache;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.Map;
import java.util.logging.Level;

public class DirectionsPlugin extends JavaPlugin {
    private ConfigLoader.LoadedData loadedData;
    private TransitGraph transitGraph;
    private NavigationService navigationService;
    private LangService lang;
    private WalkPathService walkPathService;
    private WalkingDistanceCache walkingDistanceCache;

    @Override
    public void onEnable() {
        this.lang = new LangService(this);
        this.lang.load();
        boolean selfTest = SelfTestRunner.requested();
        try {
            reloadDirectionsData();
        } catch (RuntimeException | LinkageError e) {
            if (!selfTest) {
                throw e;
            }
            // The self-test server has no transit network; run with an empty one and pathfinding on.
            getLogger().log(Level.WARNING, "Self-test mode: transit config not loaded (" + e.getMessage() + ")");
            this.loadedData = emptyData();
            this.transitGraph = new TransitGraph(List.of(), List.of(), 0, 0, "strict");
            applyPathfinding(PathfindingSettings.withEnabled(PathfindingSettings.DISABLED, true, List.of()));
        }
        this.navigationService = new NavigationService(this);
        this.navigationService.start();

        DirectionsCommand command = new DirectionsCommand(this);
        DebugCommand debugCommand = new DebugCommand(this);
        new DirectionsBrigadierRegistrar(this, command, debugCommand).register();
        GpsCommand gpsCommand = new GpsCommand(this);
        new GpsBrigadierRegistrar(this, gpsCommand).register();
        CompassCommand compassCommand = new CompassCommand(this, command);
        new CompassBrigadierRegistrar(this, compassCommand).register();

        if (selfTest) {
            if (walkPathService == null) {
                applyPathfinding(PathfindingSettings.withEnabled(loadedData.pathfinding(), true, List.of()));
            }
            new SelfTestRunner(this, walkPathService).start();
        }
    }

    @Override
    public void onDisable() {
        if (navigationService != null) {
            navigationService.stop();
        }
        if (walkPathService != null) {
            walkPathService.shutdown();
            walkPathService = null;
        }
    }

    public void reloadDirectionsData() {
        ConfigLoader loader = new ConfigLoader(this);
        this.loadedData = loader.loadAndValidate();
        this.lang.reload();
        applyPathfinding(loadedData.pathfinding());
        rebuildGraph();
        getLogger().info("Loaded " + loadedData.stopsById().size() + " stops and " + loadedData.lines().size() + " lines.");
        if (walkPathService != null && loadedData.pathfinding().routing().precomputeStopEdges()) {
            walkingDistanceCache.recompute(loadedData.stopsById().values(), loadedData.maxWalkingDistance() * 1.5,
                    walkPathService, getLogger(), this::rebuildGraph);
        }
    }

    private void applyPathfinding(PathfindingSettings settings) {
        if (walkPathService != null) {
            walkPathService.shutdown();
            walkPathService = null;
        }
        if (!settings.enabled()) {
            walkingDistanceCache = null;
            getLogger().info("Pathfinding is disabled; using straight-line directions.");
            return;
        }
        walkPathService = new WalkPathService(this, settings);
        walkPathService.start();
        walkingDistanceCache = new WalkingDistanceCache();
        getLogger().info("Pathfinding enabled (" + settings.engine().threads() + " threads"
                + (settings.worlds().isEmpty() ? ", all worlds" : ", worlds " + settings.worlds()) + ").");
    }

    private void rebuildGraph() {
        Map<String, Double> walked = walkingDistanceCache == null ? Map.of() : walkingDistanceCache.snapshot();
        this.transitGraph = new TransitGraph(
                loadedData.stopsById().values(),
                loadedData.lines(),
                loadedData.transferPenalty(),
                loadedData.maxWalkingDistance(),
                loadedData.walkingTransferPolicy(),
                walked
        );
        if (!walked.isEmpty()) {
            getLogger().info("Transit graph rebuilt with " + transitGraph.walkingEdgesFromPaths() + " walking edges from real paths.");
        }
    }

    private static ConfigLoader.LoadedData emptyData() {
        return new ConfigLoader.LoadedData(50, 150, 5, 15, 5, 5, "Next: <stop>  <remaining>m", "Destination: <stop>  <remaining>m",
                "strict", Map.of(), Map.of(), List.of(), Map.of(), PathfindingSettings.DISABLED);
    }

    public ConfigLoader.LoadedData getLoadedData() {
        return loadedData;
    }

    public TransitGraph getTransitGraph() {
        return transitGraph;
    }

    public NavigationService getNavigationService() {
        return navigationService;
    }

    public LangService getLang() {
        return lang;
    }

    /** Null when pathfinding is disabled. */
    public WalkPathService getWalkPathService() {
        return walkPathService;
    }

    /** Null when pathfinding is disabled. */
    public WalkingDistanceCache getWalkingDistanceCache() {
        return walkingDistanceCache;
    }
}
