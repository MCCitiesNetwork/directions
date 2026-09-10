package com.minecraftcitiesnetwork.directions.pathfinding;

/**
 * One byte per block describing everything the walker needs to know about it.
 *
 * <p>Exactly one flag is set per block. The flags are deliberately coarse: pathfinding only needs
 * to know whether a player can occupy a cell, stand on the cell below, or must avoid it.
 */
public final class BlockFlags {
    /** Full collision. The player can stand on it and cannot pass through it. */
    public static final byte SOLID = 1;
    /** No meaningful collision: air, plants, carpets, signs, torches. The player can occupy it. */
    public static final byte PASSABLE = 2;
    /** Water. The player can occupy it by swimming; it never counts as ground. */
    public static final byte LIQUID = 4;
    /** Doors, fence gates, trapdoors. Treated as passable at a cost regardless of open state. */
    public static final byte OPENABLE = 8;
    /** Ladders, vines, scaffolding. Occupiable, and vertical movement is allowed inside it. */
    public static final byte CLIMBABLE = 16;
    /** Lava, fire, cactus, magma and friends. Never entered. */
    public static final byte HAZARD = 32;
    /** Unknown: chunk not available. Never entered. */
    public static final byte UNLOADED = 64;
    /** Fences, walls, panes, bars: too tall to step onto, too thin to stand on. Never entered. */
    public static final byte TALL = (byte) 0x80;

    private BlockFlags() {
    }

    public static boolean isSolid(byte f) {
        return f == SOLID;
    }

    public static boolean isLiquid(byte f) {
        return f == LIQUID;
    }

    public static boolean isClimbable(byte f) {
        return f == CLIMBABLE;
    }

    public static boolean isOpenable(byte f) {
        return f == OPENABLE;
    }

    /** Whether a player's body may occupy a cell with these flags. */
    public static boolean canOccupy(byte f, boolean canSwim) {
        return switch (f) {
            case PASSABLE, OPENABLE, CLIMBABLE -> true;
            case LIQUID -> canSwim;
            default -> false;
        };
    }

    public static char symbol(byte f) {
        return switch (f) {
            case SOLID -> '#';
            case PASSABLE -> '.';
            case LIQUID -> '~';
            case OPENABLE -> 'D';
            case CLIMBABLE -> 'L';
            case HAZARD -> 'X';
            case UNLOADED -> '?';
            case TALL -> '|';
            default -> '!';
        };
    }

    public static String name(byte f) {
        return switch (f) {
            case SOLID -> "SOLID";
            case PASSABLE -> "PASSABLE";
            case LIQUID -> "LIQUID";
            case OPENABLE -> "OPENABLE";
            case CLIMBABLE -> "CLIMBABLE";
            case HAZARD -> "HAZARD";
            case UNLOADED -> "UNLOADED";
            case TALL -> "TALL";
            default -> "INVALID(" + f + ")";
        };
    }
}
