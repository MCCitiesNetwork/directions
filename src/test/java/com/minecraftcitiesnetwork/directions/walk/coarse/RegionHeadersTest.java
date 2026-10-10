package com.minecraftcitiesnetwork.directions.walk.coarse;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RegionHeadersTest {
    @TempDir
    Path dir;

    @Test
    void parsesRegionCoordinatesFromTheFileName() {
        assertEquals(new ChunkPos(-3, 7), RegionHeaders.regionOf("r.-3.7.mca"));
    }

    @Test
    void unrelatedFileNamesAreIgnored() {
        assertNull(RegionHeaders.regionOf("r.1.mca"));
        assertNull(RegionHeaders.regionOf("level.dat"));
        assertNull(RegionHeaders.regionOf("r.a.b.mca"));
    }

    @Test
    void listsExactlyTheChunksWithOffsets() throws IOException {
        ByteBuffer data = ByteBuffer.allocate(8192);
        data.putInt(4 * (5 + 32 * 0), 2 << 8 | 1);
        data.putInt(4 * (31 + 32 * 31), 2 << 8 | 1);
        Path file = dir.resolve("r.-1.2.mca");
        Files.write(file, data.array());

        assertEquals(List.of(new ChunkPos(5 - 32, 64), new ChunkPos(31 - 32, 31 + 64)), RegionHeaders.chunksIn(file));
    }

    @Test
    void shortFileIsAnError() throws IOException {
        Path file = dir.resolve("r.0.0.mca");
        Files.write(file, new byte[100]);

        IOException error = assertThrows(IOException.class, () -> RegionHeaders.chunksIn(file));
        assertTrue(error.getMessage().contains("r.0.0.mca"));
    }

    @Test
    void unrelatedFileNameIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> RegionHeaders.chunksIn(dir.resolve("level.dat")));
    }
}
