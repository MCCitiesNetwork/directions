package com.minecraftcitiesnetwork.directions.pathfinding.grid;

import com.minecraftcitiesnetwork.directions.pathfinding.BlockClassifier;
import com.minecraftcitiesnetwork.directions.pathfinding.BlockFlags;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.Deflater;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Writes a small region file with a hand-built chunk in the modern section format and reads it back.
 * No region file is ever committed: the fixture is generated here.
 */
class RegionFileReaderTest {
    private static final int WORLD_MIN_Y = -64;
    private static final int WORLD_MAX_Y = 319;

    @Test
    void decodesPaletteAndPackedIndices(@TempDir Path dir) throws IOException {
        // Section Y=4 covers world Y 64..79. Floor of stone at local y=0 (world 64); a door at (3,1,3);
        // water at (5,1,5); a fence at (7,1,7); air elsewhere.
        String[] palette = {"minecraft:air", "minecraft:stone", "minecraft:oak_door", "minecraft:water", "minecraft:oak_fence"};
        int[] blocks = new int[4096];
        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                blocks[index(lx, 0, lz)] = 1;
            }
        }
        blocks[index(3, 1, 3)] = 2;
        blocks[index(5, 1, 5)] = 3;
        blocks[index(7, 1, 7)] = 4;
        byte[] chunk = chunkNbt(List.of(new SectionSpec(4, palette, blocks)), "minecraft:full");
        writeRegion(dir.resolve("r.0.0.mca"), 3, 5, chunk, 2);

        RegionFileReader reader = new RegionFileReader(dir, BlockClassifier.offline(), WORLD_MIN_Y, WORLD_MAX_Y, 64);
        ChunkFlags flags = reader.read(3, 5);
        assertNotNull(flags);
        assertEquals(BlockFlags.SOLID, flags.flags(0, 64, 0));
        assertEquals(BlockFlags.PASSABLE, flags.flags(0, 65, 0));
        assertEquals(BlockFlags.OPENABLE, flags.flags(3, 65, 3));
        assertEquals(BlockFlags.LIQUID, flags.flags(5, 65, 5));
        assertEquals(BlockFlags.TALL, flags.flags(7, 65, 7));
        // Below the band is solid, above is air.
        assertEquals(BlockFlags.SOLID, flags.flags(0, WORLD_MIN_Y, 0));
        assertEquals(BlockFlags.PASSABLE, flags.flags(0, 200, 0));
    }

    @Test
    void uniformSectionNeedsNoData(@TempDir Path dir) throws IOException {
        byte[] chunk = chunkNbt(List.of(new SectionSpec(4, new String[]{"minecraft:stone"}, null)), "minecraft:full");
        writeRegion(dir.resolve("r.0.0.mca"), 0, 0, chunk, 2);
        ChunkFlags flags = new RegionFileReader(dir, BlockClassifier.offline(), WORLD_MIN_Y, WORLD_MAX_Y, 64).read(0, 0);
        assertNotNull(flags);
        assertEquals(BlockFlags.SOLID, flags.flags(8, 70, 8));
        assertEquals(BlockFlags.PASSABLE, flags.flags(8, 80, 8));
    }

    @Test
    void missingChunkAndMissingFileAreNull(@TempDir Path dir) throws IOException {
        byte[] chunk = chunkNbt(List.of(new SectionSpec(4, new String[]{"minecraft:stone"}, null)), "minecraft:full");
        writeRegion(dir.resolve("r.0.0.mca"), 0, 0, chunk, 2);
        RegionFileReader reader = new RegionFileReader(dir, BlockClassifier.offline(), WORLD_MIN_Y, WORLD_MAX_Y, 64);
        assertNull(reader.read(1, 1), "chunk not present in the region");
        assertNull(reader.read(40, 40), "region file does not exist");
    }

    @Test
    void partiallyGeneratedChunkIsRejected(@TempDir Path dir) throws IOException {
        byte[] chunk = chunkNbt(List.of(new SectionSpec(4, new String[]{"minecraft:stone"}, null)), "minecraft:features");
        writeRegion(dir.resolve("r.0.0.mca"), 0, 0, chunk, 2);
        assertNull(new RegionFileReader(dir, BlockClassifier.offline(), WORLD_MIN_Y, WORLD_MAX_Y, 64).read(0, 0));
    }

    @Test
    void truncatedFileReadsAsUnavailable(@TempDir Path dir) throws IOException {
        byte[] chunk = chunkNbt(List.of(new SectionSpec(4, new String[]{"minecraft:stone"}, null)), "minecraft:full");
        Path file = dir.resolve("r.0.0.mca");
        writeRegion(file, 0, 0, chunk, 2);
        byte[] all = Files.readAllBytes(file);
        Files.write(file, java.util.Arrays.copyOf(all, 8192 + 40)); // header intact, payload cut mid-way
        assertNull(new RegionFileReader(dir, BlockClassifier.offline(), WORLD_MIN_Y, WORLD_MAX_Y, 64).read(0, 0));
    }

    @Test
    void unsupportedCompressionIsSkipped(@TempDir Path dir) throws IOException {
        byte[] chunk = chunkNbt(List.of(new SectionSpec(4, new String[]{"minecraft:stone"}, null)), "minecraft:full");
        writeRegion(dir.resolve("r.0.0.mca"), 0, 0, chunk, 4); // 4 = LZ4, not supported
        assertNull(new RegionFileReader(dir, BlockClassifier.offline(), WORLD_MIN_Y, WORLD_MAX_Y, 64).read(0, 0));
    }

    @Test
    void bitsPerIndexIsRecoveredFromArrayLength() {
        assertEquals(4, RegionFileReader.bitsPerIndex(256, 5));
        assertEquals(5, RegionFileReader.bitsPerIndex(342, 20));
        assertEquals(6, RegionFileReader.bitsPerIndex(410, 40));
    }

    // ---- fixture generation -----------------------------------------------------------------

    private record SectionSpec(int y, String[] palette, int[] blocks) {
    }

    private static int index(int lx, int ly, int lz) {
        return (ly << 8) | (lz << 4) | lx;
    }

    private static byte[] chunkNbt(List<SectionSpec> sections, String status) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeByte(10);
        out.writeUTF("");
        writeString(out, "Status", status);
        out.writeByte(9);
        out.writeUTF("sections");
        out.writeByte(10);
        out.writeInt(sections.size());
        for (SectionSpec s : sections) {
            out.writeByte(1);
            out.writeUTF("Y");
            out.writeByte(s.y);
            out.writeByte(10);
            out.writeUTF("block_states");
            out.writeByte(9);
            out.writeUTF("palette");
            out.writeByte(10);
            out.writeInt(s.palette.length);
            for (String name : s.palette) {
                writeString(out, "Name", name);
                out.writeByte(0);
            }
            if (s.blocks != null) {
                long[] packed = pack(s.blocks, s.palette.length);
                out.writeByte(12);
                out.writeUTF("data");
                out.writeInt(packed.length);
                for (long l : packed) {
                    out.writeLong(l);
                }
            }
            out.writeByte(0); // end block_states
            out.writeByte(0); // end section
        }
        out.writeByte(0); // end root
        out.flush();
        return bytes.toByteArray();
    }

    private static void writeString(DataOutputStream out, String name, String value) throws IOException {
        out.writeByte(8);
        out.writeUTF(name);
        out.writeUTF(value);
    }

    private static long[] pack(int[] blocks, int paletteSize) {
        int bits = Math.max(4, 32 - Integer.numberOfLeadingZeros(Math.max(1, paletteSize - 1)));
        int perLong = 64 / bits;
        long[] data = new long[(4096 + perLong - 1) / perLong];
        for (int i = 0; i < 4096; i++) {
            int longIndex = i / perLong;
            int offset = (i % perLong) * bits;
            data[longIndex] |= ((long) blocks[i]) << offset;
        }
        return data;
    }

    private static void writeRegion(Path file, int chunkX, int chunkZ, byte[] nbt, int compression) throws IOException {
        byte[] payload = compression == 2 ? deflate(nbt) : nbt;
        int length = payload.length + 1;
        int sectors = (length + 5 + 4095) / 4096;
        ByteBuffer body = ByteBuffer.allocate(8192 + sectors * 4096);
        int headerIndex = ((chunkX & 31) + (chunkZ & 31) * 32) * 4;
        body.putInt(headerIndex, (2 << 8) | sectors);
        body.position(8192);
        body.putInt(length);
        body.put((byte) compression);
        body.put(payload);
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            body.flip();
            body.limit(body.capacity());
            while (body.hasRemaining()) {
                ch.write(body);
            }
        }
    }

    private static byte[] deflate(byte[] input) {
        Deflater deflater = new Deflater();
        deflater.setInput(input);
        deflater.finish();
        List<Byte> out = new ArrayList<>();
        byte[] buf = new byte[4096];
        while (!deflater.finished()) {
            int n = deflater.deflate(buf);
            for (int i = 0; i < n; i++) {
                out.add(buf[i]);
            }
        }
        deflater.end();
        byte[] result = new byte[out.size()];
        for (int i = 0; i < result.length; i++) {
            result[i] = out.get(i);
        }
        return result;
    }
}
