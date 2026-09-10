package com.minecraftcitiesnetwork.directions.pathfinding.engine;

import com.minecraftcitiesnetwork.directions.pathfinding.BlockGrid;
import com.minecraftcitiesnetwork.directions.pathfinding.WalkRules;
import com.minecraftcitiesnetwork.directions.pathfinding.WalkerProfile;
import de.bsommerfeld.pathetic.api.pathing.processing.ValidationProcessor;
import de.bsommerfeld.pathetic.api.pathing.processing.context.EvaluationContext;
import de.bsommerfeld.pathetic.api.wrapper.PathPosition;

/**
 * Decides whether a single move is something a player can do.
 *
 * <ul>
 *   <li>The body must fit in the destination cell (feet plus headroom).</li>
 *   <li>Standing on ground, swimming, or holding a ladder counts as supported.</li>
 *   <li>A one-block rise needs footing to jump from and headroom above the jumper.</li>
 *   <li>Unsupported cells are only entered by falling: step off an edge at the same height, then
 *       straight down, and only when solid ground or water lies within the allowed drop measured
 *       from the edge.</li>
 *   <li>Diagonal moves may not squeeze between two blocks that touch only at a corner.</li>
 * </ul>
 *
 * <p>The start node is validated too: it must simply be a standing cell.
 */
final class WalkMoveValidator implements ValidationProcessor {

    @Override
    public boolean isValid(EvaluationContext ctx) {
        GridEnvironment env = GridEnvironment.of(ctx.getEnvironmentContext());
        BlockGrid grid = env.grid();
        WalkerProfile p = env.profile();

        PathPosition cur = ctx.getCurrentPathPosition();
        int cx = cur.getFlooredX();
        int cy = cur.getFlooredY();
        int cz = cur.getFlooredZ();

        if (!WalkRules.enterable(grid, p, cx, cy, cz)) {
            return false;
        }

        PathPosition prev = ctx.getPreviousPathPosition();
        boolean curSupported = WalkRules.supported(grid, p, cx, cy, cz);
        if (prev == null) {
            return curSupported;
        }

        int px = prev.getFlooredX();
        int py = prev.getFlooredY();
        int pz = prev.getFlooredZ();
        int dx = cx - px;
        int dy = cy - py;
        int dz = cz - pz;
        boolean vertical = dx == 0 && dz == 0;
        boolean prevSupported = WalkRules.supported(grid, p, px, py, pz);

        if (curSupported) {
            if (dy > 1) {
                return false;
            }
            if (!prevSupported && !(vertical && dy == -1)) {
                // Landing: a falling body arrives straight down, never sideways.
                return false;
            }
            if (dy == 1) {
                boolean climbing = WalkRules.climbOrSwim(grid.flags(cx, cy, cz)) || WalkRules.climbOrSwim(grid.flags(px, py, pz));
                if (!climbing) {
                    if (!p.stepUp() || !prevSupported) {
                        return false;
                    }
                    // Room above the head to jump.
                    if (!enterableCell(grid, p, px, py + p.height(), pz)) {
                        return false;
                    }
                } else if (!vertical && !prevSupported) {
                    return false;
                }
            }
        } else {
            // In the air: only falling is allowed.
            if (prevSupported) {
                // Stepping off an edge happens at the edge's own height, so the drop below this
                // cell is the whole drop and the limit check is exact.
                if (dy != 0) {
                    return false;
                }
            } else if (!(vertical && dy == -1)) {
                return false;
            }
            if (WalkRules.landingDepth(grid, p, cx, cy, cz) < 0) {
                return false;
            }
        }

        if (dx != 0 && dz != 0) {
            int y = dy > 0 ? cy : py;
            boolean a = WalkRules.enterable(grid, p, px + dx, y, pz);
            boolean b = WalkRules.enterable(grid, p, px, y, pz + dz);
            if (!a && !b) {
                return false;
            }
        }
        return true;
    }

    private static boolean enterableCell(BlockGrid grid, WalkerProfile p, int x, int y, int z) {
        return com.minecraftcitiesnetwork.directions.pathfinding.BlockFlags.canOccupy(grid.flags(x, y, z), p.canSwim());
    }
}
