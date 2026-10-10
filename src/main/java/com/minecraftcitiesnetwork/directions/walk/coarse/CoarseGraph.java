package com.minecraftcitiesnetwork.directions.walk.coarse;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Per-world graph of chunk components. Writes are serialised; reads are lock-free over immutable lists. */
public final class CoarseGraph {
    /** The smallest box of chunk coordinates, inclusive, that holds every chunk the graph has. */
    public record ChunkBounds(int minX, int minZ, int maxX, int maxZ) {
        ChunkBounds including(int x, int z) {
            return new ChunkBounds(Math.min(minX, x), Math.min(minZ, z), Math.max(maxX, x), Math.max(maxZ, z));
        }
    }

    private final ConcurrentHashMap<Long, List<CoarseNode>> chunks = new ConcurrentHashMap<>();
    /** Only grows: chunks are never removed. Written under the replace lock. */
    private volatile ChunkBounds bounds;

    /** A graph of the given nodes as they are; edges are not made to share their targets' id instances. */
    public static CoarseGraph of(Map<Long, List<CoarseNode>> source) {
        CoarseGraph graph = new CoarseGraph();
        source.forEach((key, nodes) -> {
            graph.chunks.put(key, List.copyOf(nodes));
            graph.include(ChunkKeys.x(key), ChunkKeys.z(key));
        });
        return graph;
    }

    public List<CoarseNode> nodesOf(int chunkX, int chunkZ) {
        return chunks.getOrDefault(ChunkKeys.key(chunkX, chunkZ), List.of());
    }

    public CoarseNode node(ComponentId id) {
        for (CoarseNode node : nodesOf(id.chunkX(), id.chunkZ())) {
            if (node.id().equals(id)) {
                return node;
            }
        }
        return null;
    }

    /** The chunk extent of the graph, or null when it has no chunk. */
    public ChunkBounds bounds() {
        return bounds;
    }

    private void include(int chunkX, int chunkZ) {
        ChunkBounds current = bounds;
        bounds = current == null ? new ChunkBounds(chunkX, chunkZ, chunkX, chunkZ) : current.including(chunkX, chunkZ);
    }

    public boolean contains(int chunkX, int chunkZ) {
        return chunks.containsKey(ChunkKeys.key(chunkX, chunkZ));
    }

    public int size() {
        int total = 0;
        for (List<CoarseNode> nodes : chunks.values()) {
            total += nodes.size();
        }
        return total;
    }

    /** Whether the graph has no node at all; stops at the first chunk that has one. */
    public boolean isEmpty() {
        for (List<CoarseNode> nodes : chunks.values()) {
            if (!nodes.isEmpty()) {
                return false;
            }
        }
        return true;
    }

    public int chunkCount() {
        return chunks.size();
    }

    public synchronized Map<Long, List<CoarseNode>> snapshot() {
        return new HashMap<>(chunks);
    }

    /**
     * Replaces the nodes of a chunk. On a recomputed side the supplied edges stand and the neighbouring
     * chunk is rewritten to match; on any other side the old edges of nodes with the same id are kept.
     * Edges to nodes that do not exist are dropped, so edges stay symmetric.
     */
    public synchronized void replace(int chunkX, int chunkZ, List<CoarseNode> nodes, Set<Side> recomputedSides) {
        List<CoarseNode> old = nodesOf(chunkX, chunkZ);
        List<CoarseNode> rebuilt = new ArrayList<>();
        for (CoarseNode node : nodes) {
            CoarseNode previous = null;
            for (CoarseNode candidate : old) {
                if (candidate.id().equals(node.id())) {
                    previous = candidate;
                }
            }
            List<ComponentId> edges = new ArrayList<>();
            for (ComponentId edge : node.edges()) {
                Side side = node.sideOf(edge);
                ComponentId target = side != null && recomputedSides.contains(side) ? canonical(edge) : null;
                if (target != null) {
                    edges.add(target);
                }
            }
            if (previous != null) {
                for (ComponentId edge : previous.edges()) {
                    Side side = previous.sideOf(edge);
                    ComponentId target = side != null && !recomputedSides.contains(side) ? canonical(edge) : null;
                    if (target != null) {
                        edges.add(target);
                    }
                }
            }
            // Keeping the old instance keeps the neighbours' edges to this node canonical.
            ComponentId id = previous != null ? previous.id() : node.id();
            rebuilt.add(new CoarseNode(id, node.minY(), node.maxY(), edges));
        }
        chunks.put(ChunkKeys.key(chunkX, chunkZ), List.copyOf(rebuilt));
        include(chunkX, chunkZ);

        for (Side side : Side.values()) {
            int nx = chunkX + side.dx();
            int nz = chunkZ + side.dz();
            List<CoarseNode> neighbours = nodesOf(nx, nz);
            if (neighbours.isEmpty()) {
                continue;
            }
            List<CoarseNode> updated = new ArrayList<>();
            for (CoarseNode neighbour : neighbours) {
                List<ComponentId> edges = new ArrayList<>();
                for (ComponentId edge : neighbour.edges()) {
                    boolean intoThisChunk = edge.chunkX() == chunkX && edge.chunkZ() == chunkZ;
                    if (!intoThisChunk || !recomputedSides.contains(side) && hasEdgeBack(rebuilt, edge, neighbour.id())) {
                        edges.add(edge);
                    }
                }
                if (recomputedSides.contains(side)) {
                    for (CoarseNode fresh : rebuilt) {
                        if (fresh.edges().contains(neighbour.id())) {
                            edges.add(fresh.id());
                        }
                    }
                }
                updated.add(new CoarseNode(neighbour.id(), neighbour.minY(), neighbour.maxY(), edges));
            }
            chunks.put(ChunkKeys.key(nx, nz), List.copyOf(updated));
        }
    }

    /**
     * The id instance of the existing node equal to {@code id}, or null when there is none. Edges
     * hold that instance rather than an equal copy, so an edge costs a reference and no object.
     */
    private ComponentId canonical(ComponentId id) {
        CoarseNode node = node(id);
        return node == null ? null : node.id();
    }

    private static boolean hasEdgeBack(List<CoarseNode> nodes, ComponentId target, ComponentId source) {
        for (CoarseNode node : nodes) {
            if (node.id().equals(target)) {
                return node.edges().contains(source);
            }
        }
        return false;
    }
}
