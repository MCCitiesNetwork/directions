package com.minecraftcitiesnetwork.directions.pathfinding;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BlockClassifierTest {
    private final BlockClassifier c = BlockClassifier.offline();

    @Test
    void airAndDecorationsArePassable() {
        for (String n : new String[]{"AIR", "CAVE_AIR", "SHORT_GRASS", "TALL_GRASS", "POPPY", "WHITE_CARPET",
                "OAK_SIGN", "OAK_WALL_SIGN", "RED_BANNER", "TORCH", "WALL_TORCH", "STONE_PRESSURE_PLATE",
                "OAK_BUTTON", "RAIL", "SNOW", "OAK_SAPLING", "WHITE_CANDLE", "TUBE_CORAL", "TUBE_CORAL_FAN"}) {
            assertEquals(BlockFlags.PASSABLE, c.classify(n), n);
        }
    }

    @Test
    void openablesAreDoorsGatesAndTrapdoorsButNotIron() {
        assertEquals(BlockFlags.OPENABLE, c.classify("OAK_DOOR"));
        assertEquals(BlockFlags.OPENABLE, c.classify("SPRUCE_TRAPDOOR"));
        assertEquals(BlockFlags.OPENABLE, c.classify("BIRCH_FENCE_GATE"));
        assertEquals(BlockFlags.SOLID, c.classify("IRON_DOOR"));
        assertEquals(BlockFlags.SOLID, c.classify("IRON_TRAPDOOR"));
    }

    @Test
    void liquidsClimbablesHazards() {
        assertEquals(BlockFlags.LIQUID, c.classify("WATER"));
        assertEquals(BlockFlags.LIQUID, c.classify("KELP"));
        assertEquals(BlockFlags.CLIMBABLE, c.classify("LADDER"));
        assertEquals(BlockFlags.CLIMBABLE, c.classify("VINE"));
        assertEquals(BlockFlags.HAZARD, c.classify("LAVA"));
        assertEquals(BlockFlags.HAZARD, c.classify("CACTUS"));
        assertEquals(BlockFlags.HAZARD, c.classify("MAGMA_BLOCK"));
        assertEquals(BlockFlags.HAZARD, c.classify("POWDER_SNOW"));
    }

    @Test
    void fullBlocksAndBarriersAreSolid() {
        for (String n : new String[]{"STONE", "OAK_FENCE", "COBBLESTONE_WALL", "GLASS_PANE", "IRON_BARS",
                "OAK_SLAB", "OAK_STAIRS", "SNOW_BLOCK", "TUBE_CORAL_BLOCK", "RED_MUSHROOM_BLOCK", "CHEST", "LANTERN"}) {
            assertEquals(BlockFlags.SOLID, c.classify(n), n);
        }
    }

    @Test
    void acceptsNamespacedIdsAndLowerCase() {
        assertEquals(BlockFlags.OPENABLE, c.classify("minecraft:oak_door"));
        assertEquals(BlockFlags.LIQUID, c.classify("minecraft:water"));
        assertEquals(BlockFlags.SOLID, c.classify("minecraft:stone_bricks"));
    }

    @Test
    void runtimeOracleDecidesUnknownNames() {
        BlockClassifier withOracle = BlockClassifier.withSolidOracle(name -> name.equals("MYSTERY_BLOCK"));
        assertEquals(BlockFlags.SOLID, withOracle.classify("MYSTERY_BLOCK"));
        assertEquals(BlockFlags.PASSABLE, withOracle.classify("MYSTERY_DECORATION"));
        // Tables still win over the oracle.
        assertEquals(BlockFlags.OPENABLE, withOracle.classify("OAK_DOOR"));
    }
}
