package com.minecraftcitiesnetwork.directions.walk.coarse;

import java.util.Map;

/**
 * A connected set of walkable cells within one chunk.
 *
 * @param borderCells cell keys of the component on each edge row of the chunk, sorted ascending;
 *                    a side with no cells maps to an empty array
 */
public record Component(ComponentId id, int minY, int maxY, Map<Side, long[]> borderCells) {
}
