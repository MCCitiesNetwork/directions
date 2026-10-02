package com.minecraftcitiesnetwork.directions.walk;

/** What a block means to a walking player. */
public enum BlockKind {
    /** Nothing to collide with: air, flowers, carpets, signs, torches, rails, pressure plates. */
    OPEN,
    /** A full collision block that can be stood on. */
    SOLID,
    /** A low block (slab, stairs) that can be stood on and walked up without jumping. */
    HALF,
    /** Doors, fence gates and trapdoors: passable, but slower. */
    DOOR,
    /** Water: swimmable at its surface. */
    WATER,
    /** Ladders, vines and scaffolding: climbable. */
    CLIMB,
    /** Fences, walls, panes and bars: block the body and cannot be stood on. */
    FENCE,
    /** Lava, fire, cactus and similar: never entered or stood on. */
    HAZARD;

    /** A player's body can occupy this block. */
    public boolean passable() {
        return this == OPEN || this == DOOR || this == CLIMB;
    }

    /** A player can stand on top of this block. */
    public boolean supports() {
        return this == SOLID || this == HALF;
    }
}
