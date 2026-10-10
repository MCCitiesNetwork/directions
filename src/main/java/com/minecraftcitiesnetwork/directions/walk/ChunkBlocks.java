package com.minecraftcitiesnetwork.directions.walk;

/** Read-only block kinds of one chunk, for {@link ColumnDecoder}. */
public interface ChunkBlocks {
    /** Lowest block height, inclusive. */
    int minY();

    /** Highest block height, exclusive. */
    int maxY();

    BlockKind kind(int localX, int y, int localZ);
}
