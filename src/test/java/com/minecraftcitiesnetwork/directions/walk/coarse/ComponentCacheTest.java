package com.minecraftcitiesnetwork.directions.walk.coarse;

import com.minecraftcitiesnetwork.directions.walk.BlockKind;
import com.minecraftcitiesnetwork.directions.walk.BlockWorld;
import com.minecraftcitiesnetwork.directions.walk.Terrain;
import com.minecraftcitiesnetwork.directions.walk.WalkRules;
import com.minecraftcitiesnetwork.directions.walk.WalkerProfile;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

class ComponentCacheTest {
    private static final WalkRules RULES = new WalkRules(WalkerProfile.DEFAULT);
    private static final Terrain FLAT = new BlockWorld(0).fill(0, 64, 0, 16 * 8 - 1, 64, 15, BlockKind.SOLID).terrain();

    @Test
    void aSecondLookupIsAHit() {
        ComponentCache cache = new ComponentCache(4);
        List<Component> first = cache.of(FLAT, 1, 0, RULES);
        assertEquals(ChunkComponents.of(FLAT, 1, 0, RULES).stream().map(Component::id).toList(),
                first.stream().map(Component::id).toList());
        assertSame(first, cache.of(FLAT, 1, 0, RULES));
    }

    @Test
    void invalidateForgetsOnlyThatChunk() {
        ComponentCache cache = new ComponentCache(4);
        List<Component> one = cache.of(FLAT, 1, 0, RULES);
        List<Component> two = cache.of(FLAT, 2, 0, RULES);
        cache.invalidate(1, 0);
        assertNotSame(one, cache.of(FLAT, 1, 0, RULES));
        assertSame(two, cache.of(FLAT, 2, 0, RULES));
    }

    @Test
    void theLeastRecentlyUsedChunkIsEvicted() {
        ComponentCache cache = new ComponentCache(2);
        List<Component> zero = cache.of(FLAT, 0, 0, RULES);
        List<Component> one = cache.of(FLAT, 1, 0, RULES);
        assertSame(zero, cache.of(FLAT, 0, 0, RULES)); // chunk 1 is now the least recently used
        cache.of(FLAT, 2, 0, RULES);
        assertEquals(2, cache.size());
        assertSame(zero, cache.of(FLAT, 0, 0, RULES));
        assertNotSame(one, cache.of(FLAT, 1, 0, RULES));
    }

    @Test
    void theDefaultHolds512Chunks() {
        ComponentCache cache = new ComponentCache();
        for (int cx = 0; cx < 600; cx++) {
            cache.of((x, z) -> new int[0], cx, 0, RULES);
        }
        assertEquals(512, cache.size());
    }

    @Test
    void edgesFromTheCacheMatchEdgesComputedDirectly() {
        ComponentCache cache = new ComponentCache();
        List<Component> components = ChunkComponents.of(FLAT, 3, 0, RULES);
        assertEquals(ChunkEdges.compute(FLAT, RULES, 3, 0, components),
                ChunkEdges.compute(FLAT, RULES, 3, 0, components, (x, z) -> cache.of(FLAT, x, z, RULES)));
    }
}
