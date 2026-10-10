package com.minecraftcitiesnetwork.directions.walk.coarse;

import com.minecraftcitiesnetwork.directions.walk.BlockKind;
import com.minecraftcitiesnetwork.directions.walk.BlockWorld;
import com.minecraftcitiesnetwork.directions.walk.Terrain;
import com.minecraftcitiesnetwork.directions.walk.WalkRules;
import com.minecraftcitiesnetwork.directions.walk.WalkerProfile;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ChunkEdgesTest {
    private final WalkRules rules = new WalkRules(WalkerProfile.DEFAULT);

    private ChunkEdges.Linked link(BlockWorld world, int chunkX, int chunkZ) {
        Terrain terrain = world.terrain();
        return ChunkEdges.compute(terrain, rules, chunkX, chunkZ,
                ChunkComponents.of(terrain, chunkX, chunkZ, rules));
    }

    private static int edgeCount(ChunkEdges.Linked linked) {
        return linked.nodes().stream().mapToInt(n -> n.edges().size()).sum();
    }

    @Test
    void flatNeighboursConnectOnEverySide() {
        BlockWorld world = new BlockWorld(0).fill(0, 64, 0, 47, 64, 47, BlockKind.SOLID);
        ChunkEdges.Linked linked = link(world, 1, 1);
        assertEquals(1, linked.nodes().size());
        assertEquals(List.of(new ComponentId(0, 1, 8, 65, 24), new ComponentId(1, 0, 24, 65, 8),
                        new ComponentId(1, 2, 24, 65, 40), new ComponentId(2, 1, 40, 65, 24)),
                linked.nodes().get(0).edges());
        assertEquals(EnumSet.allOf(Side.class), linked.sides());
    }

    @Test
    void unknownNeighbourSideIsNotReported() {
        BlockWorld world = new BlockWorld(0).fill(0, 64, 0, 31, 64, 47, BlockKind.SOLID);
        ChunkEdges.Linked linked = link(world, 1, 1);
        assertEquals(EnumSet.of(Side.WEST, Side.NORTH, Side.SOUTH), linked.sides());
        assertEquals(3, edgeCount(linked));
    }

    @Test
    void wallAlongTheBorderGivesNoEdge() {
        BlockWorld world = new BlockWorld(0).fill(0, 64, 0, 31, 64, 15, BlockKind.SOLID);
        // A wall too high to drop from, so the wall top cannot reach the next chunk either.
        world.fill(15, 65, 0, 15, 70, 15, BlockKind.SOLID);
        ChunkEdges.Linked linked = link(world, 0, 0);
        assertEquals(0, edgeCount(linked));
        assertEquals(EnumSet.of(Side.EAST), linked.sides());
    }

    @Test
    void cornerOnlyCrossingGivesNoEdge() {
        BlockWorld world = new BlockWorld(0)
                .set(15, 64, 15, BlockKind.SOLID)
                .set(16, 64, 16, BlockKind.SOLID)
                .set(16, 64, 0, BlockKind.OPEN)
                .set(0, 64, 16, BlockKind.OPEN);
        ChunkEdges.Linked linked = link(world, 0, 0);
        assertEquals(0, edgeCount(linked));
        assertEquals(EnumSet.of(Side.EAST, Side.SOUTH), linked.sides());
    }

    @Test
    void stepUpAcrossTheBorderConnects() {
        BlockWorld one = new BlockWorld(0).fill(0, 64, 0, 15, 64, 15, BlockKind.SOLID)
                .fill(16, 64, 0, 31, 65, 15, BlockKind.SOLID);
        assertEquals(1, edgeCount(link(one, 0, 0)));
        BlockWorld two = new BlockWorld(0).fill(0, 64, 0, 15, 64, 15, BlockKind.SOLID)
                .fill(16, 64, 0, 31, 66, 15, BlockKind.SOLID);
        assertEquals(0, edgeCount(link(two, 0, 0)));
    }

    @Test
    void twoComponentsEachConnectToTheirOwnSide() {
        BlockWorld world = new BlockWorld(0).fill(-16, 64, 0, 31, 64, 15, BlockKind.SOLID);
        world.fill(7, 64, 0, 8, 64, 15, BlockKind.OPEN);
        ChunkEdges.Linked linked = link(world, 0, 0);
        assertEquals(2, linked.nodes().size());
        assertEquals(List.of(new ComponentId(-1, 0, -8, 65, 8)), linked.nodes().get(0).edges());
        assertEquals(List.of(new ComponentId(1, 0, 24, 65, 8)), linked.nodes().get(1).edges());
    }
}
