package com.minecraftcitiesnetwork.directions.walk.coarse;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Lists the chunks that exist in a region file by reading only its 4,096-byte location table. */
public final class RegionHeaders {
    private static final Pattern REGION_FILE = Pattern.compile("^r\\.(-?\\d+)\\.(-?\\d+)\\.mca$");
    private static final int HEADER_BYTES = 4096;
    private static final int REGION_WIDTH = 32;

    private RegionHeaders() {
    }

    /** The region position named by a file such as {@code r.-3.7.mca}, or null for any other name. */
    public static ChunkPos regionOf(String fileName) {
        Matcher matcher = REGION_FILE.matcher(fileName);
        if (!matcher.matches()) {
            return null;
        }
        try {
            return new ChunkPos(Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)));
        } catch (NumberFormatException tooLarge) {
            return null;
        }
    }

    /** Absolute positions of the chunks present in the region file, in row order (z, then x). */
    public static List<ChunkPos> chunksIn(Path regionFile) throws IOException {
        String name = String.valueOf(regionFile.getFileName());
        ChunkPos region = regionOf(name);
        if (region == null) {
            throw new IllegalArgumentException("Not a region file name: " + name);
        }
        byte[] header = new byte[HEADER_BYTES];
        try (InputStream in = Files.newInputStream(regionFile)) {
            new DataInputStream(in).readFully(header);
        } catch (EOFException tooShort) {
            throw new IOException("Region file " + name + " is shorter than the " + HEADER_BYTES + "-byte header", tooShort);
        }
        ByteBuffer entries = ByteBuffer.wrap(header);
        List<ChunkPos> chunks = new ArrayList<>();
        for (int index = 0; index < HEADER_BYTES / 4; index++) {
            int sectorOffset = entries.getInt() >>> 8;
            if (sectorOffset != 0) {
                chunks.add(new ChunkPos(region.x() * REGION_WIDTH + (index & 31), region.z() * REGION_WIDTH + (index >> 5)));
            }
        }
        return chunks;
    }
}
