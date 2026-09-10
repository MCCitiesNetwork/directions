package com.minecraftcitiesnetwork.directions.pathfinding.grid;

import com.minecraftcitiesnetwork.directions.pathfinding.BlockFlags;

/**
 * Turns a full-height column-major flags source into a {@link ChunkFlags} band around the surface.
 * Shared by the snapshot decoder and the region-file reader so both produce identical bands.
 */
public final class BandDecoder {
    /** Margin kept below the lowest and above the highest surface in the chunk. */
    public static final int SURFACE_MARGIN = 16;

    private BandDecoder() {
    }

    /** A source of flags for one chunk at any world Y. */
    @FunctionalInterface
    public interface ColumnSource {
        byte flags(int lx, int y, int lz);
    }

    /**
     * @param highestSolid per-column highest non-air Y, 256 entries indexed {@code lz * 16 + lx}
     */
    public static ChunkFlags decode(ColumnSource source, int[] highestSolid, int worldMinY, int worldMaxY, int bandHeight) {
        int minH = Integer.MAX_VALUE;
        int maxH = Integer.MIN_VALUE;
        for (int h : highestSolid) {
            minH = Math.min(minH, h);
            maxH = Math.max(maxH, h);
        }
        if (minH == Integer.MAX_VALUE) {
            minH = worldMinY;
            maxH = worldMinY;
        }
        int top = Math.min(worldMaxY, maxH + SURFACE_MARGIN);
        int bottom = Math.max(worldMinY, Math.max(minH - SURFACE_MARGIN, top - bandHeight + 1));
        int height = Math.max(1, top - bottom + 1);
        byte[] data = new byte[256 * height];
        for (int y = bottom; y <= top; y++) {
            int rowBase = (y - bottom) << 8;
            for (int lz = 0; lz < 16; lz++) {
                for (int lx = 0; lx < 16; lx++) {
                    data[rowBase | (lz << 4) | lx] = source.flags(lx, y, lz);
                }
            }
        }
        return new ChunkFlags(bottom, height, data);
    }

    /** Highest Y whose flags are not air-like, per column, or {@code worldMinY} for empty columns. */
    public static int[] highestNonAir(ColumnSource source, int worldMinY, int worldMaxY) {
        int[] out = new int[256];
        for (int lz = 0; lz < 16; lz++) {
            for (int lx = 0; lx < 16; lx++) {
                int h = worldMinY;
                for (int y = worldMaxY; y >= worldMinY; y--) {
                    byte f = source.flags(lx, y, lz);
                    if (f != BlockFlags.PASSABLE) {
                        h = y;
                        break;
                    }
                }
                out[lz * 16 + lx] = h;
            }
        }
        return out;
    }
}
