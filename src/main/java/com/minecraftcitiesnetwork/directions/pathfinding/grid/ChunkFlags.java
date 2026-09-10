package com.minecraftcitiesnetwork.directions.pathfinding.grid;

import com.minecraftcitiesnetwork.directions.pathfinding.BlockFlags;

/**
 * Decoded flags for one 16x16 chunk over a band of Y levels around the surface. Below the band
 * everything is solid; above it everything is air. About 16 KB at the default band height, versus
 * 100 to 200 KB for a raw chunk snapshot.
 */
public final class ChunkFlags {
    /** Marker for a chunk that is neither loaded nor on disk. */
    public static final ChunkFlags UNLOADED = new ChunkFlags(0, 0, new byte[0]);

    private final int bandMinY;
    private final int bandHeight;
    private final byte[] data;

    public ChunkFlags(int bandMinY, int bandHeight, byte[] data) {
        if (bandHeight < 0 || data.length != 256 * bandHeight) {
            throw new IllegalArgumentException("data length " + data.length + " does not match band height " + bandHeight);
        }
        this.bandMinY = bandMinY;
        this.bandHeight = bandHeight;
        this.data = data;
    }

    public boolean isUnloaded() {
        return this == UNLOADED;
    }

    public int bandMinY() {
        return bandMinY;
    }

    public int bandMaxY() {
        return bandMinY + bandHeight - 1;
    }

    /** Flags at in-chunk {@code lx, lz} (0..15) and world {@code y}. */
    public byte flags(int lx, int y, int lz) {
        if (this == UNLOADED) {
            return BlockFlags.UNLOADED;
        }
        if (y < bandMinY) {
            return BlockFlags.SOLID;
        }
        if (y >= bandMinY + bandHeight) {
            return BlockFlags.PASSABLE;
        }
        return data[index(lx, y, lz)];
    }

    /** Whether a world Y lies inside the stored band. Changes outside it cannot be patched in place. */
    public boolean covers(int y) {
        return this != UNLOADED && y >= bandMinY && y < bandMinY + bandHeight;
    }

    /** Patches one block. Benign data race by design: a torn read sees either the old or new flag. */
    public void set(int lx, int y, int lz, byte flag) {
        if (!covers(y)) {
            throw new IllegalArgumentException("y=" + y + " outside band " + bandMinY + ".." + bandMaxY());
        }
        data[index(lx, y, lz)] = flag;
    }

    private int index(int lx, int y, int lz) {
        return ((y - bandMinY) << 8) | (lz << 4) | lx;
    }

    public int byteSize() {
        return data.length;
    }
}
