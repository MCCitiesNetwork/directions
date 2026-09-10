package com.minecraftcitiesnetwork.directions.command;

import com.minecraftcitiesnetwork.directions.DirectionsPlugin;
import com.minecraftcitiesnetwork.directions.config.ConfigLoader;
import com.minecraftcitiesnetwork.directions.graph.TransitGraph;
import com.minecraftcitiesnetwork.directions.i18n.LangService;
import com.minecraftcitiesnetwork.directions.model.RouteResult;
import com.minecraftcitiesnetwork.directions.model.Stop;
import com.minecraftcitiesnetwork.directions.navigation.Waypoint;
import com.minecraftcitiesnetwork.directions.pathfinding.PathfindingSettings;
import com.minecraftcitiesnetwork.directions.pathfinding.Pos;
import com.minecraftcitiesnetwork.directions.pathfinding.WalkPath;
import com.minecraftcitiesnetwork.directions.pathfinding.WalkResult;
import com.minecraftcitiesnetwork.directions.pathfinding.bukkit.WalkPathService;
import com.minecraftcitiesnetwork.directions.pathfinding.bukkit.WalkingDistanceCache;
import com.minecraftcitiesnetwork.directions.resolver.DestinationResolver;
import com.minecraftcitiesnetwork.directions.resolver.PlayerPositionResolver;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

public class DirectionsCommand {
    private static final String START_NODE = "__start__";
    private static final String DEST_NODE = "__dest__";

    private final DirectionsPlugin plugin;
    private final LangService lang;
    private final RegionSuggestionService suggestionService = new RegionSuggestionService();
    private final DestinationResolver destinationResolver = new DestinationResolver();
    private final PlayerPositionResolver playerPositionResolver = new PlayerPositionResolver();
    /** Latest request per player, so a slow search cannot finish a route the player has since replaced. */
    private final Map<UUID, Integer> requestIds = new HashMap<>();

    public DirectionsCommand(DirectionsPlugin plugin) {
        this.plugin = plugin;
        this.lang = plugin.getLang();
    }

    public void sendUsage(CommandSender sender) {
        lang.send(sender, "command.usage", lang.placeholderRaw("prefix", lang.raw("prefix")));
    }

    public void sendOnlyPlayer(CommandSender sender) {
        lang.send(sender, "command.only-player", lang.placeholderRaw("prefix", lang.raw("prefix")));
    }

    public void reload(CommandSender sender) {
        plugin.reloadDirectionsData();
        lang.send(sender, "command.reloaded", lang.placeholderRaw("prefix", lang.raw("prefix")));
    }

    public void stop(Player player) {
        requestIds.merge(player.getUniqueId(), 1, Integer::sum);
        if (plugin.getNavigationService().stopNavigation(player)) {
            lang.send(player, "command.stopped", lang.placeholderRaw("prefix", lang.raw("prefix")));
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

        lang.send(player, "command.started-header",
                lang.placeholderRaw("prefix", lang.raw("prefix")),
                lang.regionDestination("destination", plugin.getLoadedData(), targetId));

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
        Waypoint finalWaypoint = new Waypoint.Region(destination.requestedRegionId());
        Pos destPos = Pos.floor(destination.x(), destination.y(), destination.z());
        routeAndNavigate(player, regionManager, destination.x(), destination.z(), destPos, finalWaypoint);
    }

    public void startAt(Player player, double x, double z) {
        startAt(player, x, player.getLocation().getY(), z, false);
    }

    public void startAt(Player player, double x, double y, double z) {
        startAt(player, x, y, z, true);
    }

    private void startAt(Player player, double x, double y, double z, boolean includeY) {
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
            lang.send(player, "errors.invalid-coordinates", lang.placeholderRaw("prefix", lang.raw("prefix")));
            return;
        }

        RegionManager regionManager = regionManager(player);
        if (regionManager == null) {
            return;
        }

        Waypoint.Coordinates destination = new Waypoint.Coordinates(x, y, z, includeY);
        lang.send(player, "command.started-header",
                lang.placeholderRaw("prefix", lang.raw("prefix")),
                lang.waypointLabel("destination", plugin.getLoadedData(), destination));

        double arrivalRadius = plugin.getLoadedData().coordinateArrivalRadius();
        if (destination.isWithinRadius(player.getLocation(), arrivalRadius)) {
            lang.send(player, "errors.already-at-coordinates",
                    lang.placeholderRaw("prefix", lang.raw("prefix")),
                    lang.waypointLabel("destination", plugin.getLoadedData(), destination));
            return;
        }

        // Without a Y the search resolves the column's surface itself.
        Pos destPos = Pos.floor(x, includeY ? y : player.getWorld().getMaxHeight() - 1, z);
        routeAndNavigate(player, regionManager, x, z, destPos, destination);
    }

