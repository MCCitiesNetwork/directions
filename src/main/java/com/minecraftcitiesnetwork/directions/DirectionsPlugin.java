package com.minecraftcitiesnetwork.directions;

import com.minecraftcitiesnetwork.directions.command.DirectionsCommand;
import com.minecraftcitiesnetwork.directions.command.DirectionsBrigadierRegistrar;
import com.minecraftcitiesnetwork.directions.command.CompassCommand;
import com.minecraftcitiesnetwork.directions.command.CompassBrigadierRegistrar;
import com.minecraftcitiesnetwork.directions.command.GpsCommand;
import com.minecraftcitiesnetwork.directions.command.GpsBrigadierRegistrar;
import com.minecraftcitiesnetwork.directions.config.ConfigLoader;
import com.minecraftcitiesnetwork.directions.graph.TransitGraph;
import com.minecraftcitiesnetwork.directions.i18n.LangService;
import com.minecraftcitiesnetwork.directions.navigation.NavigationService;
import com.minecraftcitiesnetwork.directions.walk.bukkit.WalkSearchService;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.Nullable;

public class DirectionsPlugin extends JavaPlugin {
    private ConfigLoader.LoadedData loadedData;
    private TransitGraph transitGraph;
    private NavigationService navigationService;
    private WalkSearchService walkService;
    private LangService lang;

    @Override
    public void onEnable() {
        this.lang = new LangService(this);
        this.lang.load();
        reloadDirectionsData();
        this.navigationService = new NavigationService(this);
        this.navigationService.start();

        DirectionsCommand command = new DirectionsCommand(this);
        new DirectionsBrigadierRegistrar(this, command).register();
        GpsCommand gpsCommand = new GpsCommand(this);
        new GpsBrigadierRegistrar(this, gpsCommand).register();
        CompassCommand compassCommand = new CompassCommand(this, command);
        new CompassBrigadierRegistrar(this, compassCommand).register();
    }

    @Override
    public void onDisable() {
        if (navigationService != null) {
            navigationService.stop();
        }
        stopWalking();
    }

    public void reloadDirectionsData() {
        ConfigLoader loader = new ConfigLoader(this);
        this.loadedData = loader.loadAndValidate();
        this.lang.reload();
        this.transitGraph = new TransitGraph(
                loadedData.stopsById().values(),
                loadedData.lines(),
                loadedData.transferPenalty(),
                loadedData.maxWalkingDistance(),
                loadedData.walkingTransferPolicy()
        );
        restartWalking();
        getLogger().info("Loaded " + loadedData.stopsById().size() + " stops and " + loadedData.lines().size() + " lines.");
    }

    private void restartWalking() {
        stopWalking();
        if (loadedData.walk().enabled()) {
            walkService = new WalkSearchService(this, loadedData.walk());
            walkService.start();
        }
    }

    private void stopWalking() {
        if (walkService != null) {
            walkService.shutdown();
            walkService = null;
        }
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

    /** Null when {@code walking-paths.enabled} is false. */
    public @Nullable WalkSearchService getWalkService() {
        return walkService;
    }

    public LangService getLang() {
        return lang;
    }
}
