package com.minecraftcitiesnetwork.directions.walk.coarse;

import com.minecraftcitiesnetwork.directions.walk.Cell;
import com.minecraftcitiesnetwork.directions.walk.Surfaces;
import com.minecraftcitiesnetwork.directions.walk.Terrain;
import com.minecraftcitiesnetwork.directions.walk.WalkRules;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;

/** Links the components of a chunk to those of the four chunks beside it. */
public final class ChunkEdges {
    private ChunkEdges() {
    }

    /**
     * The chunk's nodes with their edges, and the sides whose neighbour chunk was fully captured.
     * Only those sides can have edges.
     */
    public record Linked(List<CoarseNode> nodes, Set<Side> sides) {
    }

    public static Linked compute(Terrain terrain, WalkRules rules, int chunkX, int chunkZ,
                                 List<Component> components) {
        return compute(terrain, rules, chunkX, chunkZ, components,
                (x, z) -> ChunkComponents.of(terrain, x, z, rules));
    }

    /**
     * As {@link #compute(Terrain, WalkRules, int, int, List)}, taking a fully captured neighbour's
     * components from {@code neighbourComponents} (a {@link ComponentCache}, say) instead of
     * flooding the neighbour again.
     */
    public static Linked compute(Terrain terrain, WalkRules rules, int chunkX, int chunkZ,
                                 List<Component> components,
                                 BiFunction<Integer, Integer, List<Component>> neighbourComponents) {
        Map<ComponentId, Set<ComponentId>> edges = new HashMap<>();
        for (Component component : components) {
            edges.put(component.id(), new HashSet<>());
        }
        Set<Side> sides = EnumSet.noneOf(Side.class);
        for (Side side : Side.values()) {
            int neighbourX = chunkX + side.dx();
            int neighbourZ = chunkZ + side.dz();
            if (!isCaptured(terrain, neighbourX, neighbourZ)) {
                continue;
            }
            sides.add(side);
            Map<Long, ComponentId> facing = new HashMap<>();
            for (Component other : neighbourComponents.apply(neighbourX, neighbourZ)) {
                for (long key : other.borderCells().get(side.opposite())) {
                    facing.put(key, other.id());
                }
            }
            for (Component component : components) {
                for (long key : component.borderCells().get(side)) {
                    linkFrom(terrain, rules, key, facing, edges.get(component.id()));
                }
            }
        }
        List<CoarseNode> nodes = new ArrayList<>();
        for (Component component : components) {
            nodes.add(new CoarseNode(component.id(), component.minY(), component.maxY(),
                    List.copyOf(edges.get(component.id()))));
        }
        return new Linked(nodes, sides);
    }

    private static void linkFrom(Terrain terrain, WalkRules rules, long key, Map<Long, ComponentId> facing,
                                 Set<ComponentId> found) {
        Cell cell = Cell.fromKey(key);
        int x = cell.x();
        int z = cell.z();
        for (int surface : terrain.surfaces(x, z)) {
            if (Surfaces.y(surface) != cell.y()) {
                continue;
            }
            rules.neighbours(terrain, x, z, surface, (nx, ny, nz, nSurface, cost) -> {
                ComponentId target = facing.get(Cell.key(nx, ny, nz));
                if (target != null) {
                    found.add(target);
                }
            });
        }
    }

    private static boolean isCaptured(Terrain terrain, int chunkX, int chunkZ) {
        for (int x = chunkX * 16; x < chunkX * 16 + 16; x++) {
            for (int z = chunkZ * 16; z < chunkZ * 16 + 16; z++) {
                if (terrain.surfaces(x, z) == null) {
                    return false;
                }
            }
        }
        return true;
    }
}
