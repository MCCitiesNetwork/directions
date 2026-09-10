package com.minecraftcitiesnetwork.directions.pathfinding.engine;

import de.bsommerfeld.pathetic.api.pathing.INeighborStrategy;
import de.bsommerfeld.pathetic.api.wrapper.PathPosition;
import de.bsommerfeld.pathetic.api.wrapper.PathVector;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The 26 unit moves of a walker: eight horizontal directions, each flat, one up or one down, plus
 * straight up and straight down for ladders, water and falling.
 *
 * <p>Also the budget enforcement point: once the current search has run out of time this returns no
 * offsets, so the engine stops growing the open set and drains what is left in a few milliseconds.
 * The engine runs synchronously and offers no abort handle until it returns, so starving it is the
 * cleanest way to stop early.
 */
final class WalkerNeighbors implements INeighborStrategy {
    private static final List<PathVector> ALL = buildAll();
    private static final List<PathVector> FLAT = buildFlat();
    private static final List<PathVector> NONE = Collections.emptyList();

    private final boolean flatOnly;

    WalkerNeighbors(boolean flatOnly) {
        this.flatOnly = flatOnly;
    }

    @Override
    public Iterable<PathVector> getOffsets() {
        return flatOnly ? FLAT : ALL;
    }

    @Override
    public Iterable<PathVector> getOffsets(PathPosition currentPosition) {
        if (SearchClock.expired()) {
            return NONE;
        }
        return flatOnly ? FLAT : ALL;
    }

    private static List<PathVector> buildAll() {
        List<PathVector> out = new ArrayList<>(26);
        for (int dy : new int[]{0, 1, -1}) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) {
                        continue;
                    }
                    out.add(new PathVector(dx, dy, dz));
                }
            }
        }
        return Collections.unmodifiableList(out);
    }

    /** Four cardinal moves only. Used by the reference tests. */
    private static List<PathVector> buildFlat() {
        return List.of(new PathVector(1, 0, 0), new PathVector(-1, 0, 0), new PathVector(0, 0, 1), new PathVector(0, 0, -1));
    }
}
