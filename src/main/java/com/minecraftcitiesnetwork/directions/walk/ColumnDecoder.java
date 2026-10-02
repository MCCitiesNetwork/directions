package com.minecraftcitiesnetwork.directions.walk;

import java.util.Arrays;

/** Turns a chunk's blocks into the walkable surfaces of each column. */
public final class ColumnDecoder {
    private static final int[] NONE = new int[0];

    private ColumnDecoder() {
    }

    public static ColumnChunk decode(int chunkX, int chunkZ, ChunkBlocks blocks) {
        int minY = blocks.minY();
        int height = blocks.maxY() - minY;
        BlockKind[] column = new BlockKind[height];
        int[] buffer = new int[Math.max(height, 1)];
        int[][] columns = new int[256][];
        for (int lz = 0; lz < 16; lz++) {
            for (int lx = 0; lx < 16; lx++) {
                for (int i = 0; i < height; i++) {
                    column[i] = blocks.kind(lx, minY + i, lz);
                }
                columns[lz * 16 + lx] = surfaces(column, minY, buffer);
            }
        }
        return new ColumnChunk(chunkX, chunkZ, columns);
    }

    /** Index {@code i} of {@code k} is height {@code minY + i}; above the top is open sky. */
    static int[] surfaces(BlockKind[] k, int minY, int[] buffer) {
        int n = 0;
        for (int i = 1; i < k.length; i++) {
            BlockKind below = k[i - 1];
            BlockKind feet = k[i];
            BlockKind head = at(k, i + 1);
            Footing footing;
            if (below.supports() && feet.passable() && head.passable()) {
                footing = Footing.GROUND;
            } else if (feet == BlockKind.WATER && head.passable()) {
                footing = Footing.WATER;
            } else if (feet == BlockKind.CLIMB && head.passable()) {
                footing = Footing.CLIMB;
            } else {
                continue;
            }
            int headroom = 0;
            for (int j = i; headroom < Surfaces.MAX_HEADROOM; j++) {
                BlockKind kj = at(k, j);
                if (kj.passable() || (j == i && kj == BlockKind.WATER)) {
                    headroom++;
                } else {
                    break;
                }
            }
            boolean door = feet == BlockKind.DOOR || head == BlockKind.DOOR;
            boolean half = footing == Footing.GROUND && below == BlockKind.HALF;
            buffer[n++] = Surfaces.pack(minY + i, footing, headroom, door, half);
        }
        return n == 0 ? NONE : Arrays.copyOf(buffer, n);
    }

    private static BlockKind at(BlockKind[] k, int i) {
        return i < k.length ? k[i] : BlockKind.OPEN;
    }
}
