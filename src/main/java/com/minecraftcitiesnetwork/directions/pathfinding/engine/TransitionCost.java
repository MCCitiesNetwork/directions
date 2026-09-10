package com.minecraftcitiesnetwork.directions.pathfinding.engine;

import com.minecraftcitiesnetwork.directions.pathfinding.BlockFlags;
import com.minecraftcitiesnetwork.directions.pathfinding.BlockGrid;
import com.minecraftcitiesnetwork.directions.pathfinding.WalkRules;
import com.minecraftcitiesnetwork.directions.pathfinding.WalkerProfile;
import de.bsommerfeld.pathetic.api.pathing.processing.Cost;
import de.bsommerfeld.pathetic.api.pathing.processing.CostProcessor;
import de.bsommerfeld.pathetic.api.pathing.processing.context.EvaluationContext;
import de.bsommerfeld.pathetic.api.wrapper.PathPosition;

/**
 * Extra cost on top of the engine's Euclidean step cost for moves that slow a player down: doors,
 * water, climbing, and jumping up a block. All multipliers are at least 1, so the heuristic stays
 * admissible and paths stay optimal.
 */
final class TransitionCost implements CostProcessor {
    /** Flat penalty for a one-block rise. Prefers flat routes when they are almost as short. */
    static final double STEP_UP_PENALTY = 0.5;

    @Override
    public Cost calculateCostContribution(EvaluationContext ctx) {
        GridEnvironment env = GridEnvironment.of(ctx.getEnvironmentContext());
        BlockGrid grid = env.grid();
        WalkerProfile p = env.profile();
        PathPosition cur = ctx.getCurrentPathPosition();
        PathPosition prev = ctx.getPreviousPathPosition();
        if (prev == null) {
            return Cost.ZERO;
        }

        int cx = cur.getFlooredX();
        int cy = cur.getFlooredY();
        int cz = cur.getFlooredZ();
        byte f = grid.flags(cx, cy, cz);
        double base = ctx.getBaseTransitionCost();
        int dy = cy - prev.getFlooredY();
        double extra = 0.0;

        if (BlockFlags.isLiquid(f)) {
            extra += base * (p.waterCost() - 1.0);
        } else if (BlockFlags.isOpenable(f)) {
            extra += base * (p.doorCost() - 1.0);
        } else if (BlockFlags.isClimbable(f) && dy != 0) {
            extra += base * (p.climbCost() - 1.0);
        }

        if (dy == 1 && !WalkRules.climbOrSwim(f) && !WalkRules.climbOrSwim(grid.flags(prev.getFlooredX(), prev.getFlooredY(), prev.getFlooredZ()))) {
            extra += STEP_UP_PENALTY;
        }
        return extra == 0.0 ? Cost.ZERO : Cost.of(extra);
    }
}
