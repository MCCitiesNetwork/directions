package com.minecraftcitiesnetwork.directions.pathfinding.grid;

import com.minecraftcitiesnetwork.directions.pathfinding.BlockClassifier;
import com.minecraftcitiesnetwork.directions.pathfinding.BlockFlags;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

/**
 * Reads a chunk's block flags straight from the world's Anvil region files, read-only and off the
 * main thread, for chunks that are generated but not loaded. Never loads or generates anything.
 *
 * <p>Only fully generated chunks in the modern (1.18+) {@code sections} schema are decoded. Anything
 * absent, partial, torn by a concurrent server write, or in an unsupported format returns
 * {@code null}, and the caller treats the chunk as unloaded. The file format and NBT walking follow
 * pathetic-bukkit's {@code world-reader} module (MIT, Benjamin Sommerfeld), reduced to what the
 * walker needs.
 */
public final class RegionFileReader {
    private static final int SECTOR_BYTES = 4096;
    private static final int COMPRESSION_GZIP = 1;
    private static final int COMPRESSION_ZLIB = 2;
    private static final int COMPRESSION_NONE = 3;
    private static final int EXTERNAL_FLAG = 0x80;

    private static final int TAG_END = 0;
    private static final int TAG_BYTE = 1;
    private static final int TAG_SHORT = 2;
    private static final int TAG_INT = 3;
    private static final int TAG_LONG = 4;
    private static final int TAG_FLOAT = 5;
    private static final int TAG_DOUBLE = 6;
    private static final int TAG_BYTE_ARRAY = 7;
    private static final int TAG_STRING = 8;
    private static final int TAG_LIST = 9;
    private static final int TAG_COMPOUND = 10;
    private static final int TAG_INT_ARRAY = 11;
    private static final int TAG_LONG_ARRAY = 12;

    private final Path regionDirectory;
    private final BlockClassifier classifier;
    private final int worldMinY;
    private final int worldMaxY;
    private final int bandHeight;
    private final Map<String, Byte> flagsByName = new ConcurrentHashMap<>();

    public RegionFileReader(Path regionDirectory, BlockClassifier classifier, int worldMinY, int worldMaxY, int bandHeight) {
        this.regionDirectory = regionDirectory;
        this.classifier = classifier;
        this.worldMinY = worldMinY;
        this.worldMaxY = worldMaxY;
        this.bandHeight = bandHeight;
    }

