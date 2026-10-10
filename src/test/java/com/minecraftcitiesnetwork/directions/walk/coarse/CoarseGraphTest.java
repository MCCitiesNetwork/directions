package com.minecraftcitiesnetwork.directions.walk.coarse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CoarseGraphTest {
    private static ComponentId id(int cx, int cz) {
        return new ComponentId(cx, cz, cx * 16, 64, cz * 16);
    }

    private static CoarseNode node(ComponentId id, ComponentId... edges) {
        return new CoarseNode(id, 60, 70, List.of(edges));
    }

    @Test
    void replacingAChunkRewritesNeighbourEdgesBothWays() {
        CoarseGraph graph = new CoarseGraph();
        ComponentId a = id(0, 0);
        ComponentId b = id(1, 0);
        graph.replace(1, 0, List.of(node(b)), EnumSet.noneOf(Side.class));
        graph.replace(0, 0, List.of(node(a, b)), EnumSet.of(Side.EAST));
        assertEquals(List.of(a), graph.node(b).edges());

        graph.replace(1, 0, List.of(node(b, a)), EnumSet.of(Side.WEST));
        assertEquals(List.of(b), graph.node(a).edges());

        graph.replace(1, 0, List.of(node(b)), EnumSet.of(Side.WEST));
        assertTrue(graph.node(a).edges().isEmpty());
    }

    @Test
    void edgesStaySymmetricAfterAnySequenceOfReplacements() {
        Random random = new Random(42);
        CoarseGraph graph = new CoarseGraph();
        for (int step = 0; step < 20; step++) {
            int cx = random.nextInt(3);
            int cz = random.nextInt(3);
            List<CoarseNode> nodes = new ArrayList<>();
            for (int i = 0; i < random.nextInt(3); i++) {
                ComponentId self = new ComponentId(cx, cz, cx * 16 + i, 64, cz * 16);
                List<ComponentId> edges = new ArrayList<>();
                for (Side side : Side.values()) {
                    for (int j = 0; j < 2; j++) {
                        if (random.nextBoolean()) {
                            int nx = cx + side.dx();
                            int nz = cz + side.dz();
                            edges.add(new ComponentId(nx, nz, nx * 16 + j, 64, nz * 16));
                        }
                    }
                }
                nodes.add(new CoarseNode(self, 60, 70, edges));
            }
            Set<Side> sides = EnumSet.noneOf(Side.class);
            for (Side side : Side.values()) {
                if (random.nextBoolean()) {
                    sides.add(side);
                }
            }
            graph.replace(cx, cz, nodes, sides);
            assertSymmetric(graph);
            assertInterned(graph);
        }
    }

    /** Every edge is the target node's own id instance, so an edge costs no ComponentId of its own. */
    static void assertInterned(CoarseGraph graph) {
        for (List<CoarseNode> nodes : graph.snapshot().values()) {
            for (CoarseNode source : nodes) {
                for (ComponentId target : source.edges()) {
                    assertSame(graph.node(target).id(), target, "edge " + source.id() + " -> " + target);
                }
            }
        }
    }

    @Test
    void edgesShareTheTargetNodesIdInstance() {
        CoarseGraph graph = new CoarseGraph();
        graph.replace(1, 0, List.of(node(id(1, 0))), EnumSet.noneOf(Side.class));
        graph.replace(0, 1, List.of(node(id(0, 1))), EnumSet.noneOf(Side.class));
        // Equal but distinct instances, as a fresh computation produces them.
        graph.replace(0, 0, List.of(node(id(0, 0), id(1, 0), id(0, 1))), EnumSet.of(Side.EAST, Side.SOUTH));
        assertInterned(graph);
        // Recomputing the middle chunk with only one side known keeps the other side's edge.
        graph.replace(0, 0, List.of(node(id(0, 0), id(1, 0))), EnumSet.of(Side.EAST));
        assertInterned(graph);
        assertEquals(2, graph.node(id(0, 0)).edges().size());
        graph.replace(1, 0, List.of(node(id(1, 0), id(0, 0))), EnumSet.of(Side.WEST));
        assertInterned(graph);
    }

    private static void assertSymmetric(CoarseGraph graph) {
        for (List<CoarseNode> nodes : graph.snapshot().values()) {
            for (CoarseNode source : nodes) {
                for (ComponentId target : source.edges()) {
                    CoarseNode other = graph.node(target);
                    assertTrue(other != null, "edge to missing node " + target);
                    assertTrue(other.edges().contains(source.id()), "no edge back from " + target);
                }
            }
        }
    }

    @Test
    void unrecomputedSidesKeepOldEdges() {
        CoarseGraph graph = new CoarseGraph();
        ComponentId a = id(0, 0);
        ComponentId east = id(1, 0);
        ComponentId north = id(0, -1);
        graph.replace(1, 0, List.of(node(east)), EnumSet.noneOf(Side.class));
        graph.replace(0, -1, List.of(node(north)), EnumSet.noneOf(Side.class));
        graph.replace(0, 0, List.of(node(a, east, north)), EnumSet.of(Side.EAST, Side.NORTH));
        assertEquals(2, graph.node(a).edges().size());

        graph.replace(0, 0, List.of(node(a)), EnumSet.of(Side.EAST));
        assertEquals(List.of(north), graph.node(a).edges());
        assertTrue(graph.node(east).edges().isEmpty());
    }

    @Test
    void edgesToVanishedNodesAreDropped() {
        CoarseGraph graph = new CoarseGraph();
        ComponentId a = id(0, 0);
        ComponentId b = id(1, 0);
        graph.replace(1, 0, List.of(node(b)), EnumSet.noneOf(Side.class));
        graph.replace(0, 0, List.of(node(a, b)), EnumSet.of(Side.EAST));
        graph.replace(1, 0, List.of(), EnumSet.of(Side.WEST));
        assertNull(graph.node(b));
        assertTrue(graph.node(a).edges().isEmpty());
    }

    @Test
    void snapshotRoundTripsThroughOf() {
        CoarseGraph graph = new CoarseGraph();
        graph.replace(1, 0, List.of(node(id(1, 0))), EnumSet.noneOf(Side.class));
        graph.replace(-1, -1, List.of(node(id(-1, -1)), node(new ComponentId(-1, -1, -15, 64, -16))), EnumSet.noneOf(Side.class));
        CoarseGraph copy = CoarseGraph.of(graph.snapshot());
        assertEquals(graph.size(), copy.size());
        assertEquals(graph.chunkCount(), copy.chunkCount());
        assertEquals(3, copy.size());
    }

    @Test
    void boundsCoverEveryChunkEverPut() {
        CoarseGraph graph = new CoarseGraph();
        assertNull(graph.bounds());
        graph.replace(2, -3, List.of(node(id(2, -3))), EnumSet.noneOf(Side.class));
        assertEquals(new CoarseGraph.ChunkBounds(2, -3, 2, -3), graph.bounds());
        graph.replace(-5, 4, List.of(), EnumSet.noneOf(Side.class));
        assertEquals(new CoarseGraph.ChunkBounds(-5, -3, 2, 4), graph.bounds());
        CoarseGraph copy = CoarseGraph.of(graph.snapshot());
        assertEquals(graph.bounds(), copy.bounds());
    }

    @Test
    void chunkKeysRoundTripNegatives() {
        long key = ChunkKeys.key(-5, 7);
        assertEquals(-5, ChunkKeys.x(key));
        assertEquals(7, ChunkKeys.z(key));
        key = ChunkKeys.key(3, -9);
        assertEquals(3, ChunkKeys.x(key));
        assertEquals(-9, ChunkKeys.z(key));
    }

    @Test
    void isEmptyOnlyWithoutAnyNode() {
        assertTrue(new CoarseGraph().isEmpty());
        assertTrue(CoarseGraph.of(Map.of(ChunkKeys.key(0, 0), List.of())).isEmpty());
        assertFalse(CoarseGraph.of(Map.of(ChunkKeys.key(0, 0), List.of(node(id(0, 0))))).isEmpty());
    }
}
