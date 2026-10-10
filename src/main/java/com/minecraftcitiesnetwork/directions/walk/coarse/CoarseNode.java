package com.minecraftcitiesnetwork.directions.walk.coarse;

import java.util.Comparator;
import java.util.List;

/** A walkable component of a chunk and the components of neighbouring chunks it touches; edges sorted, without repeats. */
public record CoarseNode(ComponentId id, int minY, int maxY, List<ComponentId> edges) {
    private static final Comparator<ComponentId> EDGE_ORDER = Comparator
            .comparingInt(ComponentId::chunkX)
            .thenComparingInt(ComponentId::chunkZ)
            .thenComparingInt(ComponentId::x)
            .thenComparingInt(ComponentId::y)
            .thenComparingInt(ComponentId::z);

    public CoarseNode {
        edges = edges.stream().distinct().sorted(EDGE_ORDER).toList();
    }

    /** The side of this node's chunk on which {@code other}'s chunk lies, or null if it is not a 4-neighbour. */
    public Side sideOf(ComponentId other) {
        int dx = other.chunkX() - id.chunkX();
        int dz = other.chunkZ() - id.chunkZ();
        for (Side side : Side.values()) {
            if (side.dx() == dx && side.dz() == dz) {
                return side;
            }
        }
        return null;
    }
}
