package com.minecraftcitiesnetwork.directions.pathfinding.bukkit;

import com.minecraftcitiesnetwork.directions.model.Stop;
import com.minecraftcitiesnetwork.directions.pathfinding.BlockGrid;
import com.minecraftcitiesnetwork.directions.pathfinding.Pos;
import com.minecraftcitiesnetwork.directions.pathfinding.StandingPositions;
import com.minecraftcitiesnetwork.directions.pathfinding.WalkerProfile;

import java.util.Optional;

/** Turns a stop's 2D centre into a standing cell: near the region's own height, or on the surface. */
public final class StopLocator {
    private StopLocator() {
    }

    public static Optional<Pos> locate(BlockGrid grid, WalkerProfile profile, Stop stop) {
        int x = (int) Math.floor(stop.x());
        int z = (int) Math.floor(stop.z());
        if (stop.hasY()) {
            Optional<Pos> near = StandingPositions.snap(grid, profile, new Pos(x, (int) Math.floor(stop.y()), z));
            if (near.isPresent()) {
                return near;
            }
        }
        return StandingPositions.surface(grid, profile, x, z, grid.maxY() + 1);
    }
}
