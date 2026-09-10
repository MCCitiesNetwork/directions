package com.minecraftcitiesnetwork.directions.pathfinding.grid;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Reads and writes {@code .grid.gz} files: a small header and one flags byte per block, gzipped.
 * The format carries no materials, names or world identity.
 */
public final class GridCodec {
    private static final int MAGIC = 0x44475244; // "DGRD"
    private static final int VERSION = 1;

    private GridCodec() {
    }

    public static void write(ArrayBlockGrid grid, Path file) throws IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        try (OutputStream out = Files.newOutputStream(file)) {
            write(grid, out);
        }
    }

    public static void write(ArrayBlockGrid grid, OutputStream out) throws IOException {
        try (DataOutputStream data = new DataOutputStream(new GZIPOutputStream(out))) {
            data.writeInt(MAGIC);
            data.writeInt(VERSION);
            data.writeInt(grid.minX());
            data.writeInt(grid.minY());
            data.writeInt(grid.minZ());
            data.writeInt(grid.sizeX());
            data.writeInt(grid.sizeY());
            data.writeInt(grid.sizeZ());
            data.write(grid.rawData());
        }
    }

    public static ArrayBlockGrid read(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            return read(in);
        }
    }

    public static ArrayBlockGrid read(InputStream in) throws IOException {
        try (DataInputStream data = new DataInputStream(new GZIPInputStream(in))) {
            if (data.readInt() != MAGIC) {
                throw new IOException("not a Directions grid file");
            }
            int version = data.readInt();
            if (version != VERSION) {
                throw new IOException("unsupported grid version " + version);
            }
            int minX = data.readInt();
            int minY = data.readInt();
            int minZ = data.readInt();
            int sizeX = data.readInt();
            int sizeY = data.readInt();
            int sizeZ = data.readInt();
            long cells = (long) sizeX * sizeY * sizeZ;
            if (sizeX <= 0 || sizeY <= 0 || sizeZ <= 0 || cells > Integer.MAX_VALUE) {
                throw new IOException("invalid grid dimensions " + sizeX + "x" + sizeY + "x" + sizeZ);
            }
            byte[] bytes = new byte[(int) cells];
            data.readFully(bytes);
            return new ArrayBlockGrid(minX, minY, minZ, sizeX, sizeY, sizeZ, bytes);
        }
    }
}
