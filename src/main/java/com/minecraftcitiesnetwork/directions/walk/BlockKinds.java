package com.minecraftcitiesnetwork.directions.walk;

import java.util.Set;

/**
 * Maps a material name to what it means for a walking player. Works on names so it stays free of
 * Bukkit; the Bukkit layer memoises the result per material.
 */
public final class BlockKinds {
    private static final Set<String> HAZARDS = Set.of(
            "LAVA", "FIRE", "SOUL_FIRE", "CACTUS", "MAGMA_BLOCK", "SWEET_BERRY_BUSH",
            "POWDER_SNOW", "CAMPFIRE", "SOUL_CAMPFIRE", "WITHER_ROSE");
    private static final Set<String> WATERS = Set.of(
            "WATER", "BUBBLE_COLUMN", "KELP", "KELP_PLANT", "SEAGRASS", "TALL_SEAGRASS");
    private static final Set<String> CLIMBABLE = Set.of(
            "LADDER", "VINE", "SCAFFOLDING", "TWISTING_VINES", "TWISTING_VINES_PLANT",
            "WEEPING_VINES", "WEEPING_VINES_PLANT", "CAVE_VINES", "CAVE_VINES_PLANT");

    private BlockKinds() {
    }

    /**
     * @param materialName the material's enum name, e.g. {@code OAK_DOOR}
     * @param solid        whether the server reports the material as solid
     */
    public static BlockKind classify(String materialName, boolean solid) {
        String n = materialName;
        if (HAZARDS.contains(n)) {
            return BlockKind.HAZARD;
        }
        if (WATERS.contains(n)) {
            return BlockKind.WATER;
        }
        if (CLIMBABLE.contains(n)) {
            return BlockKind.CLIMB;
        }
        if (n.equals("IRON_DOOR") || n.equals("IRON_TRAPDOOR")) {
            return BlockKind.SOLID;
        }
        if (n.endsWith("_DOOR") || n.endsWith("_FENCE_GATE") || n.endsWith("_TRAPDOOR")) {
            return BlockKind.DOOR;
        }
        // "_WALL" never matches wall signs, torches, banners or heads: those end in _SIGN, _TORCH, ...
        if (n.endsWith("_FENCE") || n.endsWith("_WALL") || n.endsWith("_PANE") || n.equals("IRON_BARS")) {
            return BlockKind.FENCE;
        }
        if (n.endsWith("_SLAB") || n.endsWith("_STAIRS")) {
            return BlockKind.HALF;
        }
        if (n.endsWith("_CARPET") || n.equals("SNOW")) {
            return BlockKind.OPEN;
        }
        return solid ? BlockKind.SOLID : BlockKind.OPEN;
    }
}