    private void routeAndNavigate(
            Player player,
            RegionManager regionManager,
            double destX,
            double destZ,
            Pos destPos,
            Waypoint finalWaypoint
    ) {
        TransitGraph graph = plugin.getTransitGraph();
        World world = player.getWorld();

        PlayerPositionResolver.StartResolution startResolution =
                playerPositionResolver.resolve(player, regionManager, graph);
        if (startResolution.startEdges().isEmpty()) {
            startGpsFallback(player, finalWaypoint);
            return;
        }

        List<TransitGraph.StopDistance> nearDestination = graph.stopsWithinDistance(destX, destZ, world.getName());
        boolean destinationFallback = nearDestination.isEmpty();
        if (destinationFallback) {
            nearDestination = graph.nearestStops(destX, destZ, world.getName(), 1);
        }
        if (nearDestination.isEmpty()) {
            startGpsFallback(player, finalWaypoint);
            return;
        }

        Map<String, Double> destinationEdges = new HashMap<>();
        for (TransitGraph.StopDistance sd : nearDestination) {
            destinationEdges.put(sd.stopId(), sd.distance());
        }

        WalkPathService service = plugin.getWalkPathService();
        int requestId = requestIds.merge(player.getUniqueId(), 1, Integer::sum);
        if (service == null || !service.settings().routingEnabledIn(world.getName())) {
            finishRoute(player, graph, startResolution, destinationEdges, destinationFallback, destX, destZ, finalWaypoint, Map.of(), Map.of());
            return;
        }

        // Stage two: real walking distances for every candidate leg, all under one budget.
        PathfindingSettings.Routing routing = service.settings().routing();
        Location loc = player.getLocation();
        Pos playerPos = Pos.floor(loc.getX(), loc.getY(), loc.getZ());
        Map<String, CompletableFuture<WalkResult>> startLegs = new HashMap<>();
        Map<String, CompletableFuture<WalkResult>> destLegs = new HashMap<>();
        for (Map.Entry<String, Double> e : startResolution.startEdges().entrySet()) {
            if (e.getValue() <= 0.0 || e.getValue() > routing.maxLegDistance()) {
                continue;
            }
            Pos stopPos = stopPos(world, e.getKey());
            if (stopPos != null) {
                startLegs.put(e.getKey(), service.find(world, playerPos, stopPos, routing.maxLegDistance() * 1.5, routing.timeBudget()));
            }
        }
        for (Map.Entry<String, Double> e : destinationEdges.entrySet()) {
            if (e.getValue() > routing.maxLegDistance()) {
                continue;
            }
            Pos stopPos = stopPos(world, e.getKey());
            if (stopPos != null) {
                destLegs.put(e.getKey(), service.find(world, stopPos, destPos, routing.maxLegDistance() * 1.5, routing.timeBudget()));
            }
        }
        if (startLegs.isEmpty() && destLegs.isEmpty()) {
            finishRoute(player, graph, startResolution, destinationEdges, destinationFallback, destX, destZ, finalWaypoint, Map.of(), Map.of());
            return;
        }

        List<CompletableFuture<WalkResult>> all = new ArrayList<>(startLegs.values());
        all.addAll(destLegs.values());
        CompletableFuture.allOf(all.toArray(new CompletableFuture[0])).whenComplete((v, error) -> service.mainThread().run(() -> {
            if (!player.isOnline() || requestIds.getOrDefault(player.getUniqueId(), -1) != requestId) {
                return;
            }
            Map<String, Double> startEdges = new HashMap<>(startResolution.startEdges());
            Map<String, WalkResult> startResults = new HashMap<>();
            startLegs.forEach((id, f) -> {
                WalkResult r = f.getNow(null);
                if (r != null && r.found()) {
                    startEdges.put(id, r.path().totalDistance());
                    startResults.put(id, r);
                }
            });
            Map<String, Double> destEdges = new HashMap<>(destinationEdges);
            Map<String, WalkResult> destResults = new HashMap<>();
            destLegs.forEach((id, f) -> {
                WalkResult r = f.getNow(null);
                if (r != null && r.found()) {
                    destEdges.put(id, r.path().totalDistance());
                    destResults.put(id, r);
                }
            });
            PlayerPositionResolver.StartResolution refined =
                    new PlayerPositionResolver.StartResolution(startEdges, startResolution.fallbackUsed(), startResolution.currentStopId());
            finishRoute(player, graph, refined, destEdges, destinationFallback, destX, destZ, finalWaypoint, startResults, destResults);
        }));
    }

