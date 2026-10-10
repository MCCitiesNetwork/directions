package com.minecraftcitiesnetwork.directions.walk;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** A sparse block world for tests: set block kinds, then decode it into a {@link Terrain}. */
final class BlockWorld {
    private final int minY;
    private final Map<Long, BlockKind> blocks = new HashMap<>();
    private final Set<Long> chunks = new HashSet<>();
    private int topY;

    BlockWorld(int minY) {
        this.minY = minY;
        this.topY = minY;
    }

    /**
     * Layers bottom-up, the first at {@code minY}. In each layer, rows are z (from 0) and characters
     * are x (from 0), using the codes in {@link Kinds}.
     */
    static BlockWorld layers(int minY, String... layers) {
        BlockWorld world = new BlockWorld(minY);
        for (int i = 0; i < layers.length; i++) {
            String[] rows = layers[i].strip().split("\n");
            for (int z = 0; z < rows.length; z++) {
                String row = rows[z].strip();
                for (int x = 0; x < row.length(); x++) {
                    world.set(x, minY + i, z, Kinds.of(row.charAt(x)));
                }
            }
        }
        return world;
    }

    /** A single column at x = 0, z = 0, written bottom-up from {@code minY}. */
    static BlockWorld column(int minY, String bottomUp) {
        BlockWorld world = new BlockWorld(minY);
        for (int i = 0; i < bottomUp.length(); i++) {
            world.set(0, minY + i, 0, Kinds.of(bottomUp.charAt(i)));
        }
        return world;
    }

    BlockWorld set(int x, int y, int z, BlockKind kind) {
        if (y < minY) {
            throw new IllegalArgumentException("y " + y + " is below minY " + minY);
        }
        blocks.put(key(x, y, z), kind);
        chunks.add(chunkKey(x >> 4, z >> 4));
        topY = Math.max(topY, y);
        return this;
    }

    BlockWorld fill(int x0, int y0, int z0, int x1, int y1, int z1, BlockKind kind) {
        for (int x = Math.min(x0, x1); x <= Math.max(x0, x1); x++) {
            for (int y = Math.min(y0, y1); y <= Math.max(y0, y1); y++) {
                for (int z = Math.min(z0, z1); z <= Math.max(z0, z1); z++) {
                    set(x, y, z, kind);
                }
            }
        }
        return this;
    }

    /** Decodes every chunk that has at least one block set; columns of other chunks are unknown. */
    Terrain terrain() {
        Map<Long, ColumnChunk> decoded = new HashMap<>();
        for (long ck : chunks) {
            int cx = (int) (ck >> 32);
            int cz = (int) ck;
            decoded.put(ck, ColumnDecoder.decode(cx, cz, blocksOf(cx, cz)));
        }
        return (x, z) -> {
            ColumnChunk chunk = decoded.get(chunkKey(x >> 4, z >> 4));
            return chunk == null ? null : chunk.column(x & 15, z & 15);
        };
    }

    private ChunkBlocks blocksOf(int cx, int cz) {
        int maxY = topY + 4;
        return new ChunkBlocks() {
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
                return blocks.getOrDefault(key(cx * 16 + localX, y, cz * 16 + localZ), BlockKind.OPEN);
            }
        };
    }

    private static long key(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }

    private static long chunkKey(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
    }
}
