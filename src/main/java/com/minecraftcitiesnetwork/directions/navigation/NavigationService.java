package com.minecraftcitiesnetwork.directions.navigation;

import com.minecraftcitiesnetwork.directions.DirectionsPlugin;
import com.minecraftcitiesnetwork.directions.i18n.LangService;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.EulerAngle;
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
    private static final double ARROW_HORIZONTAL_OFFSET = 6.0;
    private static final double ARROW_VERTICAL_OFFSET = 2.5;
    private static final double ARROW_DENSITY = 0.3;
    private static final double ARROW_LENGTH = 2.1;
    private static final double ARROW_HEAD_LENGTH = 0.9;
    private static final double ARROW_HEAD_OFFSET = 0.3;

    private final DirectionsPlugin plugin;
    private final LangService lang;
    private final Map<UUID, Session> sessions = new HashMap<>();
    private BukkitTask task;

    public NavigationService(DirectionsPlugin plugin) {
        this.plugin = plugin;
        this.lang = plugin.getLang();
    }

    public void start() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
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
    }

    public void startNavigation(Player player, List<Waypoint> waypoints) {
        stopNavigation(player);
        if (waypoints.isEmpty()) {
            return;
        }

        BossBar bossBar = Bukkit.createBossBar("Directions", BarColor.BLUE, BarStyle.SEGMENTED_10);
        bossBar.addPlayer(player);

        List<ArmorStand> arrowStands = new ArrayList<>();
        Session session = new Session(plugin, player.getUniqueId(), waypoints, 0, arrowStands, bossBar);
        sessions.put(player.getUniqueId(), session);
        applyVisibility(session);
        lang.send(player, "command.started-navigation", lang.placeholderRaw("prefix", lang.raw("prefix")));
    }

    public void stopNavigation(Player player) {
        Session existing = sessions.remove(player.getUniqueId());
        if (existing != null) {
            destroySession(existing);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        stopNavigation(event.getPlayer());
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        for (Session session : sessions.values()) {
            for (ArmorStand stand : session.arrowStands()) {
                event.getPlayer().hideEntity(plugin, stand);
            }
        }
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
                        lang.placeholder("destination", arrivedDestination(session)));
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
                            lang.placeholder("destination", arrivedDestination(session)));
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
        return switch (waypoint) {
            case Waypoint.Coordinates coords ->
                    new Location(player.getWorld(), coords.x(), coords.y(), coords.z());
            case Waypoint.Region region -> {
                ProtectedRegion protectedRegion = regionManager.getRegion(region.id());
                if (protectedRegion == null) {
                    yield null;
                }
                double tx = (protectedRegion.getMinimumPoint().x() + protectedRegion.getMaximumPoint().x()) / 2.0;
                double ty = (protectedRegion.getMinimumPoint().y() + protectedRegion.getMaximumPoint().y()) / 2.0;
                double tz = (protectedRegion.getMinimumPoint().z() + protectedRegion.getMaximumPoint().z()) / 2.0;
                yield new Location(player.getWorld(), tx, ty, tz);
            }
        };
    }

    private static void updateArrow(Player player, Session session, Location target) {
        Vector dir = target.toVector().subtract(player.getLocation().toVector());
        if (dir.lengthSquared() < 0.0001) {
            return;
        }
        List<ArrowComponent> components = computeArrowComponents(player, target);
        ensureStandCount(player, session, components.size());
        List<ArmorStand> stands = session.arrowStands();
        int max = Math.min(components.size(), stands.size());
        for (int i = 0; i < max; i++) {
            ArrowComponent c = components.get(i);
            ArmorStand stand = stands.get(i);
            stand.teleport(c.location());
            stand.setRotation(c.location().getYaw(), c.location().getPitch());
            setStandPose(stand, c.type(), c.location().getPitch());
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
                boolean includeY = !session.plugin().getLoadedData().stopsById().containsKey(region.id());
                yield ArrivalPolicy.distanceToRegion(from, protectedRegion, includeY);
            }
        };
        if (session.legStartDistance() < 0 || remaining > session.legStartDistance()) {
            session.setLegStartDistance(remaining);
        }
        double start = session.legStartDistance();
        double progress = start <= 0.0001 ? 1.0 : 1.0 - Math.min(remaining / start, 1.0);
        session.bossBar().setProgress(Math.max(0.0, Math.min(1.0, progress)));
        String label = waypoint.displayLabel(session.plugin().getLoadedData());
        String title = session.plugin().getLoadedData().bossbarFormat()
                .replace("<stop>", label)
                .replace("<remaining>", String.valueOf(Math.round(remaining)));
        session.bossBar().setTitle(title);
    }

    private void sendNextStopNotification(Player player, Waypoint currentTarget) {
        Session session = sessions.get(player.getUniqueId());
        boolean isFinalWaypoint = session == null || session.nextWaypoint() == null;
        String label = currentTarget.displayLabel(plugin.getLoadedData());
        if (isFinalWaypoint) {
            lang.send(player, "navigation.next-destination",
                    lang.placeholderRaw("prefix", lang.raw("prefix")),
                    lang.placeholder("stop", label));
            return;
        }
        String lineText = currentLegLineText(player, currentTarget);
        if (lineText == null) {
            lang.send(player, "navigation.next-stop",
                    lang.placeholderRaw("prefix", lang.raw("prefix")),
                    lang.placeholder("stop", label));
            return;
        }
        lang.send(player, "navigation.next-stop-with-line",
                lang.placeholderRaw("prefix", lang.raw("prefix")),
                lang.placeholder("stop", label),
                lang.placeholder("line", lineText));
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
            String nextLabel = next.displayLabel(plugin.getLoadedData());
            if (session.nextWaypointAfterNext() == null) {
                lang.send(player, "navigation.upcoming-destination",
                        lang.placeholderRaw("prefix", lang.raw("prefix")),
                        lang.placeholder("stop", nextLabel));
            } else if (lineText == null) {
                lang.send(player, "navigation.upcoming-next-stop",
                        lang.placeholderRaw("prefix", lang.raw("prefix")),
                        lang.placeholder("stop", nextLabel));
            } else {
                lang.send(player, "navigation.upcoming-next-stop-with-line",
                        lang.placeholderRaw("prefix", lang.raw("prefix")),
                        lang.placeholder("stop", nextLabel),
                        lang.placeholder("line", lineText));
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

    private String arrivedDestination(Session session) {
        Waypoint destination = session.previousWaypoint();
        if (destination == null) {
            return "destination";
        }
        return destination.displayLabel(plugin.getLoadedData());
    }

    private void destroySession(Session session) {
        for (ArmorStand stand : session.arrowStands()) {
            if (stand != null && !stand.isDead()) {
                stand.remove();
            }
        }
        if (session.bossBar() != null) {
            session.bossBar().removeAll();
        }
    }

    private static void ensureStandCount(Player player, Session session, int needed) {
        List<ArmorStand> stands = session.arrowStands();
        if (stands.size() < needed) {
            stands.addAll(spawnAdditionalStands(player, needed - stands.size()));
        } else if (stands.size() > needed) {
            for (int i = stands.size() - 1; i >= needed; i--) {
                ArmorStand stand = stands.remove(i);
                if (stand != null && !stand.isDead()) {
                    stand.remove();
                }
            }
        }
    }

    private static List<ArmorStand> spawnAdditionalStands(Player player, int count) {
        List<ArmorStand> created = new ArrayList<>();
        Location spawnLoc = player.getLocation().clone().add(0, 1.0, 0);
        for (int i = 0; i < count; i++) {
            ArmorStand stand = (ArmorStand) player.getWorld().spawnEntity(spawnLoc, EntityType.ARMOR_STAND);
            stand.setGravity(false);
            stand.setMarker(true);
            stand.setSmall(true);
            stand.setVisible(false);
            stand.setInvulnerable(true);
            stand.setPersistent(false);
            stand.getEquipment().setHelmet(new ItemStack(Material.QUARTZ_BLOCK));
            created.add(stand);
        }
        return created;
    }

    private void applyVisibility(Session session) {
        Player owner = Bukkit.getPlayer(session.playerId());
        if (owner == null) {
            return;
        }
        for (Player online : Bukkit.getOnlinePlayers()) {
            for (ArmorStand stand : session.arrowStands()) {
                if (online.getUniqueId().equals(owner.getUniqueId())) {
                    online.showEntity(plugin, stand);
                } else {
                    online.hideEntity(plugin, stand);
                }
            }
        }
    }

    private static List<ArrowComponent> computeArrowComponents(Player player, Location target) {
        Location playerLoc = player.getLocation();
        Location anchor = new Location(
                player.getWorld(),
                playerLoc.getBlockX() + 0.5,
                playerLoc.getY() + ARROW_VERTICAL_OFFSET,
                playerLoc.getBlockZ() + 0.5
        );
        anchor.setDirection(playerLoc.getDirection());

        Vector forward = anchor.getDirection().normalize();
        Location centered = anchor.add(forward.multiply(ARROW_HORIZONTAL_OFFSET));
        centered.setDirection(target.toVector().subtract(playerLoc.toVector()));

        Location shaftStart = centered.clone().subtract(centered.getDirection().multiply(ARROW_LENGTH / 2.0));
        Vector shaftStep = centered.getDirection().clone().multiply(ARROW_DENSITY);

        List<ArrowComponent> out = new ArrayList<>();
        Location cursor = shaftStart.clone();
        for (double d = 0.0; d < ARROW_LENGTH; d += ARROW_DENSITY) {
            cursor = cursor.add(shaftStep);
            out.add(new ArrowComponent(cursor.clone(), ComponentType.SHAFT));
        }
        if (out.isEmpty()) {
            return out;
        }

        Location headBase = out.getLast().location();
        out.removeLast();
        out.addAll(computeHead(headBase));
        return out;
    }

    private static List<ArrowComponent> computeHead(Location tip) {
        List<ArrowComponent> head = new ArrayList<>();
        head.addAll(computeHeadBranch(tip, 45, ComponentType.HEAD_RIGHT));
        head.addAll(computeHeadBranch(tip, -45, ComponentType.HEAD_LEFT));
        return head;
    }

    private static List<ArrowComponent> computeHeadBranch(Location tip, float yawOffset, ComponentType type) {
        List<ArrowComponent> branch = new ArrayList<>();
        Location branchLoc = tip.clone();
        double pitchAdjusted = branchLoc.getPitch() - branchLoc.getPitch() * 0.5;
        double pitchBias = yawOffset > 0 ? branchLoc.getPitch() * 0.45 : -(branchLoc.getPitch() * 0.45);
        double adjustedYaw = branchLoc.getYaw() + yawOffset + (pitchAdjusted < 0 ? -pitchBias : pitchBias);
        branchLoc.setPitch((float) pitchAdjusted);
        branchLoc.setYaw((float) adjustedYaw);
        Vector step = branchLoc.getDirection().multiply(ARROW_DENSITY);
        branchLoc.subtract(branchLoc.getDirection().multiply(ARROW_HEAD_LENGTH + ARROW_HEAD_OFFSET));
        for (double d = 0.0; d < ARROW_HEAD_LENGTH; d += ARROW_DENSITY) {
            branchLoc = branchLoc.add(step);
            branch.add(new ArrowComponent(branchLoc.clone(), type));
        }
        return branch;
    }

    private static void setStandPose(ArmorStand stand, ComponentType type, float pitch) {
        boolean steep = pitch >= 22.5F || pitch <= -22.5F;
        boolean negative = pitch < 0.0F;
        float absPitch = Math.abs(pitch);

        switch (type) {
            case SHAFT -> stand.setHeadPose(new EulerAngle(
                    negative ? -Math.toRadians(absPitch) : Math.toRadians(absPitch),
                    0.0D,
                    0.0D
            ));
            case HEAD_RIGHT -> {
                double x = signedPitchRadians(absPitch * 0.9F, negative);
                if (steep) {
                    double steepAdjustment = Math.toRadians(absPitch - 22.5F);
                    x += negative ? steepAdjustment : -steepAdjustment;
                }
                double y = -Math.toRadians(absPitch);
                double z = negative ? -Math.toRadians(absPitch * 1.8F) : Math.toRadians(absPitch * 1.8F);
                stand.setHeadPose(new EulerAngle(x, y, z));
            }
            case HEAD_LEFT -> {
                double x = signedPitchRadians(absPitch * 0.9F, negative);
                if (steep) {
                    double steepAdjustment = Math.toRadians(absPitch - 22.5F);
                    x += negative ? steepAdjustment : -steepAdjustment;
                }
                double y = Math.toRadians(absPitch);
                double z = negative ? Math.toRadians(absPitch * 1.8F) : -Math.toRadians(absPitch * 1.8F);
                stand.setHeadPose(new EulerAngle(x, y, z));
            }
        }
    }

    private static double signedPitchRadians(float pitch, boolean negative) {
        double radians = Math.toRadians(pitch);
        return negative ? -radians : radians;
    }

    private enum ComponentType {
        SHAFT,
        HEAD_LEFT,
        HEAD_RIGHT
    }

    private record ArrowComponent(Location location, ComponentType type) {
    }
}
