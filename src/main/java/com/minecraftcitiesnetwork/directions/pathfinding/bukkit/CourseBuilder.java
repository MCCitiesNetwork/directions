package com.minecraftcitiesnetwork.directions.pathfinding.bukkit;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Builds the synthetic test course in a void world named {@code directions_course}. Every feature
 * the walker rules care about appears once, at fixed coordinates the self-test scenarios reference.
 *
 * <pre>
 * Plate: stone at y=64 over x,z in [0, 79]. Walking level is y=65.
 * Canal: water at x in [30, 32], all z, except the bridge at z in [40, 42].
 * Wall: stone bricks at x=50, y in [65, 66], all z, with an oak door at z=20.
 * Platform: stone at y=65 over x,z in [60, 70] x [60, 70]  (one-block step up).
 * Cliff: stone at y in [65, 66] over x in [60, 70], z in [50, 54] (two blocks: not climbable).
 * Yard: oak fence ring at y=65 around x,z in [10, 20] x [60, 70], no gate.
 * Tower: stone column at (5, 65..67, 5) with a ladder on its east face at (6, 65..67, 5).
 * Lava: lava at y=64 over x in [70, 75], z in [10, 15].
 * Box: stone walls y in [65, 67] around x,z in [75, 79] x [75, 79]; the inside is unreachable.
 * </pre>
 */
public final class CourseBuilder {
    public static final String WORLD_NAME = "directions_course";

    private CourseBuilder() {
    }

    public static World build(JavaPlugin plugin) {
        World existing = Bukkit.getWorld(WORLD_NAME);
        if (existing != null) {
            return existing;
        }
        World world = new WorldCreator(WORLD_NAME)
                .environment(World.Environment.NORMAL)
                .generator(new VoidGenerator())
                .generateStructures(false)
                .createWorld();
        if (world == null) {
            throw new IllegalStateException("could not create " + WORLD_NAME);
        }
        world.setSpawnLocation(2, 65, 2);
        plugin.getLogger().info("SELFTEST building course in " + WORLD_NAME);
        // Keep the whole course loaded: nothing else holds these chunks in a headless server.
        for (int cx = -1; cx <= 5; cx++) {
            for (int cz = -1; cz <= 5; cz++) {
                world.addPluginChunkTicket(cx, cz, plugin);
            }
        }

        fill(world, 0, 64, 0, 79, 64, 79, Material.STONE);
        fill(world, 30, 64, 0, 32, 64, 79, Material.WATER);
        fill(world, 30, 64, 40, 32, 64, 42, Material.STONE);
        fill(world, 50, 65, 0, 50, 66, 79, Material.STONE_BRICKS);
        fill(world, 50, 65, 20, 50, 66, 20, Material.OAK_DOOR);
        fill(world, 60, 65, 60, 70, 65, 70, Material.STONE);
        fill(world, 60, 65, 50, 70, 66, 54, Material.STONE);
        ring(world, 10, 65, 60, 20, 70, Material.OAK_FENCE);
        fill(world, 5, 65, 5, 5, 67, 5, Material.STONE);
        fill(world, 6, 65, 5, 6, 67, 5, Material.LADDER);
        fill(world, 70, 64, 10, 75, 64, 15, Material.LAVA);
        ring(world, 75, 65, 75, 79, 79, Material.STONE);
        ring(world, 75, 66, 75, 79, 79, Material.STONE);
        ring(world, 75, 67, 75, 79, 79, Material.STONE);
        return world;
    }

    private static void fill(World w, int x0, int y0, int z0, int x1, int y1, int z1, Material m) {
        for (int x = x0; x <= x1; x++) {
            for (int y = y0; y <= y1; y++) {
                for (int z = z0; z <= z1; z++) {
                    w.getBlockAt(x, y, z).setType(m, false);
                }
            }
        }
    }

    private static void ring(World w, int x0, int y, int z0, int x1, int z1, Material m) {
        for (int x = x0; x <= x1; x++) {
            w.getBlockAt(x, y, z0).setType(m, false);
            w.getBlockAt(x, y, z1).setType(m, false);
        }
        for (int z = z0; z <= z1; z++) {
            w.getBlockAt(x0, y, z).setType(m, false);
            w.getBlockAt(x1, y, z).setType(m, false);
        }
    }

    /** Generates nothing; every block is set explicitly. */
    static final class VoidGenerator extends ChunkGenerator {
        @Override
        public boolean shouldGenerateNoise() {
            return false;
        }

        @Override
        public boolean shouldGenerateSurface() {
            return false;
        }

        @Override
        public boolean shouldGenerateCaves() {
            return false;
        }

        @Override
        public boolean shouldGenerateDecorations() {
            return false;
        }

        @Override
        public boolean shouldGenerateMobs() {
            return false;
        }

        @Override
        public boolean shouldGenerateStructures() {
            return false;
        }
    }
}
