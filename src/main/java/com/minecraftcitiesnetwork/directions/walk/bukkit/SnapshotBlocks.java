package com.minecraftcitiesnetwork.directions.walk.bukkit;

import com.minecraftcitiesnetwork.directions.walk.BlockKind;
import com.minecraftcitiesnetwork.directions.walk.ChunkBlocks;
import org.bukkit.ChunkSnapshot;

/** A chunk snapshot seen as block kinds. Safe to read off the main thread. */
final class SnapshotBlocks implements ChunkBlocks {
    private final ChunkSnapshot snapshot;
    private final MaterialKinds kinds;
    private final int minY;
    private final int maxY;

    SnapshotBlocks(ChunkSnapshot snapshot, MaterialKinds kinds, int minY, int maxY) {
        this.snapshot = snapshot;
        this.kinds = kinds;
        this.minY = minY;
        this.maxY = maxY;
    }

    @Override
    public int minY() {
        return minY;
    }

    @Override
    public int maxY() {
        return maxY;
    }

    @Override
    public BlockKind kind(int localX, int y, int localZ) {
        return kinds.of(snapshot.getBlockType(localX, y, localZ));
    }
}
