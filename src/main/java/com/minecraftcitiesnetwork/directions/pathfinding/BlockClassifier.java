package com.minecraftcitiesnetwork.directions.pathfinding;

import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Maps a block's material name to {@link BlockFlags}.
 *
 * <p>The classifier works on names (the {@code Material} enum constant, or a namespaced id such as
 * {@code minecraft:oak_door}) so that it has no Bukkit dependency, can run in plain unit tests, and
 * serves both the live chunk snapshot decoder and the region-file reader with one set of rules.
 *
 * <p>Ordering matters: hazards first, then liquids, climbables and openables, then the explicit
 * passable table, then fence-like blocks that are too tall to step onto, then an optional runtime oracle (Paper's {@code Material#isSolid}) for anything
 * not named here, and finally a conservative {@code SOLID} default.
 */
public final class BlockClassifier {
    private static final Set<String> AIR = Set.of("AIR", "CAVE_AIR", "VOID_AIR", "LIGHT", "STRUCTURE_VOID");

    private static final Set<String> HAZARD = Set.of(
            "LAVA", "FIRE", "SOUL_FIRE", "CACTUS", "MAGMA_BLOCK", "SWEET_BERRY_BUSH", "POWDER_SNOW",
            "WITHER_ROSE", "CAMPFIRE", "SOUL_CAMPFIRE", "END_PORTAL", "NETHER_PORTAL", "END_GATEWAY"
    );

    private static final Set<String> LIQUID = Set.of(
            "WATER", "BUBBLE_COLUMN", "KELP", "KELP_PLANT", "SEAGRASS", "TALL_SEAGRASS"
    );

    private static final Set<String> CLIMBABLE = Set.of(
            "LADDER", "VINE", "SCAFFOLDING",
            "TWISTING_VINES", "TWISTING_VINES_PLANT", "WEEPING_VINES", "WEEPING_VINES_PLANT",
            "CAVE_VINES", "CAVE_VINES_PLANT"
    );

    /** Openable blocks that need redstone and therefore block a walker. */
    private static final Set<String> LOCKED_OPENABLE = Set.of("IRON_DOOR", "IRON_TRAPDOOR");

    private static final Set<String> PASSABLE = Set.of(
            "SHORT_GRASS", "GRASS", "TALL_GRASS", "FERN", "LARGE_FERN", "DEAD_BUSH", "BUSH", "FIREFLY_BUSH",
            "SHORT_DRY_GRASS", "TALL_DRY_GRASS", "LEAF_LITTER", "WILDFLOWERS", "PINK_PETALS",
            "DANDELION", "POPPY", "BLUE_ORCHID", "ALLIUM", "AZURE_BLUET", "OXEYE_DAISY", "CORNFLOWER",
            "LILY_OF_THE_VALLEY", "TORCHFLOWER", "SUNFLOWER", "LILAC", "ROSE_BUSH", "PEONY", "PITCHER_PLANT",
            "OPEN_EYEBLOSSOM", "CLOSED_EYEBLOSSOM", "CACTUS_FLOWER",
            "SNOW", "LEVER", "TRIPWIRE", "TRIPWIRE_HOOK", "STRING", "REDSTONE_WIRE", "REPEATER", "COMPARATOR",
            "COBWEB", "SUGAR_CANE", "BAMBOO_SAPLING", "NETHER_SPROUTS", "HANGING_ROOTS", "SPORE_BLOSSOM",
            "SMALL_DRIPLEAF", "WHEAT", "CARROTS", "POTATOES", "BEETROOTS", "MELON_STEM", "PUMPKIN_STEM",
            "ATTACHED_MELON_STEM", "ATTACHED_PUMPKIN_STEM", "NETHER_WART", "TORCHFLOWER_CROP", "PITCHER_CROP",
            "MOSS_CARPET", "PALE_MOSS_CARPET", "GLOW_LICHEN", "SCULK_VEIN", "FLOWER_POT",
            "RED_MUSHROOM", "BROWN_MUSHROOM", "CRIMSON_FUNGUS", "WARPED_FUNGUS", "CRIMSON_ROOTS", "WARPED_ROOTS",
            "PALE_HANGING_MOSS", "RAIL", "POWERED_RAIL", "DETECTOR_RAIL", "ACTIVATOR_RAIL", "TORCH"
    );

    private static final String[] PASSABLE_SUFFIXES = {
            "_CARPET", "_PRESSURE_PLATE", "_BUTTON", "_SIGN", "_BANNER", "_TORCH", "_SAPLING", "_PROPAGULE",
            "_TULIP", "_CORAL_FAN", "_CORAL", "_CANDLE"
    };

    private static final BlockClassifier OFFLINE = new BlockClassifier(null);

    private final Predicate<String> solidOracle;

    private BlockClassifier(Predicate<String> solidOracle) {
        this.solidOracle = solidOracle;
    }

    /** Table-only classifier. Anything unknown is treated as solid. */
    public static BlockClassifier offline() {
        return OFFLINE;
    }

    /**
     * Classifier that consults {@code isSolidByName} for materials the tables do not name. On a
     * running server this is Paper's {@code Material#isSolid}, which needs the block registry.
     */
    public static BlockClassifier withSolidOracle(Predicate<String> isSolidByName) {
        return new BlockClassifier(isSolidByName);
    }

    public byte classify(String materialName) {
        String name = normalize(materialName);
        if (AIR.contains(name)) {
            return BlockFlags.PASSABLE;
        }
        if (HAZARD.contains(name)) {
            return BlockFlags.HAZARD;
        }
        if (LIQUID.contains(name)) {
            return BlockFlags.LIQUID;
        }
        if (CLIMBABLE.contains(name)) {
            return BlockFlags.CLIMBABLE;
        }
        if (LOCKED_OPENABLE.contains(name)) {
            return BlockFlags.SOLID;
        }
        if (name.endsWith("_DOOR") || name.endsWith("_TRAPDOOR") || name.endsWith("_FENCE_GATE")) {
            return BlockFlags.OPENABLE;
        }
        if (name.endsWith("_CORAL_BLOCK") || name.endsWith("_MUSHROOM_BLOCK") || name.endsWith("_CANDLE_CAKE")) {
            return BlockFlags.SOLID;
        }
        if (PASSABLE.contains(name)) {
            return BlockFlags.PASSABLE;
        }
        for (String suffix : PASSABLE_SUFFIXES) {
            if (name.endsWith(suffix)) {
                return BlockFlags.PASSABLE;
            }
        }
        if (name.endsWith("_FENCE") || name.endsWith("_WALL") || name.endsWith("_PANE") || name.equals("IRON_BARS")) {
            return BlockFlags.TALL;
        }
        if (solidOracle != null) {
            return solidOracle.test(name) ? BlockFlags.SOLID : BlockFlags.PASSABLE;
        }
        return BlockFlags.SOLID;
    }

    static String normalize(String materialName) {
        String name = materialName;
        int colon = name.indexOf(':');
        if (colon >= 0) {
            name = name.substring(colon + 1);
        }
        return name.toUpperCase(Locale.ROOT);
    }
}
