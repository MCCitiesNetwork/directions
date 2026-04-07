package com.minecraftcitiesnetwork.directions.resolver;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldguard.protection.ApplicableRegionSet;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.minecraftcitiesnetwork.directions.graph.TransitGraph;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class PlayerPositionResolver {
    public @NotNull StartResolution resolve(
            @NotNull Player player,
            @NotNull RegionManager regionManager,
            @NotNull TransitGraph graph
    ) {
        Location loc = player.getLocation();
        String worldName = player.getWorld().getName();

        BlockVector3 at = BukkitAdapter.asBlockVector(loc);
        ApplicableRegionSet set = regionManager.getApplicableRegions(at);
        for (var region : set) {
            if (graph.getStopsById().containsKey(region.getId())) {
                return new StartResolution(Map.of(region.getId(), 0.0), false, region.getId());
            }
        }

        List<TransitGraph.StopDistance> nearby = graph.stopsWithinDistance(loc.getX(), loc.getZ(), worldName);
        if (!nearby.isEmpty()) {
            Map<String, Double> edges = new HashMap<>();
            for (TransitGraph.StopDistance sd : nearby) {
                edges.put(sd.stopId(), sd.distance());
            }
            return new StartResolution(edges, false, null);
        }

        List<TransitGraph.StopDistance> nearest = graph.nearestStops(loc.getX(), loc.getZ(), worldName, 1);
        if (nearest.isEmpty()) {
            return new StartResolution(Map.of(), true, null);
        }
        TransitGraph.StopDistance single = nearest.getFirst();
        return new StartResolution(Map.of(single.stopId(), single.distance()), true, null);
    }

    public record StartResolution(
            @NotNull Map<String, Double> startEdges,
            boolean fallbackUsed,
            @Nullable String currentStopId
    ) {
    }
}
