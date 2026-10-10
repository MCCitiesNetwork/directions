package com.minecraftcitiesnetwork.directions.walk.coarse;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BuildOrderTest {
    /** Every chunk of the region, in row order (z, then x). */
    private static List<ChunkPos> wholeRegion(int regionX, int regionZ) {
        List<ChunkPos> chunks = new ArrayList<>();
        for (int row = 0; row < 32; row++) {
            for (int column = 0; column < 32; column++) {
                chunks.add(new ChunkPos(regionX * 32 + column, regionZ * 32 + row));
            }
        }
        return chunks;
    }

    @Test
    void everythingBeforeAnyProgress() {
        List<ChunkPos> region = wholeRegion(0, 0);
        assertEquals(region, BuildOrder.after(region, BuildProgress.NONE, 0, 0));
    }

    @Test
    void resumesAfterTheSavedRow() {
        List<ChunkPos> region = wholeRegion(0, 0);
        List<ChunkPos> left = BuildOrder.after(region, new BuildProgress(0, 0, 5, false), 0, 0);
        assertEquals(26 * 32, left.size());
        assertTrue(left.stream().allMatch(c -> c.z() > 5));
        assertEquals(new ChunkPos(0, 6), left.get(0));
    }

    @Test
    void resumesAfterTheSavedRowOfANegativeRegion() {
        List<ChunkPos> region = wholeRegion(-1, -1);
        List<ChunkPos> left = BuildOrder.after(region, new BuildProgress(-1, -1, 5, false), -1, -1);
        assertEquals(26 * 32, left.size());
        assertEquals(new ChunkPos(-32, -26), left.get(0));
    }

    @Test
    void laterRegionsAreUntouched() {
        List<ChunkPos> region = wholeRegion(1, 0);
        assertEquals(region, BuildOrder.after(region, new BuildProgress(0, 0, 5, false), 1, 0));
    }

    @Test
    void laterRegionInTheSameColumnIsUntouched() {
        List<ChunkPos> region = wholeRegion(0, 1);
        assertEquals(region, BuildOrder.after(region, new BuildProgress(0, 0, 5, false), 0, 1));
    }

    @Test
    void earlierRegionsAreDone() {
        List<ChunkPos> region = wholeRegion(0, 0);
        assertTrue(BuildOrder.after(region, new BuildProgress(1, 0, 5, false), 0, 0).isEmpty());
        assertTrue(BuildOrder.after(wholeRegion(1, -1), new BuildProgress(1, 0, 5, false), 1, -1).isEmpty());
    }

    @Test
    void aFinishedBuildLeavesNothing() {
        List<ChunkPos> region = wholeRegion(4, 4);
        assertTrue(BuildOrder.after(region, new BuildProgress(0, 0, 31, true), 4, 4).isEmpty());
    }

    @Test
    void warmsTheBuiltNeighboursWestAndNorthOfTheChunksToDo() {
        List<ChunkPos> todo = List.of(new ChunkPos(0, 6), new ChunkPos(1, 6), new ChunkPos(0, 7));
        java.util.Set<ChunkPos> builtBefore = java.util.Set.of(
                new ChunkPos(0, 5), new ChunkPos(1, 5), new ChunkPos(-1, 6), new ChunkPos(-1, 7), new ChunkPos(2, 5));
        assertEquals(List.of(new ChunkPos(-1, 6), new ChunkPos(0, 5), new ChunkPos(1, 5), new ChunkPos(-1, 7)),
                BuildOrder.builtNeighbours(todo, builtBefore::contains));
    }

    @Test
    void nothingToWarmOnAFreshBuild() {
        assertTrue(BuildOrder.builtNeighbours(wholeRegion(0, 0), c -> false).isEmpty());
    }
}
