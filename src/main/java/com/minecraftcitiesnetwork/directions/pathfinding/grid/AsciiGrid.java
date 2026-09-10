package com.minecraftcitiesnetwork.directions.pathfinding.grid;

import com.minecraftcitiesnetwork.directions.pathfinding.BlockFlags;
import com.minecraftcitiesnetwork.directions.pathfinding.Pos;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Builds a grid from layered text, for readable test fixtures.
 *
 * <pre>
 * y=64
 * #########
 * #S.....T#
 * #########
 * </pre>
 *
 * <p>Each {@code y=N} line starts a layer; the rows that follow are z from top to bottom, columns are
 * x from left to right. Layers not listed between the lowest and highest are air. Below the lowest
 * layer lies solid ground. Above the highest layer, solid and unloaded columns continue upward for
 * two more cells so a drawn wall is a real wall, and everything else is open air. A fixture
 * therefore only has to draw the cells the walker moves through.
 *
 * <p>Symbols: {@code #} solid, {@code .} air, {@code ~} water, {@code D} door, {@code L} ladder,
 * {@code X} hazard, {@code ?} unloaded, {@code |} fence, {@code S} start (air), {@code T} target (air).
 */
public final class AsciiGrid {
    /** Cells added above the top drawn layer; solid columns extend through them. */
    static final int WALL_EXTENSION = 2;

    private final ArrayBlockGrid grid;
    private final Pos start;
    private final Pos target;

    private AsciiGrid(ArrayBlockGrid grid, Pos start, Pos target) {
        this.grid = grid;
        this.start = start;
        this.target = target;
    }

    public ArrayBlockGrid grid() {
        return grid;
    }

    public Pos start() {
        return Optional.ofNullable(start).orElseThrow(() -> new IllegalStateException("fixture has no S"));
    }

    public Pos target() {
        return Optional.ofNullable(target).orElseThrow(() -> new IllegalStateException("fixture has no T"));
    }

    public static AsciiGrid parse(String text) {
        TreeMap<Integer, List<String>> layers = new TreeMap<>();
        List<String> current = null;
        for (String raw : text.split("\n")) {
            String line = raw.strip();
            if (line.isEmpty()) {
                continue;
            }
            if (line.startsWith("y=")) {
                String num = line.substring(2).replace(":", "").strip();
                current = layers.computeIfAbsent(Integer.parseInt(num), k -> new ArrayList<>());
                continue;
            }
            if (current == null) {
                throw new IllegalArgumentException("row before any y= header: " + line);
            }
            current.add(line);
        }
        if (layers.isEmpty()) {
            throw new IllegalArgumentException("no layers");
        }
        int width = 0;
        int depth = 0;
        for (List<String> rows : layers.values()) {
            depth = Math.max(depth, rows.size());
            for (String row : rows) {
                width = Math.max(width, row.length());
            }
        }
        int minY = layers.firstKey();
        int maxY = layers.lastKey();
        ArrayBlockGrid grid = new ArrayBlockGrid(0, minY, 0, width, maxY - minY + 1 + WALL_EXTENSION, depth);
        Pos start = null;
        Pos target = null;
        for (var entry : layers.entrySet()) {
            int y = entry.getKey();
            List<String> rows = entry.getValue();
            for (int z = 0; z < rows.size(); z++) {
                String row = rows.get(z);
                for (int x = 0; x < row.length(); x++) {
                    char c = row.charAt(x);
                    byte f = switch (c) {
                        case '#' -> BlockFlags.SOLID;
                        case '.', 'S', 'T' -> BlockFlags.PASSABLE;
                        case '~' -> BlockFlags.LIQUID;
                        case 'D' -> BlockFlags.OPENABLE;
                        case 'L' -> BlockFlags.CLIMBABLE;
                        case 'X' -> BlockFlags.HAZARD;
                        case '?' -> BlockFlags.UNLOADED;
                        case '|' -> BlockFlags.TALL;
                        default -> throw new IllegalArgumentException("unknown symbol '" + c + "' at x=" + x + " z=" + z + " y=" + y);
                    };
                    grid.set(x, y, z, f);
                    if (c == 'S') {
                        start = new Pos(x, y, z);
                    } else if (c == 'T') {
                        target = new Pos(x, y, z);
                    }
                }
            }
        }
        for (int x = 0; x < width; x++) {
            for (int z = 0; z < depth; z++) {
                byte top = grid.flags(x, maxY, z);
                byte above = (top == BlockFlags.SOLID || top == BlockFlags.UNLOADED) ? top : BlockFlags.PASSABLE;
                for (int y = maxY + 1; y <= maxY + WALL_EXTENSION; y++) {
                    grid.set(x, y, z, above);
                }
            }
        }
        return new AsciiGrid(grid, start, target);
    }

    /** Renders one layer, marking path nodes with {@code o}. Handy in assertion messages. */
    public static String render(ArrayBlockGrid grid, int y, List<Pos> path) {
        StringBuilder sb = new StringBuilder();
        for (int z = grid.minZ(); z < grid.minZ() + grid.sizeZ(); z++) {
            for (int x = grid.minX(); x < grid.minX() + grid.sizeX(); x++) {
                boolean onPath = false;
                for (Pos p : path) {
                    if (p.x() == x && p.z() == z && p.y() == y) {
                        onPath = true;
                        break;
                    }
                }
                sb.append(onPath ? 'o' : BlockFlags.symbol(grid.flags(x, y, z)));
            }
            sb.append('\n');
        }
        return sb.toString();
    }
}
