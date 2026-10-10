package com.minecraftcitiesnetwork.directions.walk.coarse;

import com.minecraftcitiesnetwork.directions.walk.Cell;

/** A component of a chunk, named by the chunk and the component's representative cell. */
public record ComponentId(int chunkX, int chunkZ, int x, int y, int z) {
    public Cell cell() {
        return new Cell(x, y, z);
    }
}
