package com.minecraftcitiesnetwork.directions.pathfinding.grid;

import com.minecraftcitiesnetwork.directions.pathfinding.BlockFlags;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GridCodecTest {

    @Test
    void roundTripPreservesEveryCell(@TempDir Path dir) throws IOException {
        ArrayBlockGrid grid = new ArrayBlockGrid(-20, 50, 300, 33, 40, 17);
        Random random = new Random(1);
        byte[] all = {BlockFlags.SOLID, BlockFlags.PASSABLE, BlockFlags.LIQUID, BlockFlags.OPENABLE,
                BlockFlags.CLIMBABLE, BlockFlags.HAZARD, BlockFlags.UNLOADED};
        for (int x = -20; x < 13; x++) {
            for (int y = 50; y < 90; y++) {
                for (int z = 300; z < 317; z++) {
                    grid.set(x, y, z, all[random.nextInt(all.length)]);
                }
            }
        }
        Path file = dir.resolve("nested/sample.grid.gz");
        GridCodec.write(grid, file);
        ArrayBlockGrid back = GridCodec.read(file);

        assertEquals(grid.minX(), back.minX());
        assertEquals(grid.minY(), back.minY());
        assertEquals(grid.minZ(), back.minZ());
        for (int x = -20; x < 13; x++) {
            for (int y = 50; y < 90; y++) {
                for (int z = 300; z < 317; z++) {
                    assertEquals(grid.flags(x, y, z), back.flags(x, y, z));
                }
            }
        }
        assertTrue(file.toFile().length() < 33 * 40 * 17, "gzip should compress the fixture");
    }

    @Test
    void translatedCopyKeepsShapeAndMovesOrigin() {
        ArrayBlockGrid grid = new ArrayBlockGrid(100, 60, -40, 4, 3, 5);
        grid.set(102, 61, -38, BlockFlags.LIQUID);
        ArrayBlockGrid local = grid.translatedToLocalOrigin();
        assertEquals(0, local.minX());
        assertEquals(0, local.minZ());
        assertEquals(60, local.minY());
        assertEquals(BlockFlags.LIQUID, local.flags(2, 61, 2));
    }

    @Test
    void rejectsForeignFiles() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (var gz = new java.util.zip.GZIPOutputStream(out)) {
            gz.write(new byte[]{1, 2, 3, 4, 5, 6, 7, 8});
        }
        assertThrows(IOException.class, () -> GridCodec.read(new ByteArrayInputStream(out.toByteArray())));
    }

    @Test
    void outsideTheBoxIsSolidBelowAirAboveUnloadedBeside() {
        ArrayBlockGrid grid = new ArrayBlockGrid(0, 64, 0, 4, 2, 4);
        assertEquals(BlockFlags.SOLID, grid.flags(1, 63, 1));
        assertEquals(BlockFlags.PASSABLE, grid.flags(1, 66, 1));
        assertEquals(BlockFlags.UNLOADED, grid.flags(-1, 64, 1));
        assertEquals(BlockFlags.UNLOADED, grid.flags(1, 64, 4));
    }
}
