package com.minecraftcitiesnetwork.directions.walk;

/** A feet position: the block a standing player's feet occupy. */
public record Cell(int x, int y, int z) {
    /** A unique key for maps: 26 bits each of x and z, 12 bits of y (−2048..2047). */
    public static long key(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }
}
