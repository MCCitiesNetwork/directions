package com.minecraftcitiesnetwork.directions.pathfinding.engine;

import com.minecraftcitiesnetwork.directions.pathfinding.BlockFlags;
import de.bsommerfeld.pathetic.api.pathing.context.EnvironmentContext;
import de.bsommerfeld.pathetic.api.provider.NavigationPoint;
import de.bsommerfeld.pathetic.api.provider.NavigationPointProvider;
import de.bsommerfeld.pathetic.api.wrapper.PathPosition;

/**
 * The engine's view of the world: can a single cell be occupied. The real movement rules live in
 * {@link WalkMoveValidator}; this only answers the engine's mandatory per-cell question without
 * allocating.
 */
final class GridNavigationProvider implements NavigationPointProvider {
    private static final NavigationPoint OPEN = () -> true;
    private static final NavigationPoint BLOCKED = () -> false;

    @Override
    public NavigationPoint getNavigationPoint(PathPosition position, EnvironmentContext context) {
        GridEnvironment env = GridEnvironment.of(context);
        byte f = env.grid().flags(position.getFlooredX(), position.getFlooredY(), position.getFlooredZ());
        return BlockFlags.canOccupy(f, env.profile().canSwim()) ? OPEN : BLOCKED;
    }
}
