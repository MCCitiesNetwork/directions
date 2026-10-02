package com.minecraftcitiesnetwork.directions.navigation;

import com.minecraftcitiesnetwork.directions.DirectionsPlugin;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.ArmorStand;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.UUID;

final class Session implements SessionView {
    private final DirectionsPlugin plugin;
    private final UUID playerId;
    private final List<Leg> legs;
    private int index;
    private final List<ArmorStand> arrowStands;
    private final BossBar bossBar;
    private final boolean gps;
    private double legStartDistance;
    private int upcomingHintSentForIndex;

    Session(DirectionsPlugin plugin, UUID playerId, List<Leg> legs, int index, List<ArmorStand> arrowStands, BossBar bossBar, boolean gps) {
        this.plugin = plugin;
        this.playerId = playerId;
        this.legs = List.copyOf(legs);
        this.index = index;
        this.arrowStands = arrowStands;
        this.bossBar = bossBar;
        this.gps = gps;
        this.legStartDistance = -1.0;
        this.upcomingHintSentForIndex = -1;
    }

    boolean gps() {
        return gps;
    }

    UUID playerId() {
        return playerId;
    }

    DirectionsPlugin plugin() {
        return plugin;
    }

    List<ArmorStand> arrowStands() {
        return arrowStands;
    }

    BossBar bossBar() {
        return bossBar;
    }

    double legStartDistance() {
        return legStartDistance;
    }

    void setLegStartDistance(double legStartDistance) {
        this.legStartDistance = legStartDistance;
    }

    void resetLegProgress() {
        this.legStartDistance = -1.0;
    }

    int index() {
        return index;
    }

    int upcomingHintSentForIndex() {
        return upcomingHintSentForIndex;
    }

    void setUpcomingHintSentForIndex(int idx) {
        this.upcomingHintSentForIndex = idx;
    }

    @Nullable Leg currentLeg() {
        return legAt(index);
    }

    @Nullable Waypoint currentWaypoint() {
        return targetAt(index);
    }

    @Nullable Waypoint nextWaypoint() {
        return targetAt(index + 1);
    }

    @Nullable Waypoint nextWaypointAfterNext() {
        return targetAt(index + 2);
    }

    @Override
    public @Nullable Waypoint previousWaypoint() {
        return targetAt(index - 1);
    }

    void advance() {
        index++;
    }

    private @Nullable Leg legAt(int i) {
        if (i < 0 || i >= legs.size()) {
            return null;
        }
        return legs.get(i);
    }

    private @Nullable Waypoint targetAt(int i) {
        Leg leg = legAt(i);
        return leg == null ? null : leg.target();
    }
}
