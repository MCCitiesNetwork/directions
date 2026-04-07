package com.minecraftcitiesnetwork.directions;

import com.minecraftcitiesnetwork.directions.command.DirectionsCommand;
import com.minecraftcitiesnetwork.directions.command.DirectionsBrigadierRegistrar;
import com.minecraftcitiesnetwork.directions.config.ConfigLoader;
import com.minecraftcitiesnetwork.directions.graph.TransitGraph;
import com.minecraftcitiesnetwork.directions.i18n.LangService;
import com.minecraftcitiesnetwork.directions.navigation.NavigationService;
import org.bukkit.plugin.java.JavaPlugin;

public class DirectionsPlugin extends JavaPlugin {
    private ConfigLoader.LoadedData loadedData;
    private TransitGraph transitGraph;
    private NavigationService navigationService;
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
    }

    @Override
    public void onDisable() {
        if (navigationService != null) {
            navigationService.stop();
        }
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
        getLogger().info("Loaded " + loadedData.stopsById().size() + " stops and " + loadedData.lines().size() + " lines.");
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
}
