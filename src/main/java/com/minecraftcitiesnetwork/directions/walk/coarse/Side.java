package com.minecraftcitiesnetwork.directions.walk.coarse;

/** One of the four edges of a chunk. */
public enum Side {
    WEST(-1, 0), EAST(1, 0), NORTH(0, -1), SOUTH(0, 1);

    private final int dx;
    private final int dz;

    Side(int dx, int dz) {
        this.dx = dx;
        this.dz = dz;
    }

    public int dx() {
        return dx;
    }

    public int dz() {
        return dz;
    }

    public Side opposite() {
        return switch (this) {
            case WEST -> EAST;
            case EAST -> WEST;
            case NORTH -> SOUTH;
            case SOUTH -> NORTH;
        };
    }
}
