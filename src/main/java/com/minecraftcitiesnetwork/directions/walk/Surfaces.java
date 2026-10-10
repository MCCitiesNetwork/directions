package com.minecraftcitiesnetwork.directions.walk;

/**
 * A walkable surface packed into one {@code int}: the feet height (low 16 bits, signed), the
 * footing, the free blocks above the feet (capped at {@link #MAX_HEADROOM}), whether a door is in
 * the way, and whether the block underneath is a slab or stair.
 */
public final class Surfaces {
    public static final int MAX_HEADROOM = 7;
    private static final int FOOTING_SHIFT = 16;
    private static final int HEADROOM_SHIFT = 18;
    private static final int DOOR_BIT = 1 << 21;
    private static final int HALF_BIT = 1 << 22;
    private static final Footing[] FOOTINGS = Footing.values();

    private Surfaces() {
    }

    public static int pack(int y, Footing footing, int headroom, boolean door, boolean halfSupport) {
        return (y & 0xFFFF)
                | (footing.ordinal() << FOOTING_SHIFT)
                | (Math.min(headroom, MAX_HEADROOM) << HEADROOM_SHIFT)
                | (door ? DOOR_BIT : 0)
                | (halfSupport ? HALF_BIT : 0);
    }

    public static int y(int surface) {
        return (short) (surface & 0xFFFF);
    }

    public static Footing footing(int surface) {
        return FOOTINGS[(surface >>> FOOTING_SHIFT) & 0x3];
    }

    public static int headroom(int surface) {
        return (surface >>> HEADROOM_SHIFT) & 0x7;
    }

    public static boolean door(int surface) {
        return (surface & DOOR_BIT) != 0;
    }

    public static boolean halfSupport(int surface) {
        return (surface & HALF_BIT) != 0;
    }
}
