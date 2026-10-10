package com.minecraftcitiesnetwork.directions.walk.coarse;

/** Packs a chunk coordinate pair into one long map key. */
public final class ChunkKeys {
    private ChunkKeys() {
    }

    public static long key(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

    public static int x(long key) {
        return (int) (key >> 32);
    }

    public static int z(long key) {
        return (int) key;
    }
}