    /** Decoded flags for the chunk, or {@code null} when it is not cleanly available on disk. */
    public ChunkFlags read(int chunkX, int chunkZ) {
        Path file = regionDirectory.resolve("r." + (chunkX >> 5) + "." + (chunkZ >> 5) + ".mca");
        byte[] nbt = readChunkNbt(file, chunkX, chunkZ);
        if (nbt == null) {
            return null;
        }
        try {
            Map<String, Object> root = readNbt(nbt);
            if (root == null) {
                return null;
            }
            Object status = root.get("Status");
            if (status instanceof String s && !(s.endsWith("full"))) {
                return null; // not fully generated: block data is not final
            }
            Object sections = root.get("sections");
            if (!(sections instanceof List<?> list)) {
                return null;
            }
            Map<Integer, Section> bySectionY = new HashMap<>();
            for (Object element : list) {
                if (!(element instanceof Map<?, ?> section)) {
                    continue;
                }
                Object y = section.get("Y");
                Object states = section.get("block_states");
                if (!(y instanceof Number sy) || !(states instanceof Map<?, ?> blockStates)) {
                    continue;
                }
                Section decoded = decodeSection(blockStates);
                if (decoded != null) {
                    bySectionY.put(sy.intValue(), decoded);
                }
            }
            if (bySectionY.isEmpty()) {
                return null;
            }
            BandDecoder.ColumnSource source = (lx, y, lz) -> {
                Section s = bySectionY.get(y >> 4);
                return s == null ? BlockFlags.PASSABLE : s.flagsAt(lx, y & 15, lz);
            };
            int[] highest = BandDecoder.highestNonAir(source, worldMinY, worldMaxY);
            return BandDecoder.decode(source, highest, worldMinY, worldMaxY, bandHeight);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private Section decodeSection(Map<?, ?> blockStates) {
        Object paletteTag = blockStates.get("palette");
        if (!(paletteTag instanceof List<?> palette) || palette.isEmpty()) {
            return null;
        }
        byte[] paletteFlags = new byte[palette.size()];
        for (int i = 0; i < paletteFlags.length; i++) {
            paletteFlags[i] = flagsOf(palette.get(i));
        }
        if (paletteFlags.length == 1) {
            return new Section(paletteFlags, null, 0);
        }
        Object dataTag = blockStates.get("data");
        if (!(dataTag instanceof long[] data)) {
            return null;
        }
        int bits = bitsPerIndex(data.length, paletteFlags.length);
        if (bits <= 0) {
            return null;
        }
        return new Section(paletteFlags, data, bits);
    }

    private byte flagsOf(Object paletteEntry) {
        if (!(paletteEntry instanceof Map<?, ?> entry)) {
            return BlockFlags.PASSABLE;
        }
        Object name = entry.get("Name");
        if (!(name instanceof String n)) {
            return BlockFlags.PASSABLE;
        }
        return flagsByName.computeIfAbsent(n, classifier::classify);
    }

    /**
     * The modern layout never spans a long, so the array length pins down the width exactly. Falls
     * back to the standard {@code max(4, ceil(log2(paletteSize)))} if nothing matches.
     */
    static int bitsPerIndex(int dataLength, int paletteSize) {
        for (int bits = 4; bits <= 31; bits++) {
            int perLong = 64 / bits;
            int needed = (4096 + perLong - 1) / perLong;
            if (needed == dataLength) {
                return bits;
            }
        }
        int needed = 32 - Integer.numberOfLeadingZeros(Math.max(1, paletteSize - 1));
        return Math.max(4, needed);
    }

    /** One 16x16x16 section: palette flags plus packed indices. */
    private static final class Section {
        private final byte[] paletteFlags;
        private final long[] data;
        private final int bits;
        private final int perLong;

        Section(byte[] paletteFlags, long[] data, int bits) {
            this.paletteFlags = paletteFlags;
            this.data = data;
            this.bits = bits;
            this.perLong = bits == 0 ? 0 : 64 / bits;
        }

        byte flagsAt(int lx, int ly, int lz) {
            if (data == null) {
                return paletteFlags[0];
            }
            int blockIndex = (ly << 8) | (lz << 4) | lx;
            int longIndex = blockIndex / perLong;
            int bitOffset = (blockIndex % perLong) * bits;
            long mask = (1L << bits) - 1;
            int index = (int) ((data[longIndex] >>> bitOffset) & mask);
            return index < paletteFlags.length ? paletteFlags[index] : BlockFlags.PASSABLE;
        }
    }

    // ---- region file access ------------------------------------------------------------------

    static byte[] readChunkNbt(Path regionFile, int chunkX, int chunkZ) {
        if (!Files.isReadable(regionFile)) {
            return null;
        }
        try (FileChannel channel = FileChannel.open(regionFile, StandardOpenOption.READ)) {
            long size = channel.size();
            if (size < SECTOR_BYTES * 2L) {
                return null;
            }
            int headerIndex = ((chunkX & 31) + (chunkZ & 31) * 32) * 4;
            ByteBuffer header = read(channel, headerIndex, 4);
            if (header == null) {
                return null;
            }
            int location = header.getInt();
            if (location == 0) {
                return null;
            }
            int sectorOffset = (location >>> 8) & 0xFFFFFF;
            int sectorCount = location & 0xFF;
            if (sectorOffset < 2 || sectorCount == 0) {
                return null;
            }
            long start = (long) sectorOffset * SECTOR_BYTES;
            if (start + 5 > size) {
                return null;
            }
            ByteBuffer prefix = read(channel, start, 5);
            if (prefix == null) {
                return null;
            }
            int length = prefix.getInt();
            int compression = prefix.get() & 0xFF;
            if (length <= 1 || (compression & EXTERNAL_FLAG) != 0) {
                return null;
            }
            int dataLength = length - 1;
            if (dataLength > sectorCount * SECTOR_BYTES || start + 5 + dataLength > size) {
                return null; // torn or inconsistent header
            }
            ByteBuffer data = read(channel, start + 5, dataLength);
            if (data == null) {
                return null;
            }
            byte[] raw = new byte[dataLength];
            data.get(raw);
            return decompress(compression, raw);
        } catch (IOException e) {
            return null;
        }
    }

    private static byte[] decompress(int compression, byte[] raw) throws IOException {
        return switch (compression) {
            case COMPRESSION_NONE -> raw;
            case COMPRESSION_GZIP -> readAll(new GZIPInputStream(new ByteArrayInputStream(raw)));
            case COMPRESSION_ZLIB -> readAll(new InflaterInputStream(new ByteArrayInputStream(raw)));
            default -> null;
        };
    }

    private static byte[] readAll(InputStream in) throws IOException {
        try (InputStream stream = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(64 * 1024);
            byte[] buffer = new byte[16 * 1024];
            int read;
            while ((read = stream.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        }
    }

    private static ByteBuffer read(FileChannel channel, long position, int length) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(length);
        long offset = position;
        while (buffer.hasRemaining()) {
            int read = channel.read(buffer, offset);
            if (read < 0) {
                return null;
            }
            offset += read;
        }
        buffer.flip();
        return buffer;
    }

    // ---- minimal NBT -------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    static Map<String, Object> readNbt(byte[] bytes) throws IOException {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            int rootType = in.readUnsignedByte();
            if (rootType != TAG_COMPOUND) {
                return null;
            }
            in.readUTF();
            return (Map<String, Object>) readPayload(in, TAG_COMPOUND);
        } catch (EOFException e) {
            return null;
        }
    }

    private static Object readPayload(DataInputStream in, int type) throws IOException {
        switch (type) {
            case TAG_BYTE:
                return in.readByte();
            case TAG_SHORT:
                return in.readShort();
            case TAG_INT:
                return in.readInt();
            case TAG_LONG:
                return in.readLong();
            case TAG_FLOAT:
                return in.readFloat();
            case TAG_DOUBLE:
                return in.readDouble();
            case TAG_BYTE_ARRAY: {
                byte[] array = new byte[in.readInt()];
                in.readFully(array);
                return array;
            }
            case TAG_STRING:
                return in.readUTF();
            case TAG_LIST: {
                int elementType = in.readUnsignedByte();
                int length = in.readInt();
                List<Object> list = new ArrayList<>(Math.max(0, length));
                for (int i = 0; i < length; i++) {
                    list.add(readPayload(in, elementType));
                }
                return list;
            }
            case TAG_COMPOUND: {
                Map<String, Object> map = new HashMap<>();
                while (true) {
                    int t = in.readUnsignedByte();
                    if (t == TAG_END) {
                        return map;
                    }
                    String name = in.readUTF();
                    map.put(name, readPayload(in, t));
                }
            }
            case TAG_INT_ARRAY: {
                int[] array = new int[in.readInt()];
                for (int i = 0; i < array.length; i++) {
                    array[i] = in.readInt();
                }
                return array;
            }
            case TAG_LONG_ARRAY: {
                long[] array = new long[in.readInt()];
                for (int i = 0; i < array.length; i++) {
                    array[i] = in.readLong();
                }
                return array;
            }
            default:
                throw new IOException("Unknown NBT tag type " + type);
        }
    }
}
