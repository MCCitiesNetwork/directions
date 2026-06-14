package com.minecraftcitiesnetwork.directions.command;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import com.minecraftcitiesnetwork.directions.DirectionsPlugin;
import com.minecraftcitiesnetwork.directions.i18n.LangService;
import com.minecraftcitiesnetwork.directions.navigation.Waypoint;
import com.minecraftcitiesnetwork.directions.resolver.DestinationResolver;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Locale;

public class GpsCommand {
    private final DirectionsPlugin plugin;
    private final LangService lang;
    private final RegionSuggestionService suggestionService = new RegionSuggestionService();
    private final DestinationResolver destinationResolver = new DestinationResolver();

    public GpsCommand(DirectionsPlugin plugin) {
        this.plugin = plugin;
        this.lang = plugin.getLang();
    }

    public void sendUsage(CommandSender sender) {
        lang.send(sender, "gps.usage", lang.placeholderRaw("prefix", lang.raw("prefix")));
    }

    public void sendOnlyPlayer(CommandSender sender) {
        lang.send(sender, "command.only-player", lang.placeholderRaw("prefix", lang.raw("prefix")));
    }

    public void stop(Player player) {
        if (plugin.getNavigationService().stopNavigation(player)) {
            lang.send(player, "gps.stopped", lang.placeholderRaw("prefix", lang.raw("prefix")));
        }
    }

    public void start(Player player, String targetIdRaw) {
        String targetId = targetIdRaw.toLowerCase(Locale.ROOT);
        RegionManager regionManager = regionManager(player);
        if (regionManager == null) {
            return;
        }

        ProtectedRegion target = regionManager.getRegion(targetId);
        if (target == null) {
            lang.send(player, "errors.region-not-found",
                    lang.placeholderRaw("prefix", lang.raw("prefix")),
                    lang.placeholder("region", targetId));
            return;
        }

        if (regionManager.getApplicableRegions(BukkitAdapter.asBlockVector(player.getLocation()))
                .getRegions()
                .stream()
                .anyMatch(r -> r.getId().equalsIgnoreCase(targetId))) {
            lang.send(player, "errors.already-in-region",
                    lang.placeholderRaw("prefix", lang.raw("prefix")),
                    lang.stopName("region", plugin.getLoadedData(), targetId));
            return;
        }

        DestinationResolver.Destination destination = destinationResolver.resolve(target);
        ProtectedRegion navRegion = regionManager.getRegion(destination.navigationRegionId());
        if (navRegion == null) {
            lang.send(player, "errors.destination-unavailable", lang.placeholderRaw("prefix", lang.raw("prefix")));
            return;
        }

        Waypoint finalWaypoint = new Waypoint.Region(destination.requestedRegionId());
        startDirectNavigation(player, finalWaypoint);
    }

    public RegionSuggestionService suggestions() {
        return suggestionService;
    }

    private void startDirectNavigation(Player player, Waypoint destination) {
        plugin.getNavigationService().startGpsNavigation(player, List.of(destination));
        lang.send(player, "gps.started",
                lang.placeholderRaw("prefix", lang.raw("prefix")),
                lang.waypointLabel("destination", plugin.getLoadedData(), destination));
    }

    private RegionManager regionManager(Player player) {
        RegionManager regionManager = WorldGuard.getInstance().getPlatform().getRegionContainer()
                .get(BukkitAdapter.adapt(player.getWorld()));
        if (regionManager == null) {
            lang.send(player, "errors.region-manager-unavailable", lang.placeholderRaw("prefix", lang.raw("prefix")));
        }
        return regionManager;
    }
}