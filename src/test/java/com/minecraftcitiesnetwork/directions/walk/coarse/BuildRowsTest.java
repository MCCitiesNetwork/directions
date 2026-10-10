package com.minecraftcitiesnetwork.directions.walk.coarse;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class BuildRowsTest {
    @Test
    void rowsAdvanceOnlyInOrder() {
        BuildRows rows = new BuildRows();
        rows.open(0, 0, List.of(new ChunkPos(0, 0), new ChunkPos(1, 0), new ChunkPos(0, 1), new ChunkPos(0, 2)));
        assertNull(rows.finish(new ChunkPos(0, 1)), "row 1 is done but row 0 is not");
        assertNull(rows.finish(new ChunkPos(0, 0)), "row 0 still has a chunk outstanding");
        assertEquals(new BuildProgress(0, 0, 1, false), rows.finish(new ChunkPos(1, 0)));
    }

    @Test
    void aSkippedChunkCountsAsFinished() {
        // The builder reports chunks that failed to load exactly like built ones; the row still completes.
        BuildRows rows = new BuildRows();
        rows.open(0, 0, List.of(new ChunkPos(0, 0), new ChunkPos(5, 0), new ChunkPos(0, 1)));
        assertNull(rows.finish(new ChunkPos(0, 0)));
        assertEquals(new BuildProgress(0, 0, 0, false), rows.finish(new ChunkPos(5, 0)));
    }

    @Test
    void aRegionsLastRowCompletingYieldsRow31() {
        BuildRows rows = new BuildRows();
        rows.open(-1, 2, List.of(new ChunkPos(-32, 64), new ChunkPos(-32, 74)));
        assertEquals(new BuildProgress(-1, 2, 0, false), rows.finish(new ChunkPos(-32, 64)));
        assertEquals(new BuildProgress(-1, 2, 31, false), rows.finish(new ChunkPos(-32, 74)));
    }

    @Test
    void aLaterRegionWaitsForTheEarlierOne() {
        BuildRows rows = new BuildRows();
        rows.open(0, 0, List.of(new ChunkPos(0, 0)));
        rows.open(0, 1, List.of(new ChunkPos(0, 32)));
        assertNull(rows.finish(new ChunkPos(0, 32)));
        assertEquals(new BuildProgress(0, 1, 31, false), rows.finish(new ChunkPos(0, 0)));
    }
}
