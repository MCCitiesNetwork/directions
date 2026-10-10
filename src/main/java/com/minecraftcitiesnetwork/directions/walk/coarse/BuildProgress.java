package com.minecraftcitiesnetwork.directions.walk.coarse;

/** Where a world build has got to: the region and row last finished, and whether the whole world is done. */
public record BuildProgress(int regionX, int regionZ, int row, boolean done) {
    /** No build has started. */
    public static final BuildProgress NONE = new BuildProgress(-1, -1, -1, false);
}
