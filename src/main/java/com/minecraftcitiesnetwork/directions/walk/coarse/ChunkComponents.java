package com.minecraftcitiesnetwork.directions.walk.coarse;

import com.minecraftcitiesnetwork.directions.walk.Cell;
import com.minecraftcitiesnetwork.directions.walk.Surfaces;
import com.minecraftcitiesnetwork.directions.walk.Terrain;
import com.minecraftcitiesnetwork.directions.walk.WalkRules;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Splits a chunk's walkable cells into connected components, flooding only inside the chunk. Two cells
 * are connected when a legal move exists from either one to the other: the flood runs over the
 * undirected move graph, so components partition the chunk's cells and no two share a representative.
 * The window search still walks the real, directed rules, so a coarse path through a one-way drop
 * that cannot be walked falls back.
 */
public final class ChunkComponents {
    private ChunkComponents() {
    }

    /** Components in ascending order of representative key; throws if a column of the chunk is unknown. */
    public static List<Component> of(Terrain terrain, int chunkX, int chunkZ, WalkRules rules) {
        Map<Long, List<Long>> moves = moves(terrain, chunkX, chunkZ, rules);
        List<Component> components = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        for (long start : moves.keySet()) {
            if (seen.contains(start)) {
                continue;
            }
            List<Cell> cells = flood(moves, start);
            for (Cell cell : cells) {
                seen.add(Cell.key(cell.x(), cell.y(), cell.z()));
            }
            components.add(describe(chunkX, chunkZ, cells));
        }
        components.sort(Comparator.comparingLong(c -> Cell.key(c.id().x(), c.id().y(), c.id().z())));
        return components;
    }

    /** The component holding the given cell, or null when it is not a surface cell of that chunk. */
    public static ComponentId componentOf(Terrain terrain, int chunkX, int chunkZ, int x, int y, int z,
                                          WalkRules rules) {
        Map<Long, List<Long>> moves = moves(terrain, chunkX, chunkZ, rules);
        long key = Cell.key(x, y, z);
        if (!moves.containsKey(key)) {
            return null;
        }
        return describe(chunkX, chunkZ, flood(moves, key)).id();
    }

    /** Every cell key of the component; empty when the id is not a surface cell of its chunk. */
    public static Set<Long> cellsOf(Terrain terrain, ComponentId id, WalkRules rules) {
        Map<Long, List<Long>> moves = moves(terrain, id.chunkX(), id.chunkZ(), rules);
        long start = Cell.key(id.x(), id.y(), id.z());
        Set<Long> keys = new HashSet<>();
        if (moves.containsKey(start)) {
            for (Cell cell : flood(moves, start)) {
                keys.add(Cell.key(cell.x(), cell.y(), cell.z()));
            }
        }
        return keys;
    }

    /**
     * Every surface cell of the chunk with the cells it is connected to by a legal move inside the
     * chunk, in either direction: each move is recorded both ways.
     */
    private static Map<Long, List<Long>> moves(Terrain terrain, int chunkX, int chunkZ, WalkRules rules) {
        Map<Long, Integer> surfaces = surfacesOf(terrain, chunkX, chunkZ);
        Map<Long, List<Long>> moves = new HashMap<>();
        for (long key : surfaces.keySet()) {
            moves.put(key, new ArrayList<>());
        }
        for (Map.Entry<Long, Integer> entry : surfaces.entrySet()) {
            long from = entry.getKey();
            Cell cell = Cell.fromKey(from);
            rules.neighbours(terrain, cell.x(), cell.z(), entry.getValue(), (nx, ny, nz, nSurface, cost) -> {
                if ((nx >> 4) != chunkX || (nz >> 4) != chunkZ) {
                    return;
                }
                long to = Cell.key(nx, ny, nz);
                List<Long> back = moves.get(to);
                if (back != null && to != from) {
                    moves.get(from).add(to);
                    back.add(from);
                }
            });
        }
        return moves;
    }

    private static Map<Long, Integer> surfacesOf(Terrain terrain, int chunkX, int chunkZ) {
        Map<Long, Integer> surfaces = new HashMap<>();
        for (int x = chunkX * 16; x < chunkX * 16 + 16; x++) {
            for (int z = chunkZ * 16; z < chunkZ * 16 + 16; z++) {
                int[] column = terrain.surfaces(x, z);
                if (column == null) {
                    throw new IllegalStateException("column (" + x + ", " + z + ") of chunk ("
                            + chunkX + ", " + chunkZ + ") is not captured");
                }
                for (int surface : column) {
                    surfaces.put(Cell.key(x, Surfaces.y(surface), z),
                            surface);
                }
            }
        }
        return surfaces;
    }

    private static List<Cell> flood(Map<Long, List<Long>> moves, long startKey) {
        List<Cell> found = new ArrayList<>();
        Set<Long> visited = new HashSet<>();
        ArrayDeque<Long> queue = new ArrayDeque<>();
        visited.add(startKey);
        queue.add(startKey);
        while (!queue.isEmpty()) {
            long key = queue.poll();
            found.add(Cell.fromKey(key));
            for (long next : moves.get(key)) {
                if (visited.add(next)) {
                    queue.add(next);
                }
            }
        }
        return found;
    }

    private static Component describe(int chunkX, int chunkZ, List<Cell> cells) {
        int centreX = chunkX * 16 + 8;
        int centreZ = chunkZ * 16 + 8;
        Cell best = null;
        long bestDistance = Long.MAX_VALUE;
        long bestKey = Long.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int maxY = Integer.MIN_VALUE;
        Map<Side, List<Long>> border = new EnumMap<>(Side.class);
        for (Side side : Side.values()) {
            border.put(side, new ArrayList<>());
        }
        for (Cell cell : cells) {
            long dx = cell.x() - centreX;
            long dz = cell.z() - centreZ;
            long distance = dx * dx + dz * dz;
            long key = Cell.key(cell.x(), cell.y(), cell.z());
            if (distance < bestDistance || (distance == bestDistance && key < bestKey)) {
                best = cell;
                bestDistance = distance;
                bestKey = key;
            }
            minY = Math.min(minY, cell.y());
            maxY = Math.max(maxY, cell.y());
            int localX = cell.x() & 15;
            int localZ = cell.z() & 15;
            if (localX == 0) {
                border.get(Side.WEST).add(key);
            }
            if (localX == 15) {
                border.get(Side.EAST).add(key);
            }
            if (localZ == 0) {
                border.get(Side.NORTH).add(key);
            }
            if (localZ == 15) {
                border.get(Side.SOUTH).add(key);
            }
        }
        Map<Side, long[]> borderCells = new EnumMap<>(Side.class);
        for (Side side : Side.values()) {
            borderCells.put(side, border.get(side).stream().mapToLong(Long::longValue).sorted().toArray());
        }
        ComponentId id = new ComponentId(chunkX, chunkZ, best.x(), best.y(), best.z());
        return new Component(id, minY, maxY, borderCells);
    }
}