    /** Standing cell of a stop: the precomputed one, or its centre for the search to resolve. */
    private Pos stopPos(World world, String stopId) {
        WalkingDistanceCache cache = plugin.getWalkingDistanceCache();
        if (cache != null) {
            var known = cache.position(stopId);
            if (known.isPresent()) {
                return known.get();
            }
        }
        Stop stop = plugin.getTransitGraph().getStopsById().get(stopId);
        if (stop == null) {
            return null;
        }
        int y = stop.hasY() ? (int) Math.floor(stop.y()) : world.getMaxHeight() - 1;
        return new Pos((int) Math.floor(stop.x()), y, (int) Math.floor(stop.z()));
    }

    private void finishRoute(
            Player player,
            TransitGraph graph,
            PlayerPositionResolver.StartResolution startResolution,
            Map<String, Double> destinationEdges,
            boolean destinationFallback,
            double destX,
            double destZ,
            Waypoint finalWaypoint,
            Map<String, WalkResult> startResults,
            Map<String, WalkResult> destResults
    ) {
        Location playerLoc = player.getLocation();
        RouteResult route = graph.hasFixedCostLines()
                ? graph.route(START_NODE, DEST_NODE, startResolution.startEdges(), destinationEdges)
                : graph.routeAStar(START_NODE, playerLoc.getX(), playerLoc.getZ(), DEST_NODE, destX, destZ, startResolution.startEdges(), destinationEdges);
        if (route.pathNodes().isEmpty()) {
            startGpsFallback(player, finalWaypoint);
            return;
        }

        List<Waypoint> navWaypoints = buildWaypoints(route.pathNodes(), finalWaypoint);

        if (!hasTransitHop(route.pathNodes(), graph)) {
            sendDirectWalk(player, playerLoc, destX, destZ, finalWaypoint);
            plugin.getNavigationService().startNavigation(player, List.of(finalWaypoint));
            sendStartedNavigation(player, finalWaypoint);
            return;
        }

        WalkPath firstLeg = null;
        if (route.pathNodes().size() > 1) {
            WalkResult r = startResults.get(route.pathNodes().get(1));
            if (r != null && r.found()) {
                firstLeg = r.path();
            }
        }
        plugin.getNavigationService().startNavigation(player, navWaypoints, firstLeg);
        sendStartedNavigation(player, finalWaypoint);
        sendRoute(player, route.pathNodes(), graph, startResolution.fallbackUsed(), destinationFallback, finalWaypoint, startResults);
        sendSwitchToDirectHint(player, finalWaypoint);
    }

    private RegionManager regionManager(Player player) {
        RegionManager regionManager = WorldGuard.getInstance().getPlatform().getRegionContainer()
                .get(BukkitAdapter.adapt(player.getWorld()));
        if (regionManager == null) {
            lang.send(player, "errors.region-manager-unavailable", lang.placeholderRaw("prefix", lang.raw("prefix")));
        }
        return regionManager;
    }

