package com.minecraftcitiesnetwork.directions.walk;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlockKindsTest {

    @ParameterizedTest
    @CsvSource({
            "AIR, false, OPEN",
            "STONE, true, SOLID",
            "OAK_LEAVES, true, SOLID",
            "POPPY, false, OPEN",
            "OAK_SIGN, false, OPEN",
            "OAK_WALL_SIGN, false, OPEN",
            "WALL_TORCH, false, OPEN",
            "STONE_PRESSURE_PLATE, false, OPEN",
            "RED_CARPET, true, OPEN",
            "SNOW, true, OPEN",
            "OAK_DOOR, true, DOOR",
            "IRON_DOOR, true, SOLID",
            "IRON_TRAPDOOR, true, SOLID",
            "OAK_FENCE_GATE, true, DOOR",
            "SPRUCE_TRAPDOOR, true, DOOR",
            "OAK_FENCE, true, FENCE",
            "COBBLESTONE_WALL, true, FENCE",
            "GLASS_PANE, true, FENCE",
            "RED_STAINED_GLASS_PANE, true, FENCE",
            "IRON_BARS, true, FENCE",
            "STONE_SLAB, true, HALF",
            "OAK_STAIRS, true, HALF",
            "WATER, false, WATER",
            "KELP_PLANT, false, WATER",
            "LADDER, false, CLIMB",
            "VINE, false, CLIMB",
            "SCAFFOLDING, true, CLIMB",
            "LAVA, false, HAZARD",
            "FIRE, false, HAZARD",
            "CACTUS, true, HAZARD",
            "MAGMA_BLOCK, true, HAZARD",
            "POWDER_SNOW, false, HAZARD",
            "CAMPFIRE, true, HAZARD"
    })
    void classifiesMaterials(String name, boolean solid, BlockKind expected) {
        assertEquals(expected, BlockKinds.classify(name, solid));
    }

    @Test
    void passableAndSupportingKinds() {
        assertTrue(BlockKind.OPEN.passable());
        assertTrue(BlockKind.DOOR.passable());
        assertTrue(BlockKind.CLIMB.passable());
        assertFalse(BlockKind.WATER.passable());
        assertFalse(BlockKind.FENCE.passable());
        assertFalse(BlockKind.HAZARD.passable());
        assertTrue(BlockKind.SOLID.supports());
        assertTrue(BlockKind.HALF.supports());
        assertFalse(BlockKind.FENCE.supports());
        assertFalse(BlockKind.HAZARD.supports());
        assertFalse(BlockKind.WATER.supports());
    }
}
