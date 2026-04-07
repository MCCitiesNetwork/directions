package com.minecraftcitiesnetwork.directions.command;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import com.minecraftcitiesnetwork.directions.DirectionsPlugin;
import com.minecraftcitiesnetwork.directions.graph.TransitGraph;
import com.minecraftcitiesnetwork.directions.i18n.LangService;
import com.minecraftcitiesnetwork.directions.model.RouteResult;
import com.minecraftcitiesnetwork.directions.model.Stop;
import com.minecraftcitiesnetwork.directions.resolver.DestinationResolver;
import com.minecraftcitiesnetwork.directions.resolver.PlayerPositionResolver;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public class DirectionsCommand {
    private static final String START_NODE = "__start__";
    private static final String DEST_NODE = "__dest__";

    private final DirectionsPlugin plugin;
    private final LangService lang;
    private final RegionSuggestionService suggestionService = new RegionSuggestionService();
    private final DestinationResolver destinationResolver = new DestinationResolver();
    private final PlayerPositionResolver playerPositionResolver = new PlayerPositionResolver();

    public DirectionsCommand(DirectionsPlugin plugin) {
        this.plugin = plugin;
        this.lang = plugin.getLang();
    }

    public void sendUsage(CommandSender sender) {
        lang.send(sender, "command.usage", lang.pRaw("prefix", lang.raw("prefix")));
    }

    public void sendOnlyPlayer(CommandSender sender) {
        lang.send(sender, "command.only-player", lang.pRaw("prefix", lang.raw("prefix")));
    }

    public void reload(CommandSender sender) {
        plugin.reloadDirectionsData();
        lang.send(sender, "command.reloaded", lang.pRaw("prefix", lang.raw("prefix")));
    }

    public void stop(Player player) {
        plugin.getNavigationService().stopNavigation(player);
        lang.send(player, "command.stopped", lang.pRaw("prefix", lang.raw("prefix")));
    }

    public void start(Player player, String targetIdRaw) {
        String targetId = targetIdRaw.toLowerCase(Locale.ROOT);
        TransitGraph graph = plugin.getTransitGraph();
        RegionManager regionManager = WorldGuard.getInstance().getPlatform().getRegionContainer()
                .get(BukkitAdapter.adapt(player.getWorld()));
        if (regionManager == null) {
            lang.send(player, "errors.region-manager-unavailable", lang.pRaw("prefix", lang.raw("prefix")));
            return;
        }

        ProtectedRegion target = regionManager.getRegion(targetId);
        if (target == null) {
            lang.send(player, "errors.region-not-found",
                    lang.pRaw("prefix", lang.raw("prefix")),
                    lang.p("region", targetId));
            return;
        }

        lang.send(player, "command.started-header",
                lang.pRaw("prefix", lang.raw("prefix")),
                lang.p("destination", displayDestination(targetId)));

        if (regionManager.getApplicableRegions(BukkitAdapter.asBlockVector(player.getLocation()))
                .getRegions()
                .stream()
                .anyMatch(r -> r.getId().equalsIgnoreCase(targetId))) {
            lang.send(player, "errors.already-in-region",
                    lang.pRaw("prefix", lang.raw("prefix")),
                    lang.p("region", displayStop(targetId)));
            return;
        }

        DestinationResolver.Destination destination = destinationResolver.resolve(target);
        // Routing anchor may use a parent region centroid for transit stop lookup,
        // but navigation completion must always target the originally requested region.
        ProtectedRegion navRegion = regionManager.getRegion(destination.navigationRegionId());
        if (navRegion == null) {
            lang.send(player, "errors.destination-unavailable", lang.pRaw("prefix", lang.raw("prefix")));
            return;
        }

        PlayerPositionResolver.StartResolution startResolution =
                playerPositionResolver.resolve(player, regionManager, graph);
        if (startResolution.startEdges().isEmpty()) {
            lang.send(player, "errors.no-stops-in-world", lang.pRaw("prefix", lang.raw("prefix")));
            return;
        }

        double destX = (navRegion.getMinimumPoint().x() + navRegion.getMaximumPoint().x()) / 2.0;
        double destZ = (navRegion.getMinimumPoint().z() + navRegion.getMaximumPoint().z()) / 2.0;
        List<TransitGraph.StopDistance> nearDestination = graph.stopsWithinDistance(destX, destZ, player.getWorld().getName());
        boolean destinationFallback = nearDestination.isEmpty();
        if (destinationFallback) {
            nearDestination = graph.nearestStops(destX, destZ, player.getWorld().getName(), 1);
        }
        if (nearDestination.isEmpty()) {
            lang.send(player, "errors.no-destination-stop", lang.pRaw("prefix", lang.raw("prefix")));
            return;
        }

        Map<String, Double> destinationEdges = new HashMap<>();
        for (TransitGraph.StopDistance sd : nearDestination) {
            destinationEdges.put(sd.stopId(), sd.distance());
        }

        Location playerLoc = player.getLocation();
        RouteResult route = graph.routeAStar(
                START_NODE,
                playerLoc.getX(),
                playerLoc.getZ(),
                DEST_NODE,
                destX,
                destZ,
                startResolution.startEdges(),
                destinationEdges
        );
        if (route.pathNodes().isEmpty()) {
            lang.send(player, "errors.no-route",
                    lang.pRaw("prefix", lang.raw("prefix")),
                    lang.p("region", displayDestination(targetId)));
            return;
        }

        if (!hasTransitHop(route.pathNodes(), graph)) {
            sendDirectWalk(player, playerLoc, destX, destZ, displayDestination(targetId));
            plugin.getNavigationService().startNavigation(player, List.of(destination.requestedRegionId()));
            return;
        }

        plugin.getNavigationService().startNavigation(
                player,
                buildWaypointRegions(route.pathNodes(), destination.requestedRegionId())
        );
        sendRoute(player, route.pathNodes(), graph, startResolution.fallbackUsed(), destinationFallback, displayDestination(targetId));
    }

    private void sendRoute(Player player,
                           List<String> nodes,
                           TransitGraph graph,
                           boolean startFallback,
                           boolean destinationFallback,
                           String destinationName) {
        List<Step> steps = new ArrayList<>();

        for (int i = 0; i + 1 < nodes.size(); i++) {
            String from = nodes.get(i);
            String to = nodes.get(i + 1);

            if (from.equals(START_NODE)) {
                steps.add(new Step("directions.walk-to-stop",
                        lang.p("to", displayStop(to)),
                        lang.p("hint", directionHint(player.getLocation(), graph.getStopsById().get(to)))));
                continue;
            }
            if (to.equals(DEST_NODE)) {
                Stop stop = graph.getStopsById().get(from);
                if (stop != null) {
                    steps.add(new Step("directions.walk-to-destination",
                            lang.p("from", displayStop(stop.regionId())),
                            lang.p("destination", destinationName)));
                }
                continue;
            }

            if (graph.isTransitHop(from, to)) {
                Set<String> lines = graph.transitLineNames(from, to);
                String lineText = lines.isEmpty()
                        ? "transit"
                        : lines.stream()
                        .map(plugin.getLoadedData()::displayLine)
                        .sorted()
                        .collect(Collectors.joining(", "));
                steps.add(new Step("directions.take-line",
                        lang.p("line", lineText),
                        lang.p("from", displayStop(from)),
                        lang.p("to", displayStop(to))));
            } else {
                steps.add(new Step("directions.walk-between-stops",
                        lang.p("from", displayStop(from)),
                        lang.p("to", displayStop(to))));
            }
        }

        int n = 1;
        for (Step step : steps) {
            TagResolver[] withStep = prepend(step.placeholders(), lang.p("step", String.valueOf(n)));
            lang.send(player, step.key(), withStep);
            n++;
        }

        if (startFallback) {
            lang.send(player, "directions.warn-start-fallback");
        }
        if (destinationFallback) {
            lang.send(player, "directions.warn-destination-fallback");
        }
    }

    private String displayStop(String stopId) {
        return plugin.getLoadedData().displayStop(stopId);
    }

    private String displayDestination(String regionId) {
        if (plugin.getLoadedData().stopsById().containsKey(regionId)) {
            return displayStop(regionId);
        }
        return regionId;
    }

    private static String directionHint(Location from, Stop to) {
        if (to == null) {
            return "?";
        }
        double dx = to.x() - from.getX();
        double dz = to.z() - from.getZ();
        double blocks = Math.sqrt(dx * dx + dz * dz);
        return Math.round(blocks) + " blocks " + cardinalFromDelta(dx, dz);
    }

    private static String cardinalFromDelta(double dx, double dz) {
        double angle = Math.toDegrees(Math.atan2(-dx, dz));
        if (angle < 0) {
            angle += 360;
        }
        String[] dirs = {"N", "NE", "E", "SE", "S", "SW", "W", "NW"};
        int idx = (int) Math.round(angle / 45.0) % 8;
        return dirs[idx];
    }

    private static boolean hasTransitHop(List<String> nodes, TransitGraph graph) {
        for (int i = 0; i + 1 < nodes.size(); i++) {
            if (graph.isTransitHop(nodes.get(i), nodes.get(i + 1))) {
                return true;
            }
        }
        return false;
    }

    private void sendDirectWalk(Player player, Location from, double toX, double toZ, String destinationName) {
        double dx = toX - from.getX();
        double dz = toZ - from.getZ();
        double blocks = Math.sqrt(dx * dx + dz * dz);
        lang.send(player, "directions.direct-walk",
                lang.p("step", "1"),
                lang.p("destination", destinationName),
                lang.p("distance", String.valueOf(Math.round(blocks))),
                lang.p("direction", cardinalFromDelta(dx, dz)));
    }

    private static TagResolver[] prepend(TagResolver[] rest, TagResolver first) {
        TagResolver[] out = new TagResolver[rest.length + 1];
        out[0] = first;
        System.arraycopy(rest, 0, out, 1, rest.length);
        return out;
    }

    private record Step(String key, TagResolver... placeholders) {
    }

    public RegionSuggestionService suggestions() {
        return suggestionService;
    }

    private static List<String> buildWaypointRegions(List<String> pathNodes, String destinationRegionId) {
        List<String> waypoints = new ArrayList<>();
        for (String node : pathNodes) {
            if (node.equals(START_NODE) || node.equals(DEST_NODE)) {
                continue;
            }
            waypoints.add(node);
        }
        if (waypoints.isEmpty() || !waypoints.getLast().equalsIgnoreCase(destinationRegionId)) {
            waypoints.add(destinationRegionId);
        }
        return waypoints;
    }
}
