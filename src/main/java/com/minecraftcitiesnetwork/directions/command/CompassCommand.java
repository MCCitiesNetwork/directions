package com.minecraftcitiesnetwork.directions.command;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.minecraftcitiesnetwork.directions.DirectionsPlugin;
import com.minecraftcitiesnetwork.directions.i18n.LangService;
import com.minecraftcitiesnetwork.directions.navigation.Waypoint;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;

public class CompassCommand {
    private final DirectionsPlugin plugin;
    private final LangService lang;
    private final DirectionsCommand directionsCommand;

    public CompassCommand(DirectionsPlugin plugin, DirectionsCommand directionsCommand) {
        this.plugin = plugin;
        this.lang = plugin.getLang();
        this.directionsCommand = directionsCommand;
    }

    public void sendUsage(CommandSender sender) {
        lang.send(sender, "compass.usage", lang.placeholderRaw("prefix", lang.raw("prefix")));
    }

    public void sendOnlyPlayer(CommandSender sender) {
        lang.send(sender, "command.only-player", lang.placeholderRaw("prefix", lang.raw("prefix")));
    }

    public void stop(Player player) {
        if (plugin.getNavigationService().stopNavigation(player)) {
            lang.send(player, "compass.stopped", lang.placeholderRaw("prefix", lang.raw("prefix")));
        }
    }

    public void start(Player player, double x, double z) {
        start(player, x, player.getLocation().getY(), z, false);
    }

    public void start(Player player, double x, double y, double z) {
        start(player, x, y, z, true);
    }

    public void startTransit(Player player, double x, double z) {
        directionsCommand.startAt(player, x, z);
    }

    public void startTransit(Player player, double x, double y, double z) {
        directionsCommand.startAt(player, x, y, z);
    }

    private void start(Player player, double x, double y, double z, boolean includeY) {
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
            lang.send(player, "errors.invalid-coordinates", lang.placeholderRaw("prefix", lang.raw("prefix")));
            return;
        }

        if (regionManager(player) == null) {
            return;
        }

        Waypoint.Coordinates destination = new Waypoint.Coordinates(x, y, z, includeY);
        double arrivalRadius = plugin.getLoadedData().coordinateArrivalRadius();
        if (destination.isWithinRadius(player.getLocation(), arrivalRadius)) {
            lang.send(player, "errors.already-at-coordinates",
                    lang.placeholderRaw("prefix", lang.raw("prefix")),
                    lang.waypointLabel("destination", plugin.getLoadedData(), destination));
            return;
        }

        plugin.getNavigationService().startGpsNavigation(player, List.of(destination));
        lang.send(player, "compass.started",
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
