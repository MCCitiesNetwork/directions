package com.minecraftcitiesnetwork.directions.walk;

import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ColumnStoreTest {
    private static final UUID WORLD = UUID.randomUUID();
    private static final UUID OTHER = UUID.randomUUID();

    private static ColumnChunk floorChunk(int cx, int cz) {
        return ColumnDecoder.decode(cx, cz, new ChunkBlocks() {
            @Override
            public int minY() {
                return 64;
            }

            @Override
            public int maxY() {
                return 70;
            }

            @Override
            public BlockKind kind(int localX, int y, int localZ) {
                return y == 64 ? BlockKind.SOLID : BlockKind.OPEN;
            }
        });
    }

    @Test
    void storedChunksAreVisibleThroughTerrain() {
        ColumnStore store = new ColumnStore(100, System::currentTimeMillis);
        store.put(WORLD, floorChunk(0, 0));
        assertTrue(store.contains(WORLD, 0, 0));
        assertEquals(65, Surfaces.y(store.terrain(WORLD).surfaces(3, 7)[0]));
        assertNull(store.terrain(WORLD).surfaces(16, 0));
        assertNull(store.terrain(OTHER).surfaces(3, 7));
    }

    @Test
    void negativeCoordinatesMapToTheRightChunk() {
        ColumnStore store = new ColumnStore(100, System::currentTimeMillis);
        store.put(WORLD, floorChunk(-1, -1));
        assertEquals(1, store.terrain(WORLD).surfaces(-1, -16).length);
        assertNull(store.terrain(WORLD).surfaces(-17, -1));
        assertNull(store.terrain(WORLD).surfaces(0, -1));
    }

    @Test
    void removeAndClear() {
        ColumnStore store = new ColumnStore(100, System::currentTimeMillis);
        store.put(WORLD, floorChunk(0, 0));
        store.put(WORLD, floorChunk(1, 0));
        store.remove(WORLD, 0, 0);
        assertFalse(store.contains(WORLD, 0, 0));
        assertEquals(1, store.size());
        store.clear();
        assertEquals(0, store.size());
    }

    @Test
    void trimsLeastRecentlyUsedBeyondTheLimit() {
        AtomicLong clock = new AtomicLong();
        ColumnStore store = new ColumnStore(10, clock::get);
        for (int i = 0; i < 10; i++) {
            clock.set(i);
            store.put(WORLD, floorChunk(i, 0));
        }
        clock.set(100);
        store.terrain(WORLD).surfaces(0, 0);
        clock.set(101);
        store.put(WORLD, floorChunk(10, 0));
        assertEquals(9, store.size());
        assertTrue(store.contains(WORLD, 0, 0), "recently read chunk must survive");
        assertTrue(store.contains(WORLD, 10, 0), "newest chunk must survive");
        assertFalse(store.contains(WORLD, 1, 0), "least recently used chunk must go");
    }
}
