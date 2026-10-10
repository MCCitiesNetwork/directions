package com.minecraftcitiesnetwork.directions.walk.coarse;

import com.minecraftcitiesnetwork.directions.walk.BlockKind;
import com.minecraftcitiesnetwork.directions.walk.BlockWorld;
import com.minecraftcitiesnetwork.directions.walk.Cell;
import com.minecraftcitiesnetwork.directions.walk.Terrain;
import com.minecraftcitiesnetwork.directions.walk.WalkRules;
import com.minecraftcitiesnetwork.directions.walk.WalkerProfile;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunkComponentsTest {
    private final WalkRules rules = new WalkRules(WalkerProfile.DEFAULT);

    private static BlockWorld flat() {
        return new BlockWorld(0).fill(0, 64, 0, 15, 64, 15, BlockKind.SOLID);
    }

    private static BlockWorld river() {
        BlockWorld world = flat();
        world.fill(7, 64, 0, 8, 64, 15, BlockKind.OPEN);
        return world;
    }

    @Test
    void flatChunkIsOneComponentWithCentreRepresentative() {
        List<Component> components = ChunkComponents.of(flat().terrain(), 0, 0, rules);
        assertEquals(1, components.size());
        Component c = components.get(0);
        assertEquals(new ComponentId(0, 0, 8, 65, 8), c.id());
        assertEquals(65, c.minY());
        assertEquals(65, c.maxY());
        assertEquals(16, c.borderCells().get(Side.WEST).length);
    }

    @Test
    void riverThroughTheChunkGivesTwoComponents() {
        List<Component> components = ChunkComponents.of(river().terrain(), 0, 0, rules);
        assertEquals(List.of(new ComponentId(0, 0, 6, 65, 8), new ComponentId(0, 0, 9, 65, 8)),
                components.stream().map(Component::id).toList());
    }

    @Test
    void bridgeOverARoadGivesTwoComponents() {
        BlockWorld world = flat().fill(0, 68, 6, 15, 68, 9, BlockKind.SOLID);
        List<Component> components = ChunkComponents.of(world.terrain(), 0, 0, rules);
        assertEquals(2, components.size());
        assertEquals(List.of(65, 69), components.stream().map(c -> c.minY()).sorted().toList());
    }

    @Test
    void stairsJoinTwoLevelsIntoOne() {
        BlockWorld world = new BlockWorld(0)
                .fill(0, 64, 0, 7, 64, 15, BlockKind.SOLID)
                .fill(8, 64, 0, 15, 65, 15, BlockKind.SOLID);
        List<Component> components = ChunkComponents.of(world.terrain(), 0, 0, rules);
        assertEquals(1, components.size());
        assertEquals(65, components.get(0).minY());
        assertEquals(66, components.get(0).maxY());
    }

    /** Floor (feet at 65) with two 3-high pillars (feet at 68): one can drop off a pillar but not climb it. */
    private static BlockWorld pillars() {
        return flat().fill(2, 65, 2, 3, 67, 3, BlockKind.SOLID).fill(12, 65, 12, 13, 67, 13, BlockKind.SOLID);
    }

    @Test
    void aLedgeReachableOnlyByADropJoinsTheGroundBelow() {
        List<Component> components = ChunkComponents.of(pillars().terrain(), 0, 0, rules);
        assertEquals(1, components.size(), components::toString);
        assertEquals(65, components.get(0).minY());
        assertEquals(68, components.get(0).maxY());
        assertEquals(components.get(0).id(), ChunkComponents.componentOf(pillars().terrain(), 0, 0, 2, 68, 2, rules));
    }

    @Test
    void componentsPartitionTheCellsOfAMultiLevelChunk() {
        // Pillars, a bridge too high to reach from the floor, and a one-way drop off the bridge's end.
        BlockWorld world = pillars().fill(0, 68, 6, 15, 68, 9, BlockKind.SOLID)
                .fill(0, 65, 10, 1, 69, 15, BlockKind.SOLID);
        Terrain terrain = world.terrain();
        List<Component> components = ChunkComponents.of(terrain, 0, 0, rules);
        java.util.Set<Long> all = new java.util.HashSet<>();
        int total = 0;
        for (Component component : components) {
            java.util.Set<Long> cells = ChunkComponents.cellsOf(terrain, component.id(), rules);
            total += cells.size();
            all.addAll(cells);
            for (long key : cells) {
                Cell cell = Cell.fromKey(key);
                assertEquals(component.id(),
                        ChunkComponents.componentOf(terrain, 0, 0, cell.x(), cell.y(), cell.z(), rules));
            }
        }
        assertEquals(all.size(), total, "a cell belongs to two components");
        int surfaces = 0;
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                surfaces += terrain.surfaces(x, z).length;
            }
        }
        assertEquals(surfaces, all.size(), "every surface cell is in a component");
        assertEquals(components.size(), components.stream().map(Component::id).distinct().count(),
                "two components share a representative");
    }

    @Test
    void emptyChunkHasNoComponents() {
        BlockWorld air = new BlockWorld(0).set(0, 64, 0, BlockKind.OPEN);
        assertTrue(ChunkComponents.of(air.terrain(), 0, 0, rules).isEmpty());
    }

    @Test
    void unknownColumnThrows() {
        Terrain terrain = flat().terrain();
        Terrain holey = (x, z) -> x == 3 && z == 3 ? null : terrain.surfaces(x, z);
        assertThrows(IllegalStateException.class, () -> ChunkComponents.of(holey, 0, 0, rules));
    }

    @Test
    void componentOfFindsTheComponentOfAnyCell() {
        Terrain terrain = river().terrain();
        assertEquals(new ComponentId(0, 0, 9, 65, 8), ChunkComponents.componentOf(terrain, 0, 0, 12, 65, 2, rules));
        assertNull(ChunkComponents.componentOf(terrain, 0, 0, 7, 65, 2, rules));
    }

    @Test
    void cellsOfListsEveryCell() {
        Terrain terrain = river().terrain();
        ComponentId west = ChunkComponents.of(terrain, 0, 0, rules).get(0).id();
        var cells = ChunkComponents.cellsOf(terrain, west, rules);
        assertEquals(7 * 16, cells.size());
        assertTrue(cells.contains(Cell.key(0, 65, 0)));
    }

    @Test
    void representativeIsDeterministicOnTies() {
        BlockWorld world = flat().set(8, 64, 8, BlockKind.OPEN);
        ComponentId id = ChunkComponents.of(world.terrain(), 0, 0, rules).get(0).id();
        // (7,8), (8,7), (8,9), (9,8) tie at distance 1; (7,8) has the lowest Cell.key
        assertEquals(new ComponentId(0, 0, 7, 65, 8), id);
    }

    @Test
    void floodStaysInsideTheChunkEvenWhenTheNeighbourChunkIsKnown() {
        BlockWorld world = new BlockWorld(0).fill(0, 64, 0, 31, 64, 15, BlockKind.SOLID);
        Terrain terrain = world.terrain();
        List<Component> components = ChunkComponents.of(terrain, 0, 0, rules);
        assertEquals(1, components.size());
        var cells = ChunkComponents.cellsOf(terrain, components.get(0).id(), rules);
        assertEquals(256, cells.size());
        assertEquals(16, components.get(0).borderCells().get(Side.EAST).length);
        for (int z = 0; z < 16; z++) {
            assertTrue(cells.contains(Cell.key(15, 65, z)));
            assertTrue(!cells.contains(Cell.key(16, 65, z)));
        }
    }

    @Test
    void everySideReportsItsBorderCells() {
        Component west = ChunkComponents.of(river().terrain(), 0, 0, rules).get(0);
        long[] expectedWest = new long[16];
        long[] expectedNorth = new long[7];
        for (int i = 0; i < 16; i++) {
            expectedWest[i] = Cell.key(0, 65, i);
        }
        for (int i = 0; i < 7; i++) {
            expectedNorth[i] = Cell.key(i, 65, 0);
        }
        java.util.Arrays.sort(expectedWest);
        java.util.Arrays.sort(expectedNorth);
        assertTrue(java.util.Arrays.equals(expectedWest, west.borderCells().get(Side.WEST)));
        assertTrue(java.util.Arrays.equals(expectedNorth, west.borderCells().get(Side.NORTH)));
        assertEquals(7, west.borderCells().get(Side.SOUTH).length);
        assertEquals(0, west.borderCells().get(Side.EAST).length);
    }

    @Test
    void negativeChunksWork() {
        BlockWorld world = new BlockWorld(0).fill(-16, 64, -16, -1, 64, -1, BlockKind.SOLID);
        Terrain terrain = world.terrain();
        List<Component> components = ChunkComponents.of(terrain, -1, -1, rules);
        assertEquals(1, components.size());
        ComponentId id = components.get(0).id();
        assertEquals(new ComponentId(-1, -1, -8, 65, -8), id);
        assertEquals(256, ChunkComponents.cellsOf(terrain, id, rules).size());
        assertEquals(16, components.get(0).borderCells().get(Side.SOUTH).length);
        assertEquals(id, ChunkComponents.componentOf(terrain, -1, -1, -16, 65, -1, rules));
    }
}
