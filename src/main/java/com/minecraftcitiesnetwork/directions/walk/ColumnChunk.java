package com.minecraftcitiesnetwork.directions.walk;

/** The walkable surfaces of every column in one chunk. Immutable once built. */
public final class ColumnChunk {
    private final int chunkX;
    private final int chunkZ;
    private final int[][] columns;

    /** @param columns 256 arrays indexed {@code localZ * 16 + localX}, each sorted by height */
    public ColumnChunk(int chunkX, int chunkZ, int[][] columns) {
        if (columns.length != 256) {
            throw new IllegalArgumentException("a chunk has 256 columns");
        }
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        this.columns = columns;
    }

    public int chunkX() {
        return chunkX;
    }

    public int chunkZ() {
        return chunkZ;
    }

    /** The column's packed surfaces. Callers must not modify the array. */
    public int[] column(int localX, int localZ) {
        return columns[localZ * 16 + localX];
    }
}
