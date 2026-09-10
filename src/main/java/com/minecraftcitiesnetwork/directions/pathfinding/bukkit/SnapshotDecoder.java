package com.minecraftcitiesnetwork.directions.pathfinding.bukkit;

import com.minecraftcitiesnetwork.directions.pathfinding.grid.BandDecoder;
import com.minecraftcitiesnetwork.directions.pathfinding.grid.ChunkFlags;
import org.bukkit.ChunkSnapshot;

/** Decodes a live {@link ChunkSnapshot} into {@link ChunkFlags}. Runs on a search thread. */
final class SnapshotDecoder {
    private final MaterialFlags materials;
    private final int worldMinY;
    private final int worldMaxY;
    private final int bandHeight;

    SnapshotDecoder(MaterialFlags materials, int worldMinY, int worldMaxY, int bandHeight) {
        this.materials = materials;
        this.worldMinY = worldMinY;
        this.worldMaxY = worldMaxY;
        this.bandHeight = bandHeight;
    }

    ChunkFlags decode(ChunkSnapshot snapshot) {
        int[] highest = new int[256];
        for (int lz = 0; lz < 16; lz++) {
            for (int lx = 0; lx < 16; lx++) {
                // getHighestBlockYAt returns the Y above the highest non-air block.
                highest[lz * 16 + lx] = Math.max(worldMinY, snapshot.getHighestBlockYAt(lx, lz) - 1);
            }
        }
        BandDecoder.ColumnSource source = (lx, y, lz) -> materials.flags(snapshot.getBlockType(lx, y, lz));
        return BandDecoder.decode(source, highest, worldMinY, worldMaxY, bandHeight);
    }
}
