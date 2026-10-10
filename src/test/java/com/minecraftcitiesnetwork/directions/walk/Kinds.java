package com.minecraftcitiesnetwork.directions.walk;

/** One-character block codes for test worlds. */
public final class Kinds {
    private Kinds() {
    }

    public static BlockKind of(char c) {
        return switch (c) {
            case 'S' -> BlockKind.SOLID;
            case 'H' -> BlockKind.HALF;
            case '.' -> BlockKind.OPEN;
            case 'D' -> BlockKind.DOOR;
            case 'W' -> BlockKind.WATER;
            case 'L' -> BlockKind.CLIMB;
            case 'F' -> BlockKind.FENCE;
            case 'X' -> BlockKind.HAZARD;
            default -> throw new IllegalArgumentException("unknown block code '" + c + "'");
        };
    }
}
