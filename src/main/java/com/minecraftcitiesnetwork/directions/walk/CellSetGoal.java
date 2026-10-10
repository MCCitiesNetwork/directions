package com.minecraftcitiesnetwork.directions.walk;

import java.util.Set;

/** An explicit set of cells, such as one chunk component the coarse planner leads to. */
public final class CellSetGoal implements Goal {
    private final Set<Long> cellKeys;
    private final BoxGoal box;

    /** @param cellKeys keys from {@link Cell#key}; at least one */
    public CellSetGoal(Set<Long> cellKeys) {
        if (cellKeys.isEmpty()) {
            throw new IllegalArgumentException("a cell set goal needs at least one cell");
        }
        this.cellKeys = Set.copyOf(cellKeys);
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (long key : this.cellKeys) {
            Cell cell = Cell.fromKey(key);
            minX = Math.min(minX, cell.x());
            minY = Math.min(minY, cell.y());
            minZ = Math.min(minZ, cell.z());
            maxX = Math.max(maxX, cell.x());
            maxY = Math.max(maxY, cell.y());
            maxZ = Math.max(maxZ, cell.z());
        }
        this.box = new BoxGoal(minX, minY, minZ, maxX, maxY, maxZ);
    }

    @Override
    public boolean contains(int x, int y, int z) {
        return cellKeys.contains(Cell.key(x, y, z));
    }

    /** The distance to the cells' bounding box, which never overestimates. */
    @Override
    public double estimate(int x, int y, int z) {
        return box.estimate(x, y, z);
    }

    @Override
    public boolean mayOverlap(Window window) {
        return box.mayOverlap(window);
    }

    @Override
    public Bounds bounds() {
        return box.bounds();
    }
}
