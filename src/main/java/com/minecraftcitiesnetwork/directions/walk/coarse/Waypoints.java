package com.minecraftcitiesnetwork.directions.walk.coarse;

import com.minecraftcitiesnetwork.directions.walk.Terrain;
import com.minecraftcitiesnetwork.directions.walk.Window;

import java.util.List;

/** Chooses where along a coarse path the window search should head. */
public final class Waypoints {
    private Waypoints() {
    }

    /**
     * The last component of the path's leading run of components whose whole chunk lies inside the
     * window and whose representative column is captured; null when that run ends at the start.
     * The start itself always counts, even when its chunk straddles the window's edge. A path that
     * leaves the window and comes back in (to a bridge just outside it, say) ends the run where it
     * first leaves: a waypoint past that point would lie beyond the obstacle.
     */
    public static ComponentId pick(List<ComponentId> path, Window window, Terrain terrain) {
        int last = 0;
        for (int i = 1; i < path.size(); i++) {
            ComponentId id = path.get(i);
            int minX = id.chunkX() * 16;
            int minZ = id.chunkZ() * 16;
            boolean inside = window.contains(minX, minZ) && window.contains(minX + 15, minZ + 15);
            if (!inside || terrain.surfaces(id.x(), id.z()) == null) {
                break;
            }
            last = i;
        }
        return last == 0 ? null : path.get(last);
    }
}
