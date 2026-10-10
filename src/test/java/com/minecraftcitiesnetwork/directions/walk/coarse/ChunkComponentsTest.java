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
}
