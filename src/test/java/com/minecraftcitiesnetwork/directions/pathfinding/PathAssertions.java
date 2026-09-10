package com.minecraftcitiesnetwork.directions.pathfinding;

import com.minecraftcitiesnetwork.directions.pathfinding.grid.ArrayBlockGrid;
import com.minecraftcitiesnetwork.directions.pathfinding.grid.AsciiGrid;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/** Invariants every returned path must satisfy, whatever the terrain. */
public final class PathAssertions {
    private PathAssertions() {
    }

    public static WalkPath assertFound(WalkResult result, BlockGrid grid, WalkerProfile profile) {
        if (!result.found()) {
            fail("expected FOUND but got " + result + describe(result, grid));
        }
        assertValid(result.path(), grid, profile);
        assertEquals(result.start(), result.path().start(), "path must begin at the snapped start");
        assertEquals(result.target(), result.path().end(), "path must end at the snapped target");
        return result.path();
    }

    public static void assertValid(WalkPath path, BlockGrid grid, WalkerProfile profile) {
        List<Pos> nodes = path.nodes();
        for (int i = 0; i < nodes.size(); i++) {
            Pos p = nodes.get(i);
            assertTrue(WalkRules.enterable(grid, profile, p.x(), p.y(), p.z()),
                    "node " + i + " " + p + " is not enterable (" + BlockFlags.name(grid.flags(p.x(), p.y(), p.z())) + ")");
            if (i > 0) {
                Pos q = nodes.get(i - 1);
                assertTrue(Math.abs(p.x() - q.x()) <= 1 && Math.abs(p.y() - q.y()) <= 1 && Math.abs(p.z() - q.z()) <= 1,
                        "nodes " + (i - 1) + " and " + i + " are not adjacent: " + q + " -> " + p);
                assertTrue(!p.equals(q), "duplicate consecutive node at " + i);
                if (p.y() > q.y()) {
                    assertTrue(WalkRules.supported(grid, profile, p.x(), p.y(), p.z()),
                            "rise into an unsupported cell at " + i + ": " + q + " -> " + p);
                }
            }
            boolean supported = WalkRules.supported(grid, profile, p.x(), p.y(), p.z());
            if (!supported) {
                assertTrue(WalkRules.landingDepth(grid, profile, p.x(), p.y(), p.z()) >= 0,
                        "node " + i + " " + p + " is in the air with no landing within max drop");
                if (i + 1 < nodes.size()) {
                    Pos next = nodes.get(i + 1);
                    assertTrue(next.x() == p.x() && next.z() == p.z() && next.y() == p.y() - 1,
                            "falling must continue straight down at " + i + ": " + p + " -> " + next);
                }
            }
        }
        assertTrue(WalkRules.supported(grid, profile, path.end().x(), path.end().y(), path.end().z()),
                "path must end on a supported cell");
    }

    public static void assertAvoids(WalkPath path, byte flag, BlockGrid grid) {
        for (Pos p : path.nodes()) {
            assertTrue(grid.flags(p.x(), p.y(), p.z()) != flag, "path enters a " + BlockFlags.name(flag) + " cell at " + p);
        }
    }

    public static void assertVisits(WalkPath path, byte flag, BlockGrid grid) {
        for (Pos p : path.nodes()) {
            if (grid.flags(p.x(), p.y(), p.z()) == flag) {
                return;
            }
        }
        fail("path never enters a " + BlockFlags.name(flag) + " cell");
    }

    private static String describe(WalkResult result, BlockGrid grid) {
        if (result.path() == null || !(grid instanceof ArrayBlockGrid array)) {
            return "";
        }
        return "\npartial path:\n" + AsciiGrid.render(array, result.path().start().y(), result.path().nodes());
    }
}
