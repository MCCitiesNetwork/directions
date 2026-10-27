package com.minecraftcitiesnetwork.directions.navigation;

import com.minecraftcitiesnetwork.directions.DirectionsPlugin;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.UUID;

final class Session implements SessionView {
    private final DirectionsPlugin plugin;
    private final UUID playerId;
    private final List<Waypoint> waypoints;
    private int index;
    private final List<ItemDisplay> arrowBlocks;
    private final BossBar bossBar;
    private final boolean gps;
    private double legStartDistance;
    private int upcomingHintSentForIndex;
    private @Nullable Vector arrowPointing;

    Session(DirectionsPlugin plugin, UUID playerId, List<Waypoint> waypoints, int index, List<ItemDisplay> arrowBlocks, BossBar bossBar, boolean gps) {
        this.plugin = plugin;
        this.playerId = playerId;
        this.waypoints = waypoints;
        this.index = index;
        this.arrowBlocks = arrowBlocks;
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

    List<ItemDisplay> arrowBlocks() {
        return arrowBlocks;
    }

    /** Which way the arrow points now, or null before it is first drawn. */
    @Nullable Vector arrowPointing() {
        return arrowPointing;
    }

    void setArrowPointing(Vector arrowPointing) {
        this.arrowPointing = arrowPointing;
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

    @Nullable Waypoint currentWaypoint() {
        if (index < 0 || index >= waypoints.size()) {
            return null;
        }
        return waypoints.get(index);
    }

    @Nullable Waypoint nextWaypoint() {
        int nextIdx = index + 1;
        if (nextIdx < 0 || nextIdx >= waypoints.size()) {
            return null;
        }
        return waypoints.get(nextIdx);
    }

    @Nullable Waypoint nextWaypointAfterNext() {
        int idx = index + 2;
        if (idx < 0 || idx >= waypoints.size()) {
            return null;
        }
        return waypoints.get(idx);
    }

    @Override
    public @Nullable Waypoint previousWaypoint() {
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
