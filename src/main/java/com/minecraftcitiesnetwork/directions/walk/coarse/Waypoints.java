package com.minecraftcitiesnetwork.directions.walk.coarse;

import com.minecraftcitiesnetwork.directions.walk.Terrain;
import com.minecraftcitiesnetwork.directions.walk.Window;

import java.util.List;

/** Chooses where along a coarse path the window search should head. */
public final class Waypoints {
    private Waypoints() {
    }

    /**
     * The last component on the path whose whole chunk lies inside the window and whose representative
     * column is captured; null when that is the path's start or no component qualifies.
     */
    public static ComponentId pick(List<ComponentId> path, Window window, Terrain terrain) {
        for (int i = path.size() - 1; i >= 0; i--) {
            ComponentId id = path.get(i);
            int minX = id.chunkX() * 16;
            int minZ = id.chunkZ() * 16;
            boolean inside = window.contains(minX, minZ) && window.contains(minX + 15, minZ + 15);
            if (inside && terrain.surfaces(id.x(), id.z()) != null) {
                return i == 0 ? null : id;
            }
        }
        return null;
    }
}
