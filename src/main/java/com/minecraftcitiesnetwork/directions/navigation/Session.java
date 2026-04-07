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
    private final List<String> waypoints;
    private int index;
    private final List<ArmorStand> arrowStands;
    private final BossBar bossBar;
    private double legStartDistance;
    private int upcomingHintSentForIndex;

    Session(DirectionsPlugin plugin, UUID playerId, List<String> waypoints, int index, List<ArmorStand> arrowStands, BossBar bossBar) {
        this.plugin = plugin;
        this.playerId = playerId;
        this.waypoints = waypoints;
        this.index = index;
        this.arrowStands = arrowStands;
        this.bossBar = bossBar;
        this.legStartDistance = -1.0;
        this.upcomingHintSentForIndex = -1;
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

    @Nullable String currentWaypoint() {
        if (index < 0 || index >= waypoints.size()) {
            return null;
        }
        return waypoints.get(index);
    }

    @Nullable String nextWaypoint() {
        int nextIdx = index + 1;
        if (nextIdx < 0 || nextIdx >= waypoints.size()) {
            return null;
        }
        return waypoints.get(nextIdx);
    }

    @Nullable String nextWaypointAfterNext() {
        int idx = index + 2;
        if (idx < 0 || idx >= waypoints.size()) {
            return null;
        }
        return waypoints.get(idx);
    }

    @Override
    public @Nullable String previousWaypoint() {
        int prevIdx = index - 1;
        if (prevIdx < 0 || prevIdx >= waypoints.size()) {
            return null;
        }
        return waypoints.get(prevIdx);
    }

    void advance() {
        index++;
    }
}
