package com.minecraftcitiesnetwork.directions.walk;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WalkRulesTest {
    private static final WalkRules RULES = new WalkRules(WalkerProfile.DEFAULT);
    private static final double SQRT2 = Math.sqrt(2);
    private static final double EPS = 1e-9;

    private record Move(int x, int y, int z, double cost) {
    }

    private static List<Move> moves(Terrain terrain, int x, int y, int z) {
        int surface = surfaceAt(terrain, x, y, z);
        List<Move> out = new ArrayList<>();
        RULES.neighbours(terrain, x, z, surface, (mx, my, mz, s, cost) -> out.add(new Move(mx, my, mz, cost)));
        return out;
    }

    private static int surfaceAt(Terrain terrain, int x, int y, int z) {
        for (int s : terrain.surfaces(x, z)) {
            if (Surfaces.y(s) == y) {
                return s;
            }
        }
        throw new AssertionError("no surface at " + x + "," + y + "," + z);
    }

    private static Optional<Move> to(List<Move> moves, int x, int y, int z) {
        return moves.stream().filter(m -> m.x == x && m.y == y && m.z == z).findFirst();
    }

    @Test
    void flatGroundReachesAllEightNeighbours() {
        Terrain t = BlockWorld.layers(64, "SSS\nSSS\nSSS").terrain();
        List<Move> m = moves(t, 1, 65, 1);
        assertEquals(8, m.size());
        assertEquals(1.0, to(m, 2, 65, 1).orElseThrow().cost, EPS);
        assertEquals(SQRT2, to(m, 2, 65, 2).orElseThrow().cost, EPS);
    }

    @Test
    void stepUpOneBlockCostsExtra() {
        Terrain t = BlockWorld.layers(64, "SSS", "..S").terrain();
        Move m = to(moves(t, 1, 65, 0), 2, 66, 0).orElseThrow();
        assertEquals(SQRT2 + WalkRules.STEP_UP_COST, m.cost, EPS);
    }

    @Test
    void noStepUpWithoutRoomToJump() {
        Terrain t = BlockWorld.layers(64, "SSS", "..S", "...", ".S.").terrain();
        assertTrue(to(moves(t, 1, 65, 0), 2, 66, 0).isEmpty());
    }

    @Test
    void slabsAndStairsNeedNoJump() {
        Terrain t = BlockWorld.layers(64, "SSS", "..H", "...", ".S.").terrain();
        Move m = to(moves(t, 1, 65, 0), 2, 66, 0).orElseThrow();
        assertEquals(SQRT2, m.cost, EPS);
    }

    @Test
    void dropsUpToTheLimit() {
        Terrain three = new BlockWorld(64).fill(0, 64, 0, 0, 67, 0, BlockKind.SOLID)
                .set(1, 64, 0, BlockKind.SOLID).terrain();
        assertTrue(to(moves(three, 0, 68, 0), 1, 65, 0).isPresent());

        Terrain four = new BlockWorld(64).fill(0, 64, 0, 0, 68, 0, BlockKind.SOLID)
                .set(1, 64, 0, BlockKind.SOLID).terrain();
        assertTrue(to(moves(four, 0, 69, 0), 1, 65, 0).isEmpty());
    }

    @Test
    void noDropUnderAnOverhang() {
        Terrain t = new BlockWorld(64).fill(0, 64, 0, 0, 67, 0, BlockKind.SOLID)
                .set(1, 64, 0, BlockKind.SOLID).set(1, 67, 0, BlockKind.SOLID).terrain();
        List<Move> m = moves(t, 0, 68, 0);
        assertTrue(to(m, 1, 65, 0).isEmpty());
        assertTrue(to(m, 1, 68, 0).isPresent());
    }

    @Test
    void noCornerCuttingPastAPillar() {
        Terrain t = new BlockWorld(64).fill(0, 64, 0, 1, 64, 1, BlockKind.SOLID)
                .fill(1, 65, 0, 1, 66, 0, BlockKind.SOLID).terrain();
        assertTrue(to(moves(t, 0, 65, 0), 1, 65, 1).isEmpty());
    }

    @Test
    void fencesBlock() {
        Terrain t = BlockWorld.layers(64, "SSS", ".F.").terrain();
        assertTrue(moves(t, 0, 65, 0).stream().noneMatch(m -> m.x == 1 && m.z == 0));
    }

    @Test
    void doorsArePassableButSlower() {
        Terrain t = BlockWorld.layers(64, "SSS", ".D.").terrain();
        assertEquals(WalkerProfile.DEFAULT.doorCost(), to(moves(t, 0, 65, 0), 1, 65, 0).orElseThrow().cost, EPS);
    }

    @Test
    void swimIntoAndClimbOutOfWater() {
        Terrain t = BlockWorld.layers(63, "SSS", "SWS").terrain();
        Move in = to(moves(t, 0, 65, 0), 1, 64, 0).orElseThrow();
        assertEquals(SQRT2 * WalkerProfile.DEFAULT.waterCost(), in.cost, EPS);
        Move out = to(moves(t, 1, 64, 0), 2, 65, 0).orElseThrow();
        assertEquals(SQRT2 + WalkRules.STEP_UP_COST, out.cost, EPS);
    }

    @Test
    void laddersClimbUpAndStepOffAtTheTop() {
        Terrain t = BlockWorld.layers(64, "SS", "LS", "LS", "LS").terrain();
        Move up = to(moves(t, 0, 65, 0), 0, 66, 0).orElseThrow();
        assertEquals(WalkerProfile.DEFAULT.climbCost(), up.cost, EPS);
        List<Move> fromTop = moves(t, 0, 67, 0);
        assertTrue(to(fromTop, 1, 68, 0).isPresent());
        assertTrue(to(fromTop, 0, 66, 0).isPresent());
        assertTrue(to(moves(t, 0, 65, 0), 1, 68, 0).isEmpty());
    }

    @Test
    void hazardsAreNeverEntered() {
        Terrain t = BlockWorld.layers(64, "SXS").terrain();
        assertTrue(moves(t, 0, 65, 0).stream().noneMatch(m -> m.x == 1));
    }

    @Test
    void unknownColumnsAreSkipped() {
        Terrain base = BlockWorld.layers(64, "SSS").terrain();
        Terrain clipped = (x, z) -> x >= 1 ? null : base.surfaces(x, z);
        assertTrue(moves(clipped, 0, 65, 0).isEmpty());
    }
}