    private void sendRoute(Player player,
                           List<String> nodes,
                           TransitGraph graph,
                           boolean startFallback,
                           boolean destinationFallback,
                           Waypoint finalWaypoint,
                           Map<String, WalkResult> startResults) {
        ConfigLoader.LoadedData loadedData = plugin.getLoadedData();
        List<Step> steps = new ArrayList<>();

        for (int i = 0; i + 1 < nodes.size(); i++) {
            String from = nodes.get(i);
            String to = nodes.get(i + 1);

            if (from.equals(START_NODE)) {
                steps.add(new Step("directions.walk-to-stop",
                        lang.stopName("to", loadedData, to),
                        lang.placeholderRaw("hint", walkHint(player.getLocation(), graph.getStopsById().get(to), startResults.get(to)))));
                continue;
            }
            if (to.equals(DEST_NODE)) {
                Stop stop = graph.getStopsById().get(from);
                if (stop != null) {
                    steps.add(new Step("directions.walk-to-destination",
                            lang.stopName("from", loadedData, stop.regionId()),
                            lang.waypointLabel("destination", loadedData, finalWaypoint)));
                }
                continue;
            }

            if (graph.isTransitHop(from, to)) {
                Set<String> lines = graph.transitLineNames(from, to);
                String lineText = lines.isEmpty()
                        ? "transit"
                        : lines.stream()
                        .map(loadedData::displayLine)
                        .sorted()
                        .collect(Collectors.joining(", "));
                steps.add(new Step("directions.take-line",
                        lang.lineNames("line", lineText),
                        lang.stopName("from", loadedData, from),
                        lang.stopName("to", loadedData, to)));
            } else {
                steps.add(new Step("directions.walk-between-stops",
                        lang.stopName("from", loadedData, from),
                        lang.stopName("to", loadedData, to)));
            }
        }

        int n = 1;
        for (Step step : steps) {
            TagResolver[] withStep = prepend(step.placeholders(), lang.placeholder("step", String.valueOf(n)));
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

    /** "about 140 blocks on foot, heading NE" when a path is known; straight-line distance otherwise. */
    private String walkHint(Location from, Stop to, WalkResult leg) {
        if (to == null) {
            return "?";
        }
        double dx = to.x() - from.getX();
        double dz = to.z() - from.getZ();
        String direction = cardinalFromDelta(dx, dz);
        if (leg != null && leg.found()) {
            return lang.raw("directions.walk-hint-path")
                    .replace("<distance>", String.valueOf(Math.round(leg.path().totalDistance())))
                    .replace("<direction>", direction);
        }
        long blocks = Math.round(Math.sqrt(dx * dx + dz * dz));
        return lang.raw("directions.walk-hint-line")
                .replace("<distance>", String.valueOf(blocks))
                .replace("<direction>", direction);
    }

    private void sendSwitchToDirectHint(Player player, Waypoint destination) {
        String command = directNavigationCommand(destination);
        lang.send(player, "directions.switch-to-direct",
                lang.placeholderRaw("command", command));
    }

    private static String directNavigationCommand(Waypoint destination) {
        return switch (destination) {
            case Waypoint.Region region -> "/gps " + region.id();
            case Waypoint.Coordinates coords -> {
                if (coords.includeY()) {
                    yield "/compass " + formatCoord(coords.x()) + " " + formatCoord(coords.y()) + " " + formatCoord(coords.z());
                }
                yield "/compass " + formatCoord(coords.x()) + " " + formatCoord(coords.z());
            }
        };
    }

    private static String formatCoord(double value) {
        if (Math.rint(value) == value) {
            return String.valueOf((long) value);
        }
        return String.format("%.1f", value);
    }

    private static String cardinalFromDelta(double dx, double dz) {
        double angle = Math.toDegrees(Math.atan2(dx, -dz));
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

    private void sendDirectWalk(Player player, Location from, double toX, double toZ, Waypoint finalWaypoint) {
        double dx = toX - from.getX();
        double dz = toZ - from.getZ();
        double blocks = Math.sqrt(dx * dx + dz * dz);
        lang.send(player, "directions.direct-walk",
                lang.placeholder("step", "1"),
                lang.waypointLabel("destination", plugin.getLoadedData(), finalWaypoint),
                lang.placeholder("distance", String.valueOf(Math.round(blocks))),
                lang.placeholder("direction", cardinalFromDelta(dx, dz)));
    }

    private void sendStartedNavigation(Player player, Waypoint destination) {
        lang.send(player, "command.started-navigation",
                lang.placeholderRaw("prefix", lang.raw("prefix")),
                lang.waypointLabel("destination", plugin.getLoadedData(), destination));
    }

    private void startGpsFallback(Player player, Waypoint finalWaypoint) {
        plugin.getNavigationService().startGpsNavigation(player, List.of(finalWaypoint));
        lang.send(player, "directions.gps-fallback",
                lang.placeholderRaw("prefix", lang.raw("prefix")),
                lang.waypointLabel("destination", plugin.getLoadedData(), finalWaypoint));
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

    private static List<Waypoint> buildWaypoints(List<String> pathNodes, Waypoint finalWaypoint) {
        List<Waypoint> waypoints = new ArrayList<>();
        for (String node : pathNodes) {
            if (node.equals(START_NODE) || node.equals(DEST_NODE)) {
                continue;
            }
            waypoints.add(new Waypoint.Region(node));
        }
        if (waypoints.isEmpty() || !waypoints.getLast().equals(finalWaypoint)) {
            waypoints.add(finalWaypoint);
        }
        return waypoints;
    }
}
