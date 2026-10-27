package com.minecraftcitiesnetwork.directions.navigation;

import com.minecraftcitiesnetwork.directions.DirectionsPlugin;
import com.minecraftcitiesnetwork.directions.i18n.LangService;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

public class NavigationService implements Listener {
    private static final long NAVIGATION_TICK_INTERVAL = 1L;
    private static final int ARROW_TURN_TICKS = 3;
    private static final Color ARROW_GLOW = Color.fromRGB(0x4FD8FF);
    /**
     * The arrow only rides the player on their own client; on the server it is moved back beside them
     * once it falls this far behind, so it stays loaded and tracked.
     */
    private static final double ARROW_LEASH_DISTANCE = 8.0;
    /** Beyond this the arrow is rebuilt beside the player rather than moved there, as after a teleport. */
    private static final double ARROW_REBUILD_DISTANCE = 32.0;

    private final DirectionsPlugin plugin;
    private final LangService lang;
    private final Map<UUID, Session> sessions = new HashMap<>();
    private final ArrowMount arrowMount = new ArrowMount();
    private BukkitTask task;

    public NavigationService(DirectionsPlugin plugin) {
        this.plugin = plugin;
        this.lang = plugin.getLang();
    }

    public void start() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
        arrowMount.register();
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, NAVIGATION_TICK_INTERVAL);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        for (Session session : new ArrayList<>(sessions.values())) {
            destroySession(session);
        }
        sessions.clear();
        arrowMount.unregister();
    }

    public void startNavigation(Player player, List<Waypoint> waypoints) {
        startNavigation(player, waypoints, false);
    }

    public void startGpsNavigation(Player player, List<Waypoint> waypoints) {
        startNavigation(player, waypoints, true);
    }

    private void startNavigation(Player player, List<Waypoint> waypoints, boolean gps) {
        stopNavigation(player);
        if (waypoints.isEmpty()) {
            return;
        }

        BossBar bossBar = Bukkit.createBossBar("Directions", BarColor.BLUE, BarStyle.SEGMENTED_10);
        bossBar.addPlayer(player);

        List<ItemDisplay> arrowBlocks = new ArrayList<>();
        Session session = new Session(plugin, player.getUniqueId(), waypoints, 0, arrowBlocks, bossBar, gps);
        sessions.put(player.getUniqueId(), session);
    }

    public boolean stopNavigation(Player player) {
        Session existing = sessions.remove(player.getUniqueId());
        if (existing != null) {
            destroySession(existing);
            return true;
        }
        return false;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        stopNavigation(event.getPlayer());
    }

    private void tick() {
        for (Session session : new ArrayList<>(sessions.values())) {
            Player player = Bukkit.getPlayer(session.playerId());
            if (player == null || !player.isOnline()) {
                sessions.remove(session.playerId());
                destroySession(session);
                continue;
            }

            RegionManager regionManager = WorldGuard.getInstance().getPlatform().getRegionContainer()
                    .get(BukkitAdapter.adapt(player.getWorld()));
            if (regionManager == null) {
                continue;
            }

            Waypoint currentTarget = session.currentWaypoint();
            if (currentTarget == null) {
                lang.send(player, "navigation.arrived",
                        lang.placeholderRaw("prefix", lang.raw("prefix")),
                        arrivedDestination(session));
                sessions.remove(session.playerId());
                destroySession(session);
                continue;
            }

            boolean isConfiguredStop = switch (currentTarget) {
                case Waypoint.Region regionWaypoint ->
                        plugin.getLoadedData().stopsById().containsKey(regionWaypoint.id());
                case Waypoint.Coordinates __ -> false;
            };
            if (ArrivalPolicy.shouldAdvanceAtWaypoint(
                    session,
                    player,
                    regionManager,
                    currentTarget,
                    isConfiguredStop,
                    plugin.getLoadedData().stopArrivalBuffer(),
                    plugin.getLoadedData().coordinateArrivalRadius(),
                    plugin.getLoadedData().departureClearanceRadius()
            )) {
                session.advance();
                session.resetLegProgress();
                currentTarget = session.currentWaypoint();
                if (currentTarget == null) {
                    lang.send(player, "navigation.arrived",
                            lang.placeholderRaw("prefix", lang.raw("prefix")),
                            arrivedDestination(session));
                    sessions.remove(session.playerId());
                    destroySession(session);
                    continue;
                }
                sendNextStopNotification(player, currentTarget);
            }

            Location targetLoc = targetLocation(player, regionManager, currentTarget);
            if (targetLoc == null) {
                continue;
            }
            maybeSendUpcomingStopHint(player, session, regionManager, currentTarget, targetLoc);
            updateArrow(player, session, targetLoc);
            updateBossbar(session, currentTarget, player.getLocation(), targetLoc);
        }
    }

    private static Location targetLocation(Player player, RegionManager regionManager, Waypoint waypoint) {
        Location playerLoc = player.getLocation();
        return switch (waypoint) {
            case Waypoint.Coordinates coords -> {
                double y = coords.includeY() ? coords.y() : playerLoc.getY();
                yield new Location(playerLoc.getWorld(), coords.x(), y, coords.z());
            }
            case Waypoint.Region region -> {
                ProtectedRegion protectedRegion = regionManager.getRegion(region.id());
                if (protectedRegion == null) {
                    yield null;
                }
                var min = protectedRegion.getMinimumPoint();
                var max = protectedRegion.getMaximumPoint();
                var world = playerLoc.getWorld();
                double tx = (min.x() + max.x()) / 2.0;
                double tz = (min.z() + max.z()) / 2.0;
                // Full-height regions have no meaningful Y, so keep the arrow flat (player's Y).
                // Bounded sub-regions point toward their actual centre height.
                boolean fullHeight = min.y() <= world.getMinHeight() && max.y() >= world.getMaxHeight() - 1;
                double ty = fullHeight ? playerLoc.getY() : (min.y() + max.y()) / 2.0;
                yield new Location(world, tx, ty, tz);
            }
        };
    }

    private void updateArrow(Player player, Session session, Location target) {
        Location playerLoc = player.getLocation();
        Vector toTarget = target.toVector().subtract(playerLoc.toVector());
        if (toTarget.lengthSquared() < 1.0E-8) {
            return;
        }
        Vector pointing = session.arrowPointing() == null
                ? toTarget.normalize()
                : ArrowShape.turnToward(session.arrowPointing(), toTarget);
        session.setArrowPointing(pointing);
        List<Transformation> parts = ArrowShape.parts(pointing, playerLoc.getDirection());
        List<ItemDisplay> displays = session.arrowBlocks();
        // Rotation is cleared: a passenger's own yaw and pitch would turn the whole arrow.
        Location seat = playerLoc.clone();
        seat.setYaw(0F);
        seat.setPitch(0F);

        // Parts are rebuilt rather than repaired when they are gone, in another world, far behind, or
        // no longer shown to the owner: Paper forgets showEntity once an entity stops being tracked.
        displays.removeIf(display -> {
            boolean stale = !display.isValid()
                    || display.getWorld() != player.getWorld()
                    || display.getLocation().distanceSquared(seat) > ARROW_REBUILD_DISTANCE * ARROW_REBUILD_DISTANCE
                    || !player.canSee(display);
            if (stale) {
                removeArrowPart(session, display);
            }
            return stale;
        });
        while (displays.size() < parts.size()) {
            displays.add(spawnArrowPart(player, seat, parts.get(displays.size())));
        }
        for (int i = 0; i < parts.size(); i++) {
            ItemDisplay display = displays.get(i);
            if (display.getLocation().distanceSquared(seat) > ARROW_LEASH_DISTANCE * ARROW_LEASH_DISTANCE) {
                display.teleport(seat);
            }
            if (!display.getTransformation().equals(parts.get(i))) {
                display.setInterpolationDelay(0);
                display.setTransformation(parts.get(i));
            }
        }
    }

    private void updateBossbar(Session session, Waypoint waypoint, Location from, Location target) {
        double remaining = switch (waypoint) {
            case Waypoint.Coordinates coords -> ArrivalPolicy.distanceToCoordinate(from, coords);
            case Waypoint.Region region -> {
                RegionManager regionManager = WorldGuard.getInstance().getPlatform().getRegionContainer()
                        .get(BukkitAdapter.adapt(from.getWorld()));
                if (regionManager == null) {
                    yield 0.0;
                }
                ProtectedRegion protectedRegion = regionManager.getRegion(region.id());
                if (protectedRegion == null) {
                    yield 0.0;
                }
                yield ArrivalPolicy.distanceToRegion(from, protectedRegion, false);
            }
        };
        if (session.legStartDistance() < 0 || remaining > session.legStartDistance()) {
            session.setLegStartDistance(remaining);
        }
        double start = session.legStartDistance();
        double progress = start <= 0.0001 ? 1.0 : 1.0 - Math.min(remaining / start, 1.0);
        session.bossBar().setProgress(Math.max(0.0, Math.min(1.0, progress)));
        String format = session.gps()
                ? session.plugin().getLoadedData().gpsBossbarFormat()
                : session.plugin().getLoadedData().bossbarFormat();
        session.bossBar().setTitle(LegacyComponentSerializer.legacySection().serialize(lang.deserialize(format,
                lang.waypointLabel("stop", session.plugin().getLoadedData(), waypoint),
                Placeholder.unparsed("remaining", String.valueOf(Math.round(remaining))))));
    }

    private void sendNextStopNotification(Player player, Waypoint currentTarget) {
        Session session = sessions.get(player.getUniqueId());
        boolean isFinalWaypoint = session == null || session.nextWaypoint() == null;
        var loadedData = plugin.getLoadedData();
        if (isFinalWaypoint) {
            lang.send(player, "navigation.next-destination",
                    lang.placeholderRaw("prefix", lang.raw("prefix")),
                    lang.waypointLabel("stop", loadedData, currentTarget));
            return;
        }
        String lineText = currentLegLineText(player, currentTarget);
        if (lineText == null) {
            lang.send(player, "navigation.next-stop",
                    lang.placeholderRaw("prefix", lang.raw("prefix")),
                    lang.waypointLabel("stop", loadedData, currentTarget));
            return;
        }
        lang.send(player, "navigation.next-stop-with-line",
                lang.placeholderRaw("prefix", lang.raw("prefix")),
                lang.waypointLabel("stop", loadedData, currentTarget),
                lang.lineNames("line", lineText));
    }

    private void maybeSendUpcomingStopHint(
            Player player,
            Session session,
            RegionManager regionManager,
            Waypoint currentTarget,
            Location currentTargetLoc
    ) {
        Waypoint next = session.nextWaypoint();
        if (next == null || session.upcomingHintSentForIndex() == session.index()) {
            return;
        }
        double remaining = distanceToCurrentLeg(player.getLocation(), regionManager, currentTarget, currentTargetLoc);
        if (remaining <= plugin.getLoadedData().nextStopNotifyDistance()) {
            Waypoint current = session.currentWaypoint();
            String lineText = (current == null) ? null : lineTextBetween(current, next);
            var loadedData = plugin.getLoadedData();
            if (session.nextWaypointAfterNext() == null) {
                lang.send(player, "navigation.upcoming-destination",
                        lang.placeholderRaw("prefix", lang.raw("prefix")),
                        lang.waypointLabel("stop", loadedData, next));
            } else if (lineText == null) {
                lang.send(player, "navigation.upcoming-next-stop",
                        lang.placeholderRaw("prefix", lang.raw("prefix")),
                        lang.waypointLabel("stop", loadedData, next));
            } else {
                lang.send(player, "navigation.upcoming-next-stop-with-line",
                        lang.placeholderRaw("prefix", lang.raw("prefix")),
                        lang.waypointLabel("stop", loadedData, next),
                        lang.lineNames("line", lineText));
            }
            session.setUpcomingHintSentForIndex(session.index());
        }
    }

    private static double distanceToCurrentLeg(
            Location from,
            RegionManager regionManager,
            Waypoint currentTarget,
            Location currentTargetLoc
    ) {
        return switch (currentTarget) {
            case Waypoint.Coordinates coords -> ArrivalPolicy.distanceToCoordinate(from, coords);
            case Waypoint.Region region -> {
                ProtectedRegion protectedRegion = regionManager.getRegion(region.id());
                if (protectedRegion == null) {
                    yield from.distance(currentTargetLoc);
                }
                yield ArrivalPolicy.distanceToRegion(from, protectedRegion, false);
            }
        };
    }

    private String currentLegLineText(Player player, Waypoint currentTarget) {
        Session session = sessions.get(player.getUniqueId());
        if (session == null) {
            return null;
        }
        Waypoint previous = session.previousWaypoint();
        if (previous == null) {
            return null;
        }
        return lineTextBetween(previous, currentTarget);
    }

    private String lineTextBetween(Waypoint from, Waypoint to) {
        return switch (from) {
            case Waypoint.Region fromRegion -> switch (to) {
                case Waypoint.Region toRegion -> lineTextBetweenStops(fromRegion.id(), toRegion.id());
                case Waypoint.Coordinates __ -> null;
            };
            case Waypoint.Coordinates __ -> null;
        };
    }

    private String lineTextBetweenStops(String fromStop, String toStop) {
        Set<String> ids = plugin.getTransitGraph().transitLineNames(fromStop, toStop);
        if (ids.isEmpty()) {
            return null;
        }
        return ids.stream()
                .map(plugin.getLoadedData()::displayLine)
                .sorted()
                .collect(Collectors.joining(", "));
    }

    private TagResolver arrivedDestination(Session session) {
        Waypoint destination = session.previousWaypoint();
        if (destination == null) {
            return lang.placeholder("destination", "destination");
        }
        return lang.waypointLabel("destination", plugin.getLoadedData(), destination);
    }

    private void destroySession(Session session) {
        for (ItemDisplay display : session.arrowBlocks()) {
            removeArrowPart(session, display);
        }
        session.arrowBlocks().clear();
        if (session.bossBar() != null) {
            session.bossBar().removeAll();
        }
    }

    /** Spawns one bar of the arrow, shown only to the navigating player and riding them on their screen. */
    private ItemDisplay spawnArrowPart(Player player, Location seat, Transformation part) {
        ItemDisplay spawned = player.getWorld().spawn(seat, ItemDisplay.class, display -> {
            display.setItemStack(new ItemStack(Material.SMOOTH_QUARTZ));
            display.setTransformation(part);
            display.setBillboard(Display.Billboard.FIXED);
            display.setBrightness(new Display.Brightness(15, 15));
            display.setInterpolationDuration(ARROW_TURN_TICKS);
            display.setGlowing(true);
            display.setGlowColorOverride(ARROW_GLOW);
            display.setPersistent(false);
            display.setVisibleByDefault(false);
        });
        // Recorded before the player is sent the entity, so the mount sees it arrive.
        arrowMount.add(player, spawned);
        player.showEntity(plugin, spawned);
        return spawned;
    }

    private void removeArrowPart(Session session, ItemDisplay display) {
        arrowMount.remove(session.playerId(), display);
        display.remove();
    }
}
