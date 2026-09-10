package com.minecraftcitiesnetwork.directions.pathfinding.grid;

import com.minecraftcitiesnetwork.directions.pathfinding.BlockFlags;
import com.minecraftcitiesnetwork.directions.pathfinding.BlockGrid;

import java.util.Arrays;

/**
 * A dense in-memory grid over a bounding box. Below the box everything is solid, above it
 * everything is air, and beside it everything is unloaded. This is the shape of both test fixtures
 * and terrain captures.
 */
public final class ArrayBlockGrid implements BlockGrid {
    private final int minX;
    private final int minY;
    private final int minZ;
    private final int sizeX;
    private final int sizeY;
    private final int sizeZ;
    private final byte[] data;

    public ArrayBlockGrid(int minX, int minY, int minZ, int sizeX, int sizeY, int sizeZ) {
        this(minX, minY, minZ, sizeX, sizeY, sizeZ, filled(sizeX * sizeY * sizeZ, BlockFlags.PASSABLE));
    }

    public ArrayBlockGrid(int minX, int minY, int minZ, int sizeX, int sizeY, int sizeZ, byte[] data) {
        if (sizeX <= 0 || sizeY <= 0 || sizeZ <= 0) {
            throw new IllegalArgumentException("grid dimensions must be positive");
        }
        if (data.length != sizeX * sizeY * sizeZ) {
            throw new IllegalArgumentException("data length " + data.length + " does not match " + sizeX + "x" + sizeY + "x" + sizeZ);
        }
        this.minX = minX;
        this.minY = minY;
        this.minZ = minZ;
        this.sizeX = sizeX;
        this.sizeY = sizeY;
        this.sizeZ = sizeZ;
        this.data = data;
    }

    private static byte[] filled(int n, byte value) {
        byte[] d = new byte[n];
        Arrays.fill(d, value);
        return d;
    }

    @Override
    public byte flags(int x, int y, int z) {
        if (x < minX || x >= minX + sizeX || z < minZ || z >= minZ + sizeZ) {
            return BlockFlags.UNLOADED;
        }
        if (y < minY) {
            return BlockFlags.SOLID;
        }
        if (y >= minY + sizeY) {
            return BlockFlags.PASSABLE;
        }
        return data[index(x, y, z)];
    }

    public void set(int x, int y, int z, byte flag) {
        if (!contains(x, y, z)) {
            throw new IndexOutOfBoundsException(x + "," + y + "," + z + " is outside the grid");
        }
        data[index(x, y, z)] = flag;
    }

    public boolean contains(int x, int y, int z) {
        return x >= minX && x < minX + sizeX && y >= minY && y < minY + sizeY && z >= minZ && z < minZ + sizeZ;
    }

    private int index(int x, int y, int z) {
        return ((y - minY) * sizeZ + (z - minZ)) * sizeX + (x - minX);
    }

    @Override
    public int minY() {
        return minY;
    }

    @Override
    public int maxY() {
        return minY + sizeY - 1;
    }

    public int minX() {
        return minX;
    }

    public int minZ() {
        return minZ;
    }

    public int sizeX() {
        return sizeX;
    }

    public int sizeY() {
        return sizeY;
    }

    public int sizeZ() {
        return sizeZ;
    }

    byte[] rawData() {
        return data;
    }

    /** Copy translated so that the box starts at x = 0, z = 0. Y is kept. */
    public ArrayBlockGrid translatedToLocalOrigin() {
        return new ArrayBlockGrid(0, minY, 0, sizeX, sizeY, sizeZ, data.clone());
    }
}
