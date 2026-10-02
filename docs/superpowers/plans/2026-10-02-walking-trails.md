# Walking Trails Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Draw a per-player particle trail along the real walking route for every walking leg of a trip (`/directions`, `/gps`, `/compass`), computed on arrival from live terrain, at any distance within one world.

**Architecture:** A pure-Java core (`walk` package) decodes loaded chunks into per-column walkable surfaces, runs a hand-written A\* inside a bounded window that either reaches the goal or ends at the best window-edge exit, and drives re-planning through a pure state machine (`GuidePolicy`). A thin Bukkit shell (`walk.bukkit`) captures chunk snapshots on the main thread a few per tick, runs searches on a two-thread pool, and renders the trail. Navigation learns which legs are walks and which are rides, and attaches a guide only to walk legs.

**Tech Stack:** Java 21, Paper API 1.21.8, WorldGuard 7.0.12 / WorldEdit 7.3.0 (compileOnly), Configurate 4.2.0, JUnit 5.11, Gradle 8.10.2 with Shadow, run-paper for the dev server.

**Spec:** [docs/superpowers/specs/2026-10-02-walking-trails-design.md](../specs/2026-10-02-walking-trails-design.md)

## Global Constraints

- Branch `feature/walking-trails`, cut from `main`. Nothing from `feature/pathetic` is reused.
- Every Gradle command runs on JDK 21: prefix with `JAVA_HOME=$(/usr/libexec/java_home -v 21)`. The machine default (JDK 25) fails with Gradle 8.10.2.
- No new runtime dependencies. No pathfinding library.
- Nothing directly in `com.minecraftcitiesnetwork.directions.walk` imports `org.bukkit`, `io.papermc` or `com.sk89q` (enforced by a test from Task 1).
- The feature never loads a chunk, never reads region files, never takes chunk tickets.
- Main thread only: WorldGuard reads, `Session`/`WalkGuide` state, chunk snapshots, particles, boss bar. Search threads: chunk decode and A\*.
- With `walking-paths.enabled: false` the plugin behaves exactly as `main`.
- Defaults (spec §9): window-radius 80, time-budget-ms 50, max-expansions 200000, snapshots-per-tick 6, capture-wait-ms 1500, cache-max-chunks 4096, max-drop 3, water-cost 2.5, climb-cost 2.0, door-cost 1.5, extend-distance 48, off-route-distance 6, replan-cooldown-ms 2000, trail-length 40, trail-refresh-ticks 10, pulse-step 4, arrow-look-ahead 6.
- Fixed constants: snap radius 3, spent-exit radius 8, teleport distance 64, retry after 10 s or 16 blocks, minimum 250 ms between any two searches for one player, step-up cost +0.5, recapture of a changed chunk at most once per second.
- Code style: match the existing codebase (braces on every `if`, `final` classes, records for values, `Locale.ROOT` in formatting).

## Review Focus

1. **Negative coordinates.** Half the world has x or z < 0. Chunk maths (`>> 4`, `& 15`) and start snapping (`Math.floor`) must be right there. Pinned by `WindowSearchTest.negativeCoordinatesWork` (Task 6) and `ColumnStoreTest.negativeCoordinatesMapToTheRightChunk` (Task 8).
2. **Fractional start heights.** Players stand on slabs (y = 65.5), swim (y = 64.3) and jump. The start must snap to the right surface, not fail. Pinned by `WindowSearchTest.startOnSlabSnapsOntoIt` and `WindowSearchTest.swimmingStartSnapsToWaterSurface` (Task 6).
3. **Destination that is itself a stop.** The route's last stop and the final waypoint are the same region. A duplicate leg would strand the player, because departure clearance never passes. Pinned by `LegPlannerTest.destinationThatIsTheLastStopAddsNoExtraLeg` (Task 9).
4. **Trail end must be where arrival triggers.** A stop goal that includes cells `ArrivalPolicy` doesn't accept (the corners of a buffer-expanded box) leaves the player at the end of the trail with nothing happening. Pinned by `GoalsTest.stopAreaExcludesCornersBeyondTheMargin` (Task 4).
5. **Reload or disable mid-navigation.** Guides hold a reference to the search service that a reload replaces. Pinned by the manual acceptance step "reload while following a trail" (Task 12, Step 9). This is Bukkit lifecycle code with no unit seam.

---

## File Structure

Created, pure core (`src/main/java/com/minecraftcitiesnetwork/directions/walk/`):

| File | Responsibility |
|---|---|
| `BlockKind.java` | What a block is to a walker (OPEN, SOLID, HALF, DOOR, WATER, CLIMB, FENCE, HAZARD) |
| `BlockKinds.java` | Material name → `BlockKind` |
| `Footing.java` | GROUND, WATER, CLIMB |
| `Surfaces.java` | Pack/unpack a walkable surface into an `int` |
| `ChunkBlocks.java` | Read-only source of block kinds for one chunk |
| `ColumnChunk.java` | 16×16 columns of packed surfaces |
| `ColumnDecoder.java` | `ChunkBlocks` → `ColumnChunk` |
| `Terrain.java` | `surfaces(x, z)` view used by rules and search |
| `WalkerProfile.java` | max drop and cost multipliers |
| `WalkRules.java` | Legal moves and their cost |
| `Window.java` | Square search area |
| `Goal.java`, `BoxGoal.java`, `StopAreaGoal.java`, `PointGoal.java`, `PolygonGoal.java` | Where a leg ends |
| `Cell.java` | Feet position + packed key |
| `Route.java` | Immutable path with distance helpers |
| `SearchRequest.java`, `SearchResult.java`, `WindowSearch.java` | Bounded A\* |
| `GuidePolicy.java` | Re-planning decisions (pure state machine) |
| `ColumnStore.java` | Thread-safe LRU store of decoded chunks, `Terrain` views |
| `WalkStats.java` | Counters and timings for the debug command |
| `WalkSettings.java`, `ParticleSpec.java` | Validated `walking-paths` config |

Created, Bukkit shell (`.../walk/bukkit/`): `MaterialKinds`, `SnapshotBlocks`, `ChunkColumnCache`, `ChunkChangeListener`, `WalkSearchService`, `WalkGoals`, `WalkGuide`, `ParticleStyle`, `TrailStyle`, `TrailRenderer`.

Created, navigation: `navigation/Leg.java`, `navigation/LegKind.java`, `navigation/LegPlanner.java`. Created, config: `config/WalkSettingsParser.java`.

Modified: `navigation/Session.java`, `navigation/NavigationService.java`, `command/DirectionsCommand.java`, `command/GpsCommand.java`, `command/CompassCommand.java`, `command/DirectionsBrigadierRegistrar.java`, `config/ConfigLoader.java`, `DirectionsPlugin.java`, `src/main/resources/{config.yml,lang.yml,paper-plugin.yml}`, `build.gradle.kts`, `.gitignore`, `README.md`, `src/test/.../config/LoadedDataDisplayNameTest.java`.

Test helpers (`src/test/java/com/minecraftcitiesnetwork/directions/walk/`): `Kinds.java`, `BlockWorld.java`.

---

### Task 1: Block kinds and the purity guard

**Files:**
- Create: `src/main/java/com/minecraftcitiesnetwork/directions/walk/BlockKind.java`
- Create: `src/main/java/com/minecraftcitiesnetwork/directions/walk/BlockKinds.java`
- Test: `src/test/java/com/minecraftcitiesnetwork/directions/walk/BlockKindsTest.java`
- Test: `src/test/java/com/minecraftcitiesnetwork/directions/walk/WalkPackagePurityTest.java`

**Interfaces:**
- Produces: `enum BlockKind { OPEN, SOLID, HALF, DOOR, WATER, CLIMB, FENCE, HAZARD; boolean passable(); boolean supports(); }`; `BlockKinds.classify(String materialName, boolean solid) → BlockKind`.

- [ ] **Step 1: Write the failing tests**

`BlockKindsTest.java`:

```java
package com.minecraftcitiesnetwork.directions.walk;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlockKindsTest {

    @ParameterizedTest
    @CsvSource({
            "AIR, false, OPEN",
            "STONE, true, SOLID",
            "OAK_LEAVES, true, SOLID",
            "POPPY, false, OPEN",
            "OAK_SIGN, false, OPEN",
            "OAK_WALL_SIGN, false, OPEN",
            "WALL_TORCH, false, OPEN",
            "STONE_PRESSURE_PLATE, false, OPEN",
            "RED_CARPET, true, OPEN",
            "SNOW, true, OPEN",
            "OAK_DOOR, true, DOOR",
            "IRON_DOOR, true, DOOR",
            "OAK_FENCE_GATE, true, DOOR",
            "SPRUCE_TRAPDOOR, true, DOOR",
            "OAK_FENCE, true, FENCE",
            "COBBLESTONE_WALL, true, FENCE",
            "GLASS_PANE, true, FENCE",
            "RED_STAINED_GLASS_PANE, true, FENCE",
            "IRON_BARS, true, FENCE",
            "STONE_SLAB, true, HALF",
            "OAK_STAIRS, true, HALF",
            "WATER, false, WATER",
            "KELP_PLANT, false, WATER",
            "LADDER, false, CLIMB",
            "VINE, false, CLIMB",
            "SCAFFOLDING, true, CLIMB",
            "LAVA, false, HAZARD",
            "FIRE, false, HAZARD",
            "CACTUS, true, HAZARD",
            "MAGMA_BLOCK, true, HAZARD",
            "POWDER_SNOW, false, HAZARD",
            "CAMPFIRE, true, HAZARD"
    })
    void classifiesMaterials(String name, boolean solid, BlockKind expected) {
        assertEquals(expected, BlockKinds.classify(name, solid));
    }

    @Test
    void passableAndSupportingKinds() {
        assertTrue(BlockKind.OPEN.passable());
        assertTrue(BlockKind.DOOR.passable());
        assertTrue(BlockKind.CLIMB.passable());
        assertFalse(BlockKind.WATER.passable());
        assertFalse(BlockKind.FENCE.passable());
        assertFalse(BlockKind.HAZARD.passable());
        assertTrue(BlockKind.SOLID.supports());
        assertTrue(BlockKind.HALF.supports());
        assertFalse(BlockKind.FENCE.supports());
        assertFalse(BlockKind.HAZARD.supports());
        assertFalse(BlockKind.WATER.supports());
    }
}
```

`WalkPackagePurityTest.java`:

```java
package com.minecraftcitiesnetwork.directions.walk;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** The walking core must stay testable without a server: no Bukkit, Paper or WorldGuard imports. */
class WalkPackagePurityTest {
    private static final List<String> FORBIDDEN = List.of("import org.bukkit", "import io.papermc", "import com.sk89q");

    @Test
    void corePackageHasNoServerImports() throws IOException {
        Path dir = Path.of("src/main/java/com/minecraftcitiesnetwork/directions/walk");
        try (Stream<Path> files = Files.list(dir)) {
            List<String> offenders = files
                    .filter(p -> p.toString().endsWith(".java"))
                    .filter(WalkPackagePurityTest::importsServerApi)
                    .map(p -> p.getFileName().toString())
                    .toList();
            assertTrue(offenders.isEmpty(), "server imports in walk/: " + offenders);
        }
    }

    private static boolean importsServerApi(Path file) {
        try {
            String source = Files.readString(file);
            return FORBIDDEN.stream().anyMatch(source::contains);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew test --tests '*BlockKindsTest' --tests '*WalkPackagePurityTest'`
Expected: compilation FAILS with "cannot find symbol: class BlockKinds" (the purity test cannot run until the package exists).

- [ ] **Step 3: Implement**

`BlockKind.java`:

```java
package com.minecraftcitiesnetwork.directions.walk;

/** What a block means to a walking player. */
public enum BlockKind {
    /** Nothing to collide with: air, flowers, carpets, signs, torches, rails, pressure plates. */
    OPEN,
    /** A full collision block that can be stood on. */
    SOLID,
    /** A low block (slab, stairs) that can be stood on and walked up without jumping. */
    HALF,
    /** Doors, fence gates and trapdoors: passable, but slower. */
    DOOR,
    /** Water: swimmable at its surface. */
    WATER,
    /** Ladders, vines and scaffolding: climbable. */
    CLIMB,
    /** Fences, walls, panes and bars: block the body and cannot be stood on. */
    FENCE,
    /** Lava, fire, cactus and similar: never entered or stood on. */
    HAZARD;

    /** A player's body can occupy this block. */
    public boolean passable() {
        return this == OPEN || this == DOOR || this == CLIMB;
    }

    /** A player can stand on top of this block. */
    public boolean supports() {
        return this == SOLID || this == HALF;
    }
}
```

`BlockKinds.java`:

```java
package com.minecraftcitiesnetwork.directions.walk;

import java.util.Set;

/**
 * Maps a material name to what it means for a walking player. Works on names so it stays free of
 * Bukkit; the Bukkit layer memoises the result per material.
 */
public final class BlockKinds {
    private static final Set<String> HAZARDS = Set.of(
            "LAVA", "FIRE", "SOUL_FIRE", "CACTUS", "MAGMA_BLOCK", "SWEET_BERRY_BUSH",
            "POWDER_SNOW", "CAMPFIRE", "SOUL_CAMPFIRE", "WITHER_ROSE");
    private static final Set<String> WATERS = Set.of(
            "WATER", "BUBBLE_COLUMN", "KELP", "KELP_PLANT", "SEAGRASS", "TALL_SEAGRASS");
    private static final Set<String> CLIMBABLE = Set.of(
            "LADDER", "VINE", "SCAFFOLDING", "TWISTING_VINES", "TWISTING_VINES_PLANT",
            "WEEPING_VINES", "WEEPING_VINES_PLANT", "CAVE_VINES", "CAVE_VINES_PLANT");

    private BlockKinds() {
    }

    /**
     * @param materialName the material's enum name, e.g. {@code OAK_DOOR}
     * @param solid        whether the server reports the material as solid
     */
    public static BlockKind classify(String materialName, boolean solid) {
        String n = materialName;
        if (HAZARDS.contains(n)) {
            return BlockKind.HAZARD;
        }
        if (WATERS.contains(n)) {
            return BlockKind.WATER;
        }
        if (CLIMBABLE.contains(n)) {
            return BlockKind.CLIMB;
        }
        if (n.endsWith("_DOOR") || n.endsWith("_FENCE_GATE") || n.endsWith("_TRAPDOOR")) {
            return BlockKind.DOOR;
        }
        // "_WALL" never matches wall signs, torches, banners or heads: those end in _SIGN, _TORCH, ...
        if (n.endsWith("_FENCE") || n.endsWith("_WALL") || n.endsWith("_PANE") || n.equals("IRON_BARS")) {
            return BlockKind.FENCE;
        }
        if (n.endsWith("_SLAB") || n.endsWith("_STAIRS")) {
            return BlockKind.HALF;
        }
        if (n.endsWith("_CARPET") || n.equals("SNOW")) {
            return BlockKind.OPEN;
        }
        return solid ? BlockKind.SOLID : BlockKind.OPEN;
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew test --tests '*BlockKindsTest' --tests '*WalkPackagePurityTest'`
Expected: PASS (34 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/minecraftcitiesnetwork/directions/walk src/test/java/com/minecraftcitiesnetwork/directions/walk
git commit -m "feat(walk): block kinds for the walker and a purity guard for the core package"
```

---

### Task 2: Column decoding

**Files:**
- Create: `src/main/java/com/minecraftcitiesnetwork/directions/walk/Footing.java`
- Create: `src/main/java/com/minecraftcitiesnetwork/directions/walk/Surfaces.java`
- Create: `src/main/java/com/minecraftcitiesnetwork/directions/walk/ChunkBlocks.java`
- Create: `src/main/java/com/minecraftcitiesnetwork/directions/walk/ColumnChunk.java`
- Create: `src/main/java/com/minecraftcitiesnetwork/directions/walk/ColumnDecoder.java`
- Create: `src/main/java/com/minecraftcitiesnetwork/directions/walk/Terrain.java`
- Create (test helpers): `src/test/java/com/minecraftcitiesnetwork/directions/walk/Kinds.java`, `src/test/java/com/minecraftcitiesnetwork/directions/walk/BlockWorld.java`
- Test: `src/test/java/com/minecraftcitiesnetwork/directions/walk/ColumnDecoderTest.java`

**Interfaces:**
- Consumes: `BlockKind` (Task 1).
- Produces:
  - `enum Footing { GROUND, WATER, CLIMB }`
  - `Surfaces.pack(int y, Footing footing, int headroom, boolean door, boolean halfSupport) → int`; `Surfaces.y(int)`, `footing(int)`, `headroom(int)`, `door(int)`, `halfSupport(int)`; `Surfaces.MAX_HEADROOM = 7`
  - `interface ChunkBlocks { int minY(); int maxY(); BlockKind kind(int localX, int y, int localZ); }` (maxY exclusive)
  - `final class ColumnChunk(int chunkX, int chunkZ, int[][] columns)` with `chunkX()`, `chunkZ()`, `column(int localX, int localZ) → int[]`
  - `ColumnDecoder.decode(int chunkX, int chunkZ, ChunkBlocks blocks) → ColumnChunk`
  - `@FunctionalInterface interface Terrain { int[] surfaces(int x, int z); }`: surfaces sorted by y, `int[0]` when none, `null` when unknown
  - Test helper `BlockWorld` (`new BlockWorld(minY)`, `layers(minY, String...)`, `set`, `fill`, `terrain()`) and `Kinds.of(char)` with codes `S` SOLID, `H` HALF, `.` OPEN, `D` DOOR, `W` WATER, `L` CLIMB, `F` FENCE, `X` HAZARD.

- [ ] **Step 1: Write the test helpers**

`Kinds.java`:

```java
package com.minecraftcitiesnetwork.directions.walk;

/** One-character block codes for test worlds. */
final class Kinds {
    private Kinds() {
    }

    static BlockKind of(char c) {
        return switch (c) {
            case 'S' -> BlockKind.SOLID;
            case 'H' -> BlockKind.HALF;
            case '.' -> BlockKind.OPEN;
            case 'D' -> BlockKind.DOOR;
            case 'W' -> BlockKind.WATER;
            case 'L' -> BlockKind.CLIMB;
            case 'F' -> BlockKind.FENCE;
            case 'X' -> BlockKind.HAZARD;
            default -> throw new IllegalArgumentException("unknown block code '" + c + "'");
        };
    }
}
```

`BlockWorld.java`:

```java
package com.minecraftcitiesnetwork.directions.walk;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** A sparse block world for tests: set block kinds, then decode it into a {@link Terrain}. */
final class BlockWorld {
    private final int minY;
    private final Map<Long, BlockKind> blocks = new HashMap<>();
    private final Set<Long> chunks = new HashSet<>();
    private int topY;

    BlockWorld(int minY) {
        this.minY = minY;
        this.topY = minY;
    }

    /**
     * Layers bottom-up, the first at {@code minY}. In each layer, rows are z (from 0) and characters
     * are x (from 0), using the codes in {@link Kinds}.
     */
    static BlockWorld layers(int minY, String... layers) {
        BlockWorld world = new BlockWorld(minY);
        for (int i = 0; i < layers.length; i++) {
            String[] rows = layers[i].strip().split("\n");
            for (int z = 0; z < rows.length; z++) {
                String row = rows[z].strip();
                for (int x = 0; x < row.length(); x++) {
                    world.set(x, minY + i, z, Kinds.of(row.charAt(x)));
                }
            }
        }
        return world;
    }

    /** A single column at x = 0, z = 0, written bottom-up from {@code minY}. */
    static BlockWorld column(int minY, String bottomUp) {
        BlockWorld world = new BlockWorld(minY);
        for (int i = 0; i < bottomUp.length(); i++) {
            world.set(0, minY + i, 0, Kinds.of(bottomUp.charAt(i)));
        }
        return world;
    }

    BlockWorld set(int x, int y, int z, BlockKind kind) {
        if (y < minY) {
            throw new IllegalArgumentException("y " + y + " is below minY " + minY);
        }
        blocks.put(key(x, y, z), kind);
        chunks.add(chunkKey(x >> 4, z >> 4));
        topY = Math.max(topY, y);
        return this;
    }

    BlockWorld fill(int x0, int y0, int z0, int x1, int y1, int z1, BlockKind kind) {
        for (int x = Math.min(x0, x1); x <= Math.max(x0, x1); x++) {
            for (int y = Math.min(y0, y1); y <= Math.max(y0, y1); y++) {
                for (int z = Math.min(z0, z1); z <= Math.max(z0, z1); z++) {
                    set(x, y, z, kind);
                }
            }
        }
        return this;
    }

    /** Decodes every chunk that has at least one block set; columns of other chunks are unknown. */
    Terrain terrain() {
        Map<Long, ColumnChunk> decoded = new HashMap<>();
        for (long ck : chunks) {
            int cx = (int) (ck >> 32);
            int cz = (int) ck;
            decoded.put(ck, ColumnDecoder.decode(cx, cz, blocksOf(cx, cz)));
        }
        return (x, z) -> {
            ColumnChunk chunk = decoded.get(chunkKey(x >> 4, z >> 4));
            return chunk == null ? null : chunk.column(x & 15, z & 15);
        };
    }

    private ChunkBlocks blocksOf(int cx, int cz) {
        int maxY = topY + 4;
        return new ChunkBlocks() {
            @Override
            public int minY() {
                return minY;
            }

            @Override
            public int maxY() {
                return maxY;
            }

            @Override
            public BlockKind kind(int localX, int y, int localZ) {
                return blocks.getOrDefault(key(cx * 16 + localX, y, cz * 16 + localZ), BlockKind.OPEN);
            }
        };
    }

    private static long key(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }

    private static long chunkKey(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
    }
}
```

- [ ] **Step 2: Write the failing decoder tests**

`ColumnDecoderTest.java`:

```java
package com.minecraftcitiesnetwork.directions.walk;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ColumnDecoderTest {

    private static int[] surfaces(int minY, String bottomUp) {
        return BlockWorld.column(minY, bottomUp).terrain().surfaces(0, 0);
    }

    private static int[] ys(int[] surfaces) {
        int[] out = new int[surfaces.length];
        for (int i = 0; i < surfaces.length; i++) {
            out[i] = Surfaces.y(surfaces[i]);
        }
        return out;
    }

    @Test
    void packRoundTripsNegativeHeightsAndCapsHeadroom() {
        int s = Surfaces.pack(-63, Footing.CLIMB, 12, true, true);
        assertEquals(-63, Surfaces.y(s));
        assertEquals(Footing.CLIMB, Surfaces.footing(s));
        assertEquals(Surfaces.MAX_HEADROOM, Surfaces.headroom(s));
        assertTrue(Surfaces.door(s));
        assertTrue(Surfaces.halfSupport(s));
    }

    @Test
    void groundOnAFullBlockWithOpenSky() {
        int[] s = surfaces(0, "S...");
        assertArrayEquals(new int[]{1}, ys(s));
        assertEquals(Footing.GROUND, Surfaces.footing(s[0]));
        assertEquals(Surfaces.MAX_HEADROOM, Surfaces.headroom(s[0]));
        assertFalse(Surfaces.door(s[0]));
        assertFalse(Surfaces.halfSupport(s[0]));
    }

    @Test
    void lowCeilingLimitsHeadroomAndTheRoofIsItsOwnSurface() {
        int[] s = surfaces(0, "S..S");
        assertArrayEquals(new int[]{1, 4}, ys(s));
        assertEquals(2, Surfaces.headroom(s[0]));
    }

    @Test
    void slabIsAHalfSupport() {
        int[] s = surfaces(0, "H..");
        assertArrayEquals(new int[]{1}, ys(s));
        assertTrue(Surfaces.halfSupport(s[0]));
    }

    @Test
    void doorwayIsMarked() {
        int[] s = surfaces(0, "SD.");
        assertArrayEquals(new int[]{1}, ys(s));
        assertTrue(Surfaces.door(s[0]));
    }

    @Test
    void fencesCanNeitherBeStoodOnNorWalkedThrough() {
        assertEquals(0, surfaces(0, "F...").length);
        assertEquals(0, surfaces(0, "SF..").length);
    }

    @Test
    void hazardsCanNeitherBeStoodOnNorEntered() {
        assertEquals(0, surfaces(0, "X...").length);
        assertEquals(0, surfaces(0, "SX..").length);
    }

    @Test
    void onlyTheTopOfAWaterColumnIsASurface() {
        int[] s = surfaces(0, "SWW.");
        assertArrayEquals(new int[]{2}, ys(s));
        assertEquals(Footing.WATER, Surfaces.footing(s[0]));
        assertTrue(Surfaces.headroom(s[0]) >= 3);
    }

    @Test
    void ladderIsGroundAtItsFootThenClimbable() {
        int[] s = surfaces(0, "SLLL..");
        assertArrayEquals(new int[]{1, 2, 3}, ys(s));
        assertEquals(Footing.GROUND, Surfaces.footing(s[0]));
        assertEquals(Footing.CLIMB, Surfaces.footing(s[1]));
        assertEquals(Footing.CLIMB, Surfaces.footing(s[2]));
    }

    @Test
    void basementAndRooftopInTheSameColumn() {
        int[] s = surfaces(0, "S..SSSSS..");
        assertArrayEquals(new int[]{1, 8}, ys(s));
    }

    @Test
    void worksBelowZero() {
        int[] s = surfaces(-64, "S..");
        assertArrayEquals(new int[]{-63}, ys(s));
    }

    @Test
    void columnsOfUndecodedChunksAreUnknown() {
        Terrain t = BlockWorld.column(0, "S..").terrain();
        assertEquals(null, t.surfaces(16, 0));
        assertEquals(0, t.surfaces(5, 5).length);
    }
}
```

- [ ] **Step 3: Run the tests to verify they fail**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew test --tests '*ColumnDecoderTest'`
Expected: compilation FAILS ("cannot find symbol: class Surfaces").

- [ ] **Step 4: Implement**

`Footing.java`:

```java
package com.minecraftcitiesnetwork.directions.walk;

/** How a player is held at a surface. */
public enum Footing {
    /** Standing on a block. */
    GROUND,
    /** Swimming at the top of a water column. */
    WATER,
    /** Holding on to a ladder, vine or scaffolding. */
    CLIMB
}
```

`Surfaces.java`:

```java
package com.minecraftcitiesnetwork.directions.walk;

/**
 * A walkable surface packed into one {@code int}: the feet height (low 16 bits, signed), the
 * footing, the free blocks above the feet (capped at {@link #MAX_HEADROOM}), whether a door is in
 * the way, and whether the block underneath is a slab or stair.
 */
public final class Surfaces {
    public static final int MAX_HEADROOM = 7;
    private static final int FOOTING_SHIFT = 16;
    private static final int HEADROOM_SHIFT = 18;
    private static final int DOOR_BIT = 1 << 21;
    private static final int HALF_BIT = 1 << 22;
    private static final Footing[] FOOTINGS = Footing.values();

    private Surfaces() {
    }

    public static int pack(int y, Footing footing, int headroom, boolean door, boolean halfSupport) {
        return (y & 0xFFFF)
                | (footing.ordinal() << FOOTING_SHIFT)
                | (Math.min(headroom, MAX_HEADROOM) << HEADROOM_SHIFT)
                | (door ? DOOR_BIT : 0)
                | (halfSupport ? HALF_BIT : 0);
    }

    public static int y(int surface) {
        return (short) (surface & 0xFFFF);
    }

    public static Footing footing(int surface) {
        return FOOTINGS[(surface >>> FOOTING_SHIFT) & 0x3];
    }

    public static int headroom(int surface) {
        return (surface >>> HEADROOM_SHIFT) & 0x7;
    }

    public static boolean door(int surface) {
        return (surface & DOOR_BIT) != 0;
    }

    public static boolean halfSupport(int surface) {
        return (surface & HALF_BIT) != 0;
    }
}
```

`ChunkBlocks.java`:

```java
package com.minecraftcitiesnetwork.directions.walk;

/** Read-only block kinds of one chunk, for {@link ColumnDecoder}. */
public interface ChunkBlocks {
    /** Lowest block height, inclusive. */
    int minY();

    /** Highest block height, exclusive. */
    int maxY();

    BlockKind kind(int localX, int y, int localZ);
}
```

`ColumnChunk.java`:

```java
package com.minecraftcitiesnetwork.directions.walk;

/** The walkable surfaces of every column in one chunk. Immutable once built. */
public final class ColumnChunk {
    private final int chunkX;
    private final int chunkZ;
    private final int[][] columns;

    /** @param columns 256 arrays indexed {@code localZ * 16 + localX}, each sorted by height */
    public ColumnChunk(int chunkX, int chunkZ, int[][] columns) {
        if (columns.length != 256) {
            throw new IllegalArgumentException("a chunk has 256 columns");
        }
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        this.columns = columns;
    }

    public int chunkX() {
        return chunkX;
    }

    public int chunkZ() {
        return chunkZ;
    }

    /** The column's packed surfaces. Callers must not modify the array. */
    public int[] column(int localX, int localZ) {
        return columns[localZ * 16 + localX];
    }
}
```

`Terrain.java`:

```java
package com.minecraftcitiesnetwork.directions.walk;

/** Read-only walkability of the world, column by column. */
@FunctionalInterface
public interface Terrain {
    /**
     * The walkable surfaces of column (x, z), sorted by height: an empty array when nothing there
     * is walkable, or {@code null} when the column is not known (not captured). Callers must not
     * modify the array.
     */
    int[] surfaces(int x, int z);
}
```

`ColumnDecoder.java`:

```java
package com.minecraftcitiesnetwork.directions.walk;

import java.util.Arrays;

/** Turns a chunk's blocks into the walkable surfaces of each column. */
public final class ColumnDecoder {
    private static final int[] NONE = new int[0];

    private ColumnDecoder() {
    }

    public static ColumnChunk decode(int chunkX, int chunkZ, ChunkBlocks blocks) {
        int minY = blocks.minY();
        int height = blocks.maxY() - minY;
        BlockKind[] column = new BlockKind[height];
        int[] buffer = new int[Math.max(height, 1)];
        int[][] columns = new int[256][];
        for (int lz = 0; lz < 16; lz++) {
            for (int lx = 0; lx < 16; lx++) {
                for (int i = 0; i < height; i++) {
                    column[i] = blocks.kind(lx, minY + i, lz);
                }
                columns[lz * 16 + lx] = surfaces(column, minY, buffer);
            }
        }
        return new ColumnChunk(chunkX, chunkZ, columns);
    }

    /** Index {@code i} of {@code k} is height {@code minY + i}; above the top is open sky. */
    static int[] surfaces(BlockKind[] k, int minY, int[] buffer) {
        int n = 0;
        for (int i = 1; i < k.length; i++) {
            BlockKind below = k[i - 1];
            BlockKind feet = k[i];
            BlockKind head = at(k, i + 1);
            Footing footing;
            if (below.supports() && feet.passable() && head.passable()) {
                footing = Footing.GROUND;
            } else if (feet == BlockKind.WATER && head.passable()) {
                footing = Footing.WATER;
            } else if (feet == BlockKind.CLIMB && head.passable()) {
                footing = Footing.CLIMB;
            } else {
                continue;
            }
            int headroom = 0;
            for (int j = i; headroom < Surfaces.MAX_HEADROOM; j++) {
                BlockKind kj = at(k, j);
                if (kj.passable() || (j == i && kj == BlockKind.WATER)) {
                    headroom++;
                } else {
                    break;
                }
            }
            boolean door = feet == BlockKind.DOOR || head == BlockKind.DOOR;
            boolean half = footing == Footing.GROUND && below == BlockKind.HALF;
            buffer[n++] = Surfaces.pack(minY + i, footing, headroom, door, half);
        }
        return n == 0 ? NONE : Arrays.copyOf(buffer, n);
    }

    private static BlockKind at(BlockKind[] k, int i) {
        return i < k.length ? k[i] : BlockKind.OPEN;
    }
}
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew test --tests '*ColumnDecoderTest' --tests '*WalkPackagePurityTest'`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/minecraftcitiesnetwork/directions/walk src/test/java/com/minecraftcitiesnetwork/directions/walk
git commit -m "feat(walk): decode chunks into per-column walkable surfaces"
```

---

### Task 3: Walk rules

**Files:**
- Create: `src/main/java/com/minecraftcitiesnetwork/directions/walk/WalkerProfile.java`
- Create: `src/main/java/com/minecraftcitiesnetwork/directions/walk/WalkRules.java`
- Test: `src/test/java/com/minecraftcitiesnetwork/directions/walk/WalkRulesTest.java`

**Interfaces:**
- Consumes: `Terrain`, `Surfaces`, `Footing` (Task 2); test helper `BlockWorld` (Task 2).
- Produces:
  - `record WalkerProfile(int maxDrop, double waterCost, double climbCost, double doorCost)` with `WalkerProfile.DEFAULT = (3, 2.5, 2.0, 1.5)`
  - `final class WalkRules(WalkerProfile)` with `profile()`, `STEP_UP_COST = 0.5`, `@FunctionalInterface interface Sink { void accept(int x, int y, int z, int surface, double cost); }` and `void neighbours(Terrain terrain, int x, int z, int surface, Sink sink)`.

Rules (spec §6.1). This is the conservative form of the diagonal rule: a diagonal move is allowed only between surfaces at the same height, and only when both side columns have a surface at that height.

- [ ] **Step 1: Write the failing tests**

`WalkRulesTest.java`:

```java
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
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew test --tests '*WalkRulesTest'`
Expected: compilation FAILS ("cannot find symbol: class WalkRules").

- [ ] **Step 3: Implement**

`WalkerProfile.java`:

```java
package com.minecraftcitiesnetwork.directions.walk;

/**
 * How far the walker may drop and how much slower water, climbing and doors are. Every multiplier
 * is at least 1 so straight-line distance stays an admissible heuristic.
 */
public record WalkerProfile(int maxDrop, double waterCost, double climbCost, double doorCost) {
    public static final WalkerProfile DEFAULT = new WalkerProfile(3, 2.5, 2.0, 1.5);

    public WalkerProfile {
        if (maxDrop < 0 || maxDrop > Surfaces.MAX_HEADROOM - 2) {
            throw new IllegalArgumentException("maxDrop must be between 0 and " + (Surfaces.MAX_HEADROOM - 2));
        }
        if (waterCost < 1 || climbCost < 1 || doorCost < 1) {
            throw new IllegalArgumentException("cost multipliers must be at least 1");
        }
    }
}
```

`WalkRules.java`:

```java
package com.minecraftcitiesnetwork.directions.walk;

/** Which surfaces a player can reach from a surface in one move, and what each move costs. */
public final class WalkRules {
    public static final double STEP_UP_COST = 0.5;
    private static final double SQRT2 = Math.sqrt(2);
    private static final int[][] DIRECTIONS = {
            {1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}
    };

    @FunctionalInterface
    public interface Sink {
        void accept(int x, int y, int z, int surface, double cost);
    }

    private final WalkerProfile profile;

    public WalkRules(WalkerProfile profile) {
        this.profile = profile;
    }

    public WalkerProfile profile() {
        return profile;
    }

    public void neighbours(Terrain terrain, int x, int z, int surface, Sink sink) {
        int y = Surfaces.y(surface);
        Footing from = Surfaces.footing(surface);
        int[] own = terrain.surfaces(x, z);
        if (own != null) {
            for (int other : own) {
                int oy = Surfaces.y(other);
                if (oy == y + 1 && Surfaces.footing(other) == Footing.CLIMB) {
                    sink.accept(x, oy, z, other, profile.climbCost());
                } else if (oy == y - 1 && from == Footing.CLIMB) {
                    sink.accept(x, oy, z, other, profile.climbCost());
                }
            }
        }
        for (int[] d : DIRECTIONS) {
            int nx = x + d[0];
            int nz = z + d[1];
            int[] column = terrain.surfaces(nx, nz);
            if (column == null) {
                continue;
            }
            boolean diagonal = d[0] != 0 && d[1] != 0;
            if (diagonal && (!hasSurfaceAt(terrain, x + d[0], z, y) || !hasSurfaceAt(terrain, x, z + d[1], y))) {
                continue;
            }
            for (int target : column) {
                int dy = Surfaces.y(target) - y;
                boolean allowed = diagonal ? dy == 0 : canStep(surface, from, target, dy);
                if (allowed) {
                    sink.accept(nx, Surfaces.y(target), nz, target, cost(diagonal, dy, target));
                }
            }
        }
    }

    private boolean canStep(int from, Footing fromFooting, int to, int dy) {
        if (dy == 0) {
            return true;
        }
        if (dy == 1) {
            return fromFooting == Footing.CLIMB || Surfaces.halfSupport(to) || Surfaces.headroom(from) >= 3;
        }
        if (dy < 0) {
            // The body moves sideways at the original height, then falls: the target column must be
            // open from the landing spot up to the original head height.
            return -dy <= profile.maxDrop() && Surfaces.headroom(to) >= 2 - dy;
        }
        return false;
    }

    private double cost(boolean diagonal, int dy, int target) {
        double horizontal = diagonal ? SQRT2 : 1.0;
        double cost = Math.sqrt(horizontal * horizontal + dy * dy);
        double multiplier = 1.0;
        if (Surfaces.footing(target) == Footing.WATER) {
            multiplier = Math.max(multiplier, profile.waterCost());
        }
        if (Surfaces.door(target)) {
            multiplier = Math.max(multiplier, profile.doorCost());
        }
        cost *= multiplier;
        if (dy == 1 && !Surfaces.halfSupport(target)) {
            cost += STEP_UP_COST;
        }
        return cost;
    }

    private static boolean hasSurfaceAt(Terrain terrain, int x, int z, int y) {
        int[] column = terrain.surfaces(x, z);
        if (column == null) {
            return false;
        }
        for (int s : column) {
            if (Surfaces.y(s) == y) {
                return true;
            }
        }
        return false;
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew test --tests '*WalkRulesTest'`
Expected: PASS (13 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/minecraftcitiesnetwork/directions/walk src/test/java/com/minecraftcitiesnetwork/directions/walk
git commit -m "feat(walk): movement rules and costs for the walker"
```

---

### Task 4: Window and goals

**Files:**
- Create: `src/main/java/com/minecraftcitiesnetwork/directions/walk/Window.java`
- Create: `src/main/java/com/minecraftcitiesnetwork/directions/walk/Goal.java`
- Create: `src/main/java/com/minecraftcitiesnetwork/directions/walk/BoxGoal.java`
- Create: `src/main/java/com/minecraftcitiesnetwork/directions/walk/StopAreaGoal.java`
- Create: `src/main/java/com/minecraftcitiesnetwork/directions/walk/PointGoal.java`
- Create: `src/main/java/com/minecraftcitiesnetwork/directions/walk/PolygonGoal.java`
- Test: `src/test/java/com/minecraftcitiesnetwork/directions/walk/GoalsTest.java`

**Interfaces:**
- Produces:
  - `record Window(int minX, int minZ, int maxX, int maxZ)` with `static around(int x, int z, int radius)`, `contains(int x, int z)`, `onBorder(int x, int z)` (all inclusive)
  - `interface Goal { boolean contains(int x, int y, int z); double estimate(int x, int y, int z); boolean mayOverlap(Window window); }`. `estimate` is a lower bound on the walking cost to the nearest goal cell. `contains`/`estimate` take feet cells. They must be safe to call from any thread.
  - `record BoxGoal(int minX, int minY, int minZ, int maxX, int maxY, int maxZ)`, inclusive. Has `static int gap(int v, int min, int max)`.
  - `record StopAreaGoal(int minX, int minZ, int maxX, int maxZ, double margin)`: the region's blocks, plus anywhere whose horizontal distance to the region's bounding box is within `margin`. This is exactly `ArrivalPolicy`'s test for configured stops; y is ignored.
  - `record PointGoal(double x, double y, double z, double radius, boolean includeY)`: matches `Waypoint.Coordinates.isWithinRadius`.
  - `final class PolygonGoal(int[] xs, int[] zs, int minY, int maxY)`: a WorldGuard polygonal region, edges inclusive.

- [ ] **Step 1: Write the failing tests**

`GoalsTest.java`:

```java
package com.minecraftcitiesnetwork.directions.walk;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GoalsTest {
    private static final double EPS = 1e-9;

    @Test
    void windowAroundContainsAndBorders() {
        Window w = Window.around(10, -10, 5);
        assertEquals(new Window(5, -15, 15, -5), w);
        assertTrue(w.contains(5, -15));
        assertFalse(w.contains(16, -10));
        assertTrue(w.onBorder(15, -10));
        assertFalse(w.onBorder(10, -10));
    }

    @Test
    void boxContainsAndEstimates() {
        BoxGoal box = new BoxGoal(10, 60, 0, 20, 70, 5);
        assertTrue(box.contains(10, 60, 5));
        assertFalse(box.contains(9, 65, 0));
        assertEquals(0.0, box.estimate(15, 65, 3), EPS);
        assertEquals(5.0, box.estimate(5, 65, 0), EPS);
        assertEquals(5.0, box.estimate(23, 74, 2), EPS);
        assertTrue(box.mayOverlap(new Window(0, 0, 10, 10)));
        assertFalse(box.mayOverlap(new Window(0, 0, 9, 9)));
    }

    @Test
    void stopAreaMatchesTheArrivalBuffer() {
        StopAreaGoal stop = new StopAreaGoal(100, 100, 110, 110, 5);
        assertTrue(stop.contains(110, 0, 110));
        assertTrue(stop.contains(114, 64, 105));
        assertFalse(stop.contains(116, 64, 105));
        assertEquals(0.0, stop.estimate(112, 64, 105), EPS);
    }

    @Test
    void stopAreaExcludesCornersBeyondTheMargin() {
        StopAreaGoal stop = new StopAreaGoal(100, 100, 110, 110, 5);
        // 4.5 blocks out on both axes is 6.4 blocks from the corner: outside the arrival buffer.
        assertFalse(stop.contains(114, 64, 114));
        assertTrue(stop.contains(113, 64, 113));
    }

    @Test
    void stopAreaWithoutMarginIsTheRegionsBlocks() {
        StopAreaGoal stop = new StopAreaGoal(100, 100, 110, 110, 0);
        assertTrue(stop.contains(110, 64, 110));
        assertFalse(stop.contains(111, 64, 110));
    }

    @Test
    void pointGoalHorizontalAndThreeDimensional() {
        PointGoal flat = new PointGoal(100.0, 999.0, 200.0, 5.0, false);
        assertTrue(flat.contains(103, 64, 200));
        assertFalse(flat.contains(106, 64, 200));
        assertEquals(Math.sqrt(30.5) - 5.0, flat.estimate(105, 64, 199), EPS);

        PointGoal withY = new PointGoal(100.0, 70.0, 200.0, 5.0, true);
        assertFalse(withY.contains(99, 64, 199));
        assertTrue(withY.contains(99, 67, 199));
        assertTrue(withY.mayOverlap(new Window(104, 195, 120, 205)));
        assertFalse(withY.mayOverlap(new Window(106, 195, 120, 205)));
    }

    @Test
    void polygonContainsInsideAndEdgesButNotTheNotch() {
        // L-shape: (0,0) (10,0) (10,4) (4,4) (4,10) (0,10)
        PolygonGoal l = new PolygonGoal(new int[]{0, 10, 10, 4, 4, 0}, new int[]{0, 0, 4, 4, 10, 10}, 60, 70);
        assertTrue(l.contains(2, 65, 2));
        assertTrue(l.contains(8, 65, 2));
        assertTrue(l.contains(2, 65, 8));
        assertTrue(l.contains(10, 65, 0));
        assertTrue(l.contains(4, 65, 7));
        assertFalse(l.contains(8, 65, 8));
        assertFalse(l.contains(2, 71, 2));
        assertEquals(0.0, l.estimate(8, 65, 8), EPS);
        assertEquals(5.0, l.estimate(15, 65, 0), EPS);
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew test --tests '*GoalsTest'`
Expected: compilation FAILS ("cannot find symbol: class Window").

- [ ] **Step 3: Implement**

`Window.java`:

```java
package com.minecraftcitiesnetwork.directions.walk;

/** A square of columns a search may use, bounds inclusive. */
public record Window(int minX, int minZ, int maxX, int maxZ) {
    public static Window around(int x, int z, int radius) {
        return new Window(x - radius, z - radius, x + radius, z + radius);
    }

    public boolean contains(int x, int z) {
        return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
    }

    public boolean onBorder(int x, int z) {
        return x == minX || x == maxX || z == minZ || z == maxZ;
    }
}
```

`Goal.java`:

```java
package com.minecraftcitiesnetwork.directions.walk;

/** Where a walking leg ends. Implementations are immutable and thread-safe. */
public interface Goal {
    /** Whether a player whose feet are in this cell has arrived. */
    boolean contains(int x, int y, int z);

    /** A lower bound on the walking cost from this cell to the nearest goal cell. */
    double estimate(int x, int y, int z);

    /** False only when no goal cell can lie inside the window. */
    boolean mayOverlap(Window window);
}
```

`BoxGoal.java`:

```java
package com.minecraftcitiesnetwork.directions.walk;

/** A cuboid of cells, bounds inclusive: a WorldGuard cuboid region. */
public record BoxGoal(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) implements Goal {
    @Override
    public boolean contains(int x, int y, int z) {
        return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
    }

    @Override
    public double estimate(int x, int y, int z) {
        int dx = gap(x, minX, maxX);
        int dy = gap(y, minY, maxY);
        int dz = gap(z, minZ, maxZ);
        return Math.sqrt((double) dx * dx + (double) dy * dy + (double) dz * dz);
    }

    @Override
    public boolean mayOverlap(Window w) {
        return maxX >= w.minX() && minX <= w.maxX() && maxZ >= w.minZ() && minZ <= w.maxZ();
    }

    /** Distance from {@code v} to the range [min, max]; 0 inside. */
    static int gap(int v, int min, int max) {
        if (v < min) {
            return min - v;
        }
        return v > max ? v - max : 0;
    }
}
```

`StopAreaGoal.java`:

```java
package com.minecraftcitiesnetwork.directions.walk;

/**
 * A configured stop as navigation sees it: inside the region's footprint, or within {@code margin}
 * blocks of its bounding box horizontally, at any height. This mirrors {@code ArrivalPolicy}, so
 * the trail ends exactly where the stop counts as reached.
 */
public record StopAreaGoal(int minX, int minZ, int maxX, int maxZ, double margin) implements Goal {
    @Override
    public boolean contains(int x, int y, int z) {
        if (x >= minX && x <= maxX && z >= minZ && z <= maxZ) {
            return true;
        }
        double dx = axis(x + 0.5, minX, maxX);
        double dz = axis(z + 0.5, minZ, maxZ);
        return dx * dx + dz * dz <= margin * margin;
    }

    @Override
    public double estimate(int x, int y, int z) {
        double d = Math.hypot(BoxGoal.gap(x, minX, maxX), BoxGoal.gap(z, minZ, maxZ));
        return Math.max(0.0, d - margin - 1.0);
    }

    @Override
    public boolean mayOverlap(Window w) {
        int m = (int) Math.ceil(margin) + 1;
        return maxX + m >= w.minX() && minX - m <= w.maxX() && maxZ + m >= w.minZ() && minZ - m <= w.maxZ();
    }

    private static double axis(double v, double min, double max) {
        if (v < min) {
            return min - v;
        }
        return v > max ? v - max : 0.0;
    }
}
```

`PointGoal.java`:

```java
package com.minecraftcitiesnetwork.directions.walk;

/**
 * Within {@code radius} of a coordinate, measured from the cell's centre, ignoring height unless
 * {@code includeY}. Mirrors {@code Waypoint.Coordinates.isWithinRadius}.
 */
public record PointGoal(double x, double y, double z, double radius, boolean includeY) implements Goal {
    @Override
    public boolean contains(int cx, int cy, int cz) {
        double d2 = distanceSquared(cx, cy, cz);
        return radius <= 0 ? d2 <= 0.0001 : d2 <= radius * radius;
    }

    @Override
    public double estimate(int cx, int cy, int cz) {
        return Math.max(0.0, Math.sqrt(distanceSquared(cx, cy, cz)) - radius);
    }

    @Override
    public boolean mayOverlap(Window w) {
        return Math.floor(x + radius) >= w.minX() && Math.floor(x - radius) <= w.maxX()
                && Math.floor(z + radius) >= w.minZ() && Math.floor(z - radius) <= w.maxZ();
    }

    private double distanceSquared(int cx, int cy, int cz) {
        double dx = cx + 0.5 - x;
        double dz = cz + 0.5 - z;
        double dy = includeY ? cy - y : 0.0;
        return dx * dx + dy * dy + dz * dz;
    }
}
```

`PolygonGoal.java`:

```java
package com.minecraftcitiesnetwork.directions.walk;

/**
 * A polygonal region: inside the polygon (edges inclusive) between {@code minY} and {@code maxY}
 * inclusive. The estimate is the distance to the polygon's bounding box, which never overestimates.
 */
public final class PolygonGoal implements Goal {
    private final int[] xs;
    private final int[] zs;
    private final BoxGoal bounds;

    public PolygonGoal(int[] xs, int[] zs, int minY, int maxY) {
        if (xs.length != zs.length || xs.length < 3) {
            throw new IllegalArgumentException("a polygon needs at least three points");
        }
        this.xs = xs.clone();
        this.zs = zs.clone();
        int minX = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (int i = 0; i < xs.length; i++) {
            minX = Math.min(minX, xs[i]);
            maxX = Math.max(maxX, xs[i]);
            minZ = Math.min(minZ, zs[i]);
            maxZ = Math.max(maxZ, zs[i]);
        }
        this.bounds = new BoxGoal(minX, minY, minZ, maxX, maxY, maxZ);
    }

    @Override
    public boolean contains(int x, int y, int z) {
        return bounds.contains(x, y, z) && inside(x, z);
    }

    @Override
    public double estimate(int x, int y, int z) {
        return bounds.estimate(x, y, z);
    }

    @Override
    public boolean mayOverlap(Window window) {
        return bounds.mayOverlap(window);
    }

    private boolean inside(int px, int pz) {
        boolean in = false;
        for (int i = 0, j = xs.length - 1; i < xs.length; j = i++) {
            int xi = xs[i];
            int zi = zs[i];
            int xj = xs[j];
            int zj = zs[j];
            if (onSegment(xi, zi, xj, zj, px, pz)) {
                return true;
            }
            if ((zi > pz) != (zj > pz)) {
                double crossX = xi + (double) (pz - zi) * (xj - xi) / (zj - zi);
                if (px < crossX) {
                    in = !in;
                }
            }
        }
        return in;
    }

    private static boolean onSegment(int ax, int az, int bx, int bz, int px, int pz) {
        long cross = (long) (bx - ax) * (pz - az) - (long) (bz - az) * (px - ax);
        return cross == 0
                && px >= Math.min(ax, bx) && px <= Math.max(ax, bx)
                && pz >= Math.min(az, bz) && pz <= Math.max(az, bz);
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew test --tests '*GoalsTest'`
Expected: PASS (7 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/minecraftcitiesnetwork/directions/walk src/test/java/com/minecraftcitiesnetwork/directions/walk
git commit -m "feat(walk): search window and leg goals matching the arrival rules"
```

---

### Task 5: Cells and routes

**Files:**
- Create: `src/main/java/com/minecraftcitiesnetwork/directions/walk/Cell.java`
- Create: `src/main/java/com/minecraftcitiesnetwork/directions/walk/Route.java`
- Test: `src/test/java/com/minecraftcitiesnetwork/directions/walk/RouteTest.java`

**Interfaces:**
- Consumes: `Terrain`, `Surfaces` (Task 2); `BlockWorld` test helper.
- Produces:
  - `record Cell(int x, int y, int z)` with `static long key(int x, int y, int z)` (y must be in −2048..2047)
  - `final class Route` with `static Route of(List<Cell> cells, boolean complete, double endEstimate)`, `size()`, `x(int)`, `y(int)`, `z(int)`, `cell(int)`, `end()`, `complete()`, `endEstimate()`, `length()`, `distanceTo(int i, double px, double py, double pz)`, `nearestIndex(double px, double py, double pz, int hint)`, `remainingFrom(int i, double px, double py, double pz)`, `indexAhead(int i, double blocks)`, `firstMissing(Terrain terrain, int from, int count)` (returns −1 when all present or unknown).

Spec §7 described "stitching" the new route onto the old one. It isn't needed: every new route starts at the player's position, and the trail is drawn only from the player's nearest point forward, so the trail can never jump behind them.

- [ ] **Step 1: Write the failing tests**

`RouteTest.java`:

```java
package com.minecraftcitiesnetwork.directions.walk;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RouteTest {
    private static final double EPS = 1e-9;

    private static Route line(int fromX, int toX) {
        List<Cell> cells = new ArrayList<>();
        for (int x = fromX; x <= toX; x++) {
            cells.add(new Cell(x, 65, 0));
        }
        return Route.of(cells, true, 0);
    }

    @Test
    void cellKeysAreDistinctForNeighboursAndNegatives() {
        assertTrue(Cell.key(0, 65, 0) != Cell.key(1, 65, 0));
        assertTrue(Cell.key(-1, 65, 0) != Cell.key(1, 65, 0));
        assertTrue(Cell.key(0, -64, 0) != Cell.key(0, 64, 0));
        assertTrue(Cell.key(0, 65, -1) != Cell.key(0, 65, 1));
    }

    @Test
    void lengthAndRemaining() {
        Route r = line(0, 10);
        assertEquals(11, r.size());
        assertEquals(10.0, r.length(), EPS);
        assertEquals(new Cell(10, 65, 0), r.end());
        assertEquals(6.0, r.remainingFrom(4, 4.5, 65, 0.5), EPS);
    }

    @Test
    void diagonalStepsCountTheirTrueLength() {
        Route r = Route.of(List.of(new Cell(0, 65, 0), new Cell(1, 65, 1), new Cell(2, 66, 1)), false, 7);
        assertEquals(Math.sqrt(2) + Math.sqrt(2), r.length(), EPS);
        assertFalse(r.complete());
        assertEquals(7.0, r.endEstimate(), EPS);
    }

    @Test
    void nearestIndexSearchesForwardFromTheHint() {
        Route r = line(0, 100);
        assertEquals(42, r.nearestIndex(42.5, 65, 0.5, 40));
        assertEquals(80, r.nearestIndex(80.5, 65, 0.5, 2));
        assertEquals(0, r.nearestIndex(-5, 65, 0.5, 0));
    }

    @Test
    void nearestIndexPrefersTheNearbySectionOnALoop() {
        // Out along z = 0 and back along z = 2: near (5, 1) both legs are 1 block away.
        List<Cell> cells = new ArrayList<>();
        for (int x = 0; x <= 10; x++) {
            cells.add(new Cell(x, 65, 0));
        }
        for (int x = 10; x >= 0; x--) {
            cells.add(new Cell(x, 65, 2));
        }
        Route r = Route.of(cells, true, 0);
        assertEquals(5, r.nearestIndex(5.5, 65, 1.5, 4));
    }

    @Test
    void indexAheadWalksTheGivenDistance() {
        Route r = line(0, 10);
        assertEquals(6, r.indexAhead(0, 6));
        assertEquals(10, r.indexAhead(8, 6));
    }

    @Test
    void firstMissingFindsABlockedCell() {
        BlockWorld world = new BlockWorld(64).fill(0, 64, 0, 10, 64, 0, BlockKind.SOLID);
        Route r = line(0, 10);
        assertEquals(-1, r.firstMissing(world.terrain(), 0, 40));
        world.set(5, 65, 0, BlockKind.SOLID).set(5, 66, 0, BlockKind.SOLID);
        assertEquals(5, r.firstMissing(world.terrain(), 2, 40));
        assertEquals(-1, r.firstMissing(world.terrain(), 6, 40));
        assertEquals(-1, r.firstMissing((x, z) -> null, 0, 40));
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew test --tests '*RouteTest'`
Expected: compilation FAILS ("cannot find symbol: class Route").

- [ ] **Step 3: Implement**

`Cell.java`:

```java
package com.minecraftcitiesnetwork.directions.walk;

/** A feet position: the block a standing player's feet occupy. */
public record Cell(int x, int y, int z) {
    /** A unique key for maps: 26 bits each of x and z, 12 bits of y (−2048..2047). */
    public static long key(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }
}
```

`Route.java`:

```java
package com.minecraftcitiesnetwork.directions.walk;

import java.util.List;

/**
 * An immutable walking route: feet cells from the start to either the goal ({@link #complete()})
 * or the edge of the search window. Distances are measured between cells.
 */
public final class Route {
    private static final int LOOK_BACK = 8;
    private static final int LOOK_FORWARD = 64;
    private static final double LOCAL_MATCH = 8.0;

    private final int[] xs;
    private final int[] ys;
    private final int[] zs;
    private final double[] cumulative;
    private final boolean complete;
    private final double endEstimate;

    private Route(int[] xs, int[] ys, int[] zs, boolean complete, double endEstimate) {
        this.xs = xs;
        this.ys = ys;
        this.zs = zs;
        this.complete = complete;
        this.endEstimate = endEstimate;
        this.cumulative = new double[xs.length];
        for (int i = 1; i < xs.length; i++) {
            double dx = xs[i] - xs[i - 1];
            double dy = ys[i] - ys[i - 1];
            double dz = zs[i] - zs[i - 1];
            cumulative[i] = cumulative[i - 1] + Math.sqrt(dx * dx + dy * dy + dz * dz);
        }
    }

    /**
     * @param complete    whether the route reaches the goal
     * @param endEstimate for a partial route, the goal's estimate from its last cell; 0 when complete
     */
    public static Route of(List<Cell> cells, boolean complete, double endEstimate) {
        if (cells.isEmpty()) {
            throw new IllegalArgumentException("a route needs at least one cell");
        }
        int n = cells.size();
        int[] xs = new int[n];
        int[] ys = new int[n];
        int[] zs = new int[n];
        for (int i = 0; i < n; i++) {
            Cell c = cells.get(i);
            xs[i] = c.x();
            ys[i] = c.y();
            zs[i] = c.z();
        }
        return new Route(xs, ys, zs, complete, endEstimate);
    }

    public int size() {
        return xs.length;
    }

    public int x(int i) {
        return xs[i];
    }

    public int y(int i) {
        return ys[i];
    }

    public int z(int i) {
        return zs[i];
    }

    public Cell cell(int i) {
        return new Cell(xs[i], ys[i], zs[i]);
    }

    public Cell end() {
        return cell(xs.length - 1);
    }

    public boolean complete() {
        return complete;
    }

    public double endEstimate() {
        return endEstimate;
    }

    public double length() {
        return cumulative[cumulative.length - 1];
    }

    /** Straight-line distance from a player position to the centre of cell {@code i} at feet height. */
    public double distanceTo(int i, double px, double py, double pz) {
        double dx = xs[i] + 0.5 - px;
        double dy = ys[i] - py;
        double dz = zs[i] + 0.5 - pz;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /**
     * The route index nearest the player, looking a little behind and well ahead of {@code hint}
     * first so a route that doubles back on itself does not skip ahead, then the whole route when
     * nothing nearby is close.
     */
    public int nearestIndex(double px, double py, double pz, int hint) {
        int h = Math.max(0, Math.min(size() - 1, hint));
        int best = nearestBetween(Math.max(0, h - LOOK_BACK), Math.min(size(), h + LOOK_FORWARD), px, py, pz);
        if (distanceTo(best, px, py, pz) > LOCAL_MATCH) {
            best = nearestBetween(0, size(), px, py, pz);
        }
        return best;
    }

    /** Distance from the player to cell {@code i}, plus the route from there to its end. */
    public double remainingFrom(int i, double px, double py, double pz) {
        return distanceTo(i, px, py, pz) + (length() - cumulative[i]);
    }

    /** The first index at least {@code blocks} along the route from {@code i}, or the last index. */
    public int indexAhead(int i, double blocks) {
        for (int j = i; j < size(); j++) {
            if (cumulative[j] - cumulative[i] >= blocks) {
                return j;
            }
        }
        return size() - 1;
    }

    /**
     * The first of {@code count} cells from {@code from} that the terrain no longer has a surface at,
     * or −1. Cells in unknown columns are assumed still walkable.
     */
    public int firstMissing(Terrain terrain, int from, int count) {
        int end = Math.min(size(), from + count);
        for (int i = Math.max(0, from); i < end; i++) {
            int[] column = terrain.surfaces(xs[i], zs[i]);
            if (column == null) {
                continue;
            }
            boolean present = false;
            for (int s : column) {
                if (Surfaces.y(s) == ys[i]) {
                    present = true;
                    break;
                }
            }
            if (!present) {
                return i;
            }
        }
        return -1;
    }

    private int nearestBetween(int from, int to, double px, double py, double pz) {
        int best = from;
        double bestDistance = Double.MAX_VALUE;
        for (int i = from; i < to; i++) {
            double d = distanceTo(i, px, py, pz);
            if (d < bestDistance) {
                bestDistance = d;
                best = i;
            }
        }
        return best;
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew test --tests '*RouteTest'`
Expected: PASS (7 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/minecraftcitiesnetwork/directions/walk src/test/java/com/minecraftcitiesnetwork/directions/walk
git commit -m "feat(walk): cells and immutable routes with following helpers"
```

---

### Task 6: Window search

**Files:**
- Create: `src/main/java/com/minecraftcitiesnetwork/directions/walk/SearchRequest.java`
- Create: `src/main/java/com/minecraftcitiesnetwork/directions/walk/SearchResult.java`
- Create: `src/main/java/com/minecraftcitiesnetwork/directions/walk/WindowSearch.java`
- Test: `src/test/java/com/minecraftcitiesnetwork/directions/walk/WindowSearchTest.java`

**Interfaces:**
- Consumes: `Terrain`, `Surfaces` (Task 2); `WalkRules`, `WalkerProfile` (Task 3); `Window`, `Goal`, `BoxGoal` (Task 4); `Cell`, `Route` (Task 5).
- Produces:
  - `record SearchRequest(double x, double y, double z, Goal goal, Window window, List<Cell> spentExits, int maxExpansions, long deadlineNanos)`
  - `record SearchResult(Status status, Route route, double cost, int expansions, long nanos)` with `enum Status { COMPLETE, PARTIAL, NO_ROUTE, BUDGET, INVALID_START }`, `found()`, `static failed(Status, int expansions, long nanos)`
  - `final class WindowSearch(WalkRules rules, LongSupplier nanoClock)` with `SearchResult search(Terrain, SearchRequest)`, `static Start snap(Terrain, double x, double y, double z, Window)`, `record Start(int x, int y, int z, int surface)`, `SNAP_RADIUS = 3`, `SPENT_EXIT_RADIUS = 8`.

Exits (spec §6.2): when no goal cell can be inside the window, a cell is terminal if it lies on the window's border or next to an unknown column, is not within 8 blocks of a spent exit, and is not the start cell (a one-cell route leads nowhere). A\* pops cells in order of `g + h`, so the first terminal cell popped minimises cost-so-far plus straight-line remainder over every reachable exit. The tests check this against brute force.

When the goal *can* be inside the window, the search first looks for the goal alone. If that fails, it searches again with exits allowed only next to unknown columns, because the goal may lie behind terrain that hasn't been captured yet. A goal that is unreachable while everything around it is known (a sealed building) still reports NO_ROUTE, which is what lets the guide say so.

- [ ] **Step 1: Write the failing tests**

`WindowSearchTest.java`:

```java
package com.minecraftcitiesnetwork.directions.walk;

import org.junit.jupiter.api.Test;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WindowSearchTest {
    private static final WalkRules RULES = new WalkRules(WalkerProfile.DEFAULT);
    private static final WindowSearch SEARCH = new WindowSearch(RULES, System::nanoTime);
    private static final double EPS = 1e-9;

    private static BlockWorld flat(int sizeX, int sizeZ) {
        return new BlockWorld(64).fill(0, 64, 0, sizeX - 1, 64, sizeZ - 1, BlockKind.SOLID);
    }

    /** The world as decoded, with everything outside it known to be empty rather than unknown. */
    private static Terrain allKnown(BlockWorld world) {
        Terrain t = world.terrain();
        return (x, z) -> {
            int[] s = t.surfaces(x, z);
            return s == null ? new int[0] : s;
        };
    }

    /** Floor from x −20..40, z −20..20, so a window around the origin has the player in its middle. */
    private static BlockWorld field() {
        return new BlockWorld(64).fill(-20, 64, -20, 40, 64, 20, BlockKind.SOLID);
    }

    private static SearchRequest request(double x, double y, double z, Goal goal, Window window) {
        return new SearchRequest(x, y, z, goal, window, List.of(), 1_000_000, Long.MAX_VALUE);
    }

    private static BoxGoal at(int x, int y, int z) {
        return new BoxGoal(x, y, z, x, y, z);
    }

    @Test
    void straightLineOnOpenFloor() {
        SearchResult r = SEARCH.search(flat(40, 40).terrain(), request(0.5, 65, 0.5, at(10, 65, 0), new Window(0, 0, 39, 39)));
        assertEquals(SearchResult.Status.COMPLETE, r.status());
        assertEquals(10.0, r.cost(), EPS);
        assertEquals(11, r.route().size());
        assertEquals(new Cell(10, 65, 0), r.route().end());
        assertTrue(r.route().complete());
    }

    @Test
    void diagonalsCostTheirLength() {
        SearchResult r = SEARCH.search(flat(40, 40).terrain(), request(0.5, 65, 0.5, at(5, 65, 5), new Window(0, 0, 39, 39)));
        assertEquals(5 * Math.sqrt(2), r.cost(), EPS);
    }

    @Test
    void walksAroundAWall() {
        BlockWorld world = flat(40, 40).fill(5, 65, 0, 5, 66, 30, BlockKind.SOLID);
        SearchResult r = SEARCH.search(world.terrain(), request(0.5, 65, 0.5, at(10, 65, 0), new Window(0, 0, 39, 39)));
        assertEquals(SearchResult.Status.COMPLETE, r.status());
        assertTrue(r.cost() > 10.0);
        for (int i = 0; i < r.route().size(); i++) {
            assertFalse(r.route().x(i) == 5 && r.route().z(i) <= 30, "route crosses the wall at " + r.route().cell(i));
        }
    }

    @Test
    void regionGoalReachesItsNearestCell() {
        SearchResult r = SEARCH.search(flat(40, 40).terrain(),
                request(0.5, 65, 0.5, new BoxGoal(10, 65, 0, 20, 65, 10), new Window(0, 0, 39, 39)));
        assertEquals(new Cell(10, 65, 0), r.route().end());
        assertEquals(10.0, r.cost(), EPS);
    }

    @Test
    void startInsideTheGoalIsCompleteAtOnce() {
        SearchResult r = SEARCH.search(flat(10, 10).terrain(), request(3.5, 65, 3.5, new BoxGoal(0, 60, 0, 9, 70, 9), new Window(0, 0, 9, 9)));
        assertEquals(SearchResult.Status.COMPLETE, r.status());
        assertEquals(1, r.route().size());
        assertEquals(0.0, r.cost(), EPS);
    }

    @Test
    void goalOutsideTheWindowEndsOnTheBorder() {
        SearchResult r = SEARCH.search(field().terrain(), request(0.5, 65, 0.5, at(35, 65, 0), Window.around(0, 0, 20)));
        assertEquals(SearchResult.Status.PARTIAL, r.status());
        assertEquals(new Cell(20, 65, 0), r.route().end());
        assertFalse(r.route().complete());
        assertEquals(15.0, r.route().endEstimate(), EPS);
    }

    @Test
    void wallAcrossTheWindowIsLeftByTheBestReachableEdge() {
        BlockWorld world = flat(40, 40).fill(10, 65, 0, 10, 66, 20, BlockKind.SOLID);
        Terrain t = world.terrain();
        Window window = new Window(0, 0, 20, 20);
        Goal goal = at(35, 65, 10);
        SearchResult r = SEARCH.search(t, request(2.5, 65, 10.5, goal, window));
        assertEquals(SearchResult.Status.PARTIAL, r.status());
        assertTrue(r.route().end().x() < 10, "exit must be on this side of the wall: " + r.route().end());
        WindowSearch.Start start = WindowSearch.snap(t, 2.5, 65, 10.5, window);
        assertEquals(bestExit(t, start, goal, window), r.cost() + r.route().endEstimate(), EPS);
    }

    @Test
    void spentExitIsNotOfferedAgain() {
        SearchRequest req = new SearchRequest(0.5, 65, 0.5, at(35, 65, 0), Window.around(0, 0, 20),
                List.of(new Cell(20, 65, 0)), 1_000_000, Long.MAX_VALUE);
        SearchResult r = SEARCH.search(field().terrain(), req);
        assertEquals(SearchResult.Status.PARTIAL, r.status());
        Cell end = r.route().end();
        assertTrue(Math.abs(end.x() - 20) > WindowSearch.SPENT_EXIT_RADIUS || Math.abs(end.z()) > WindowSearch.SPENT_EXIT_RADIUS,
                "re-used the spent exit: " + end);
    }

    @Test
    void unknownTerrainActsAsAnEdge() {
        Terrain base = field().terrain();
        Terrain clipped = (x, z) -> x > 10 ? null : base.surfaces(x, z);
        SearchResult r = SEARCH.search(clipped, request(0.5, 65, 0.5, at(35, 65, 0), new Window(-20, -20, 39, 20)));
        assertEquals(SearchResult.Status.PARTIAL, r.status());
        assertEquals(10, r.route().end().x());
    }

    @Test
    void expansionCapReportsBudget() {
        SearchRequest req = new SearchRequest(0.5, 65, 0.5, at(0, 200, 0), new Window(0, 0, 39, 39), List.of(), 5, Long.MAX_VALUE);
        assertEquals(SearchResult.Status.BUDGET, SEARCH.search(flat(40, 40).terrain(), req).status());
    }

    @Test
    void deadlineReportsBudget() {
        AtomicLong clock = new AtomicLong();
        WindowSearch slow = new WindowSearch(RULES, () -> clock.addAndGet(1_000_000));
        SearchRequest req = new SearchRequest(0.5, 65, 0.5, at(0, 200, 0), new Window(0, 0, 39, 39), List.of(), 1_000_000, 1);
        assertEquals(SearchResult.Status.BUDGET, slow.search(flat(40, 40).terrain(), req).status());
    }

    @Test
    void unreachableGoalInsideFullyKnownTerrainIsNoRoute() {
        SearchResult r = SEARCH.search(allKnown(flat(40, 40)), request(0.5, 65, 0.5, at(0, 200, 0), new Window(0, 0, 39, 39)));
        assertEquals(SearchResult.Status.NO_ROUTE, r.status());
    }

    @Test
    void unreachableGoalNextToUnknownTerrainLeadsTowardIt() {
        SearchResult r = SEARCH.search(flat(40, 40).terrain(), request(5.5, 65, 5.5, at(5, 200, 5), new Window(0, 0, 39, 39)));
        assertEquals(SearchResult.Status.PARTIAL, r.status());
        Cell end = r.route().end();
        assertTrue(end.x() == 0 || end.z() == 0, "should end beside the undecoded chunks at x < 0 or z < 0: " + end);
    }

    @Test
    void startOnTheBorderIsNotAnExit() {
        SearchResult r = SEARCH.search(field().terrain(), request(0.5, 65, 0.5, at(35, 65, 0), new Window(0, -20, 20, 20)));
        assertEquals(SearchResult.Status.PARTIAL, r.status());
        assertTrue(r.route().size() > 1);
    }

    @Test
    void startInMidAirIsInvalid() {
        SearchResult r = SEARCH.search(flat(10, 10).terrain(), request(0.5, 80, 0.5, at(5, 65, 0), new Window(0, 0, 9, 9)));
        assertEquals(SearchResult.Status.INVALID_START, r.status());
    }

    @Test
    void negativeCoordinatesWork() {
        BlockWorld world = new BlockWorld(64).fill(-30, 64, -30, -1, 64, -1, BlockKind.SOLID);
        SearchResult r = SEARCH.search(world.terrain(), request(-0.5, 65, -0.5, at(-20, 65, -1), new Window(-30, -30, -1, -1)));
        assertEquals(SearchResult.Status.COMPLETE, r.status());
        assertEquals(new Cell(-1, 65, -1), r.route().cell(0));
        assertEquals(19.0, r.cost(), EPS);
    }

    @Test
    void startOnSlabSnapsOntoIt() {
        BlockWorld world = flat(10, 10).set(0, 65, 0, BlockKind.HALF);
        SearchResult r = SEARCH.search(world.terrain(), request(0.5, 65.5, 0.5, at(5, 65, 0), new Window(0, 0, 9, 9)));
        assertEquals(SearchResult.Status.COMPLETE, r.status());
        assertEquals(new Cell(0, 66, 0), r.route().cell(0));
    }

    @Test
    void swimmingStartSnapsToWaterSurface() {
        BlockWorld world = new BlockWorld(63).fill(0, 63, 0, 9, 63, 9, BlockKind.SOLID)
                .fill(0, 64, 0, 2, 64, 2, BlockKind.WATER)
                .fill(3, 64, 0, 9, 64, 9, BlockKind.SOLID)
                .fill(0, 64, 3, 2, 64, 9, BlockKind.SOLID);
        SearchResult r = SEARCH.search(world.terrain(), request(0.5, 64.3, 0.5, at(6, 65, 0), new Window(0, 0, 9, 9)));
        assertEquals(SearchResult.Status.COMPLETE, r.status());
        assertEquals(new Cell(0, 64, 0), r.route().cell(0));
    }

    @Test
    void matchesDijkstraOnRandomWorlds() {
        int checked = 0;
        for (long seed = 1; seed <= 200; seed++) {
            Terrain t = allKnown(randomWorld(seed, 24));
            Window window = new Window(0, 0, 23, 23);
            Goal goal = new BoxGoal(18, 60, 18, 23, 70, 23);
            WindowSearch.Start start = WindowSearch.snap(t, 0.5, 66, 0.5, window);
            if (start == null) {
                continue;
            }
            checked++;
            double expected = bestGoal(t, start, goal, window);
            SearchResult r = SEARCH.search(t, request(0.5, 66, 0.5, goal, window));
            if (Double.isInfinite(expected)) {
                assertEquals(SearchResult.Status.NO_ROUTE, r.status(), "seed " + seed);
            } else {
                assertEquals(SearchResult.Status.COMPLETE, r.status(), "seed " + seed);
                assertEquals(expected, r.cost(), EPS, "seed " + seed);
            }
        }
        assertTrue(checked >= 150, "only " + checked + " seeds had a valid start");
    }

    @Test
    void exitMinimisesCostPlusEstimateOnRandomWorlds() {
        for (long seed = 1; seed <= 100; seed++) {
            Terrain t = randomWorld(seed, 24).terrain();
            Window window = new Window(0, 0, 15, 15);
            Goal goal = new BoxGoal(40, 60, 40, 45, 70, 45);
            WindowSearch.Start start = WindowSearch.snap(t, 7.5, 66, 7.5, window);
            if (start == null) {
                continue;
            }
            double expected = bestExit(t, start, goal, window);
            SearchResult r = SEARCH.search(t, request(7.5, 66, 7.5, goal, window));
            if (Double.isInfinite(expected)) {
                assertEquals(SearchResult.Status.NO_ROUTE, r.status(), "seed " + seed);
            } else {
                assertEquals(SearchResult.Status.PARTIAL, r.status(), "seed " + seed);
                assertNotNull(r.route());
                assertEquals(expected, r.cost() + r.route().endEstimate(), EPS, "seed " + seed);
            }
        }
    }

    // ---- reference implementation -------------------------------------------------------------

    private record Item(double d, int x, int y, int z, int surface) {
    }

    /** Plain Dijkstra over the same rules: cost of every reachable cell, keyed like {@link Cell#key}. */
    private static Map<Long, double[]> dijkstra(Terrain t, WindowSearch.Start s, Window w) {
        Map<Long, double[]> best = new HashMap<>();
        PriorityQueue<Item> queue = new PriorityQueue<>(Comparator.comparingDouble(Item::d));
        best.put(Cell.key(s.x(), s.y(), s.z()), new double[]{0, s.x(), s.y(), s.z()});
        queue.add(new Item(0, s.x(), s.y(), s.z(), s.surface()));
        while (!queue.isEmpty()) {
            Item it = queue.poll();
            if (it.d() > best.get(Cell.key(it.x(), it.y(), it.z()))[0]) {
                continue;
            }
            RULES.neighbours(t, it.x(), it.z(), it.surface(), (x, y, z, surface, cost) -> {
                if (!w.contains(x, z)) {
                    return;
                }
                long key = Cell.key(x, y, z);
                double d = it.d() + cost;
                double[] old = best.get(key);
                if (old == null || d < old[0]) {
                    best.put(key, new double[]{d, x, y, z});
                    queue.add(new Item(d, x, y, z, surface));
                }
            });
        }
        return best;
    }

    private static double bestGoal(Terrain t, WindowSearch.Start s, Goal goal, Window w) {
        double best = Double.POSITIVE_INFINITY;
        for (double[] e : dijkstra(t, s, w).values()) {
            if (goal.contains((int) e[1], (int) e[2], (int) e[3])) {
                best = Math.min(best, e[0]);
            }
        }
        return best;
    }

    private static double bestExit(Terrain t, WindowSearch.Start s, Goal goal, Window w) {
        double best = Double.POSITIVE_INFINITY;
        for (double[] e : dijkstra(t, s, w).values()) {
            int x = (int) e[1];
            int z = (int) e[3];
            if (x == s.x() && (int) e[2] == s.y() && z == s.z()) {
                continue;
            }
            boolean exit = w.onBorder(x, z)
                    || t.surfaces(x + 1, z) == null || t.surfaces(x - 1, z) == null
                    || t.surfaces(x, z + 1) == null || t.surfaces(x, z - 1) == null;
            if (exit) {
                best = Math.min(best, e[0] + goal.estimate(x, (int) e[2], z));
            }
        }
        return best;
    }

    private static BlockWorld randomWorld(long seed, int size) {
        Random random = new Random(seed);
        BlockWorld world = new BlockWorld(63);
        for (int x = 0; x < size; x++) {
            for (int z = 0; z < size; z++) {
                world.set(x, 63, z, BlockKind.SOLID);
                double roll = random.nextDouble();
                if (roll < 0.15) {
                    world.fill(x, 64, z, x, 66, z, BlockKind.SOLID);
                } else if (roll < 0.25) {
                    world.fill(x, 64, z, x, 65, z, BlockKind.SOLID);
                } else if (roll < 0.30) {
                    world.set(x, 64, z, BlockKind.WATER);
                } else if (roll < 0.33) {
                    world.set(x, 64, z, BlockKind.HALF);
                } else if (roll < 0.36) {
                    world.set(x, 64, z, BlockKind.FENCE);
                } else {
                    world.set(x, 64, z, BlockKind.SOLID);
                }
                if (random.nextDouble() < 0.05) {
                    world.set(x, 67, z, BlockKind.SOLID);
                }
            }
        }
        return world;
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew test --tests '*WindowSearchTest'`
Expected: compilation FAILS ("cannot find symbol: class WindowSearch").

- [ ] **Step 3: Implement**

`SearchRequest.java`:

```java
package com.minecraftcitiesnetwork.directions.walk;

import java.util.List;

/**
 * One window search.
 *
 * @param x             the player's position; snapped to a surface within {@link WindowSearch#SNAP_RADIUS}
 * @param spentExits    exits this leg has already led the player to; edges near them are not offered again
 * @param maxExpansions node expansions allowed before giving up with BUDGET
 * @param deadlineNanos {@link System#nanoTime()} value after which the search gives up with BUDGET
 */
public record SearchRequest(double x, double y, double z, Goal goal, Window window, List<Cell> spentExits,
                            int maxExpansions, long deadlineNanos) {
    public SearchRequest {
        spentExits = List.copyOf(spentExits);
    }
}
```

`SearchResult.java`:

```java
package com.minecraftcitiesnetwork.directions.walk;

/**
 * @param route the route for COMPLETE and PARTIAL, otherwise null
 * @param cost  walking cost of the route
 */
public record SearchResult(Status status, Route route, double cost, int expansions, long nanos) {
    public enum Status {
        /** The route reaches the goal. */
        COMPLETE,
        /** The goal is outside the window; the route ends at the best exit. */
        PARTIAL,
        /** Nothing reachable inside the window leads on. */
        NO_ROUTE,
        /** The expansion cap or the time budget ran out. */
        BUDGET,
        /** No surface near the player to start from. */
        INVALID_START
    }

    public boolean found() {
        return status == Status.COMPLETE || status == Status.PARTIAL;
    }

    public static SearchResult failed(Status status, int expansions, long nanos) {
        return new SearchResult(status, null, 0.0, expansions, nanos);
    }
}
```

`WindowSearch.java`:

```java
package com.minecraftcitiesnetwork.directions.walk;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.function.LongSupplier;

/**
 * A* over walkable surfaces inside a window. Reaches the goal when it can; when no goal cell can be
 * in the window, ends at the exit that minimises cost so far plus straight-line distance onward.
 * Deterministic: identical inputs give identical routes. Safe to share between threads.
 */
public final class WindowSearch {
    public static final int SNAP_RADIUS = 3;
    public static final int SPENT_EXIT_RADIUS = 8;
    private static final int CLOCK_CHECK_MASK = 255;

    /** The surface a search starts from. */
    public record Start(int x, int y, int z, int surface) {
    }

    private final WalkRules rules;
    private final LongSupplier nanoClock;

    public WindowSearch(WalkRules rules, LongSupplier nanoClock) {
        this.rules = rules;
        this.nanoClock = nanoClock;
    }

    public SearchResult search(Terrain terrain, SearchRequest request) {
        long started = nanoClock.getAsLong();
        Start start = snap(terrain, request.x(), request.y(), request.z(), request.window());
        if (start == null) {
            return SearchResult.failed(SearchResult.Status.INVALID_START, 0, nanoClock.getAsLong() - started);
        }
        if (!request.goal().mayOverlap(request.window())) {
            return run(terrain, request, start, Exits.ANY, started);
        }
        SearchResult direct = run(terrain, request, start, Exits.NONE, started);
        if (direct.status() != SearchResult.Status.NO_ROUTE) {
            return direct;
        }
        // The goal may lie behind terrain that is not captured yet: lead toward it instead.
        return run(terrain, request, start, Exits.UNKNOWN_ONLY, started);
    }

    private enum Exits {
        /** Only the goal ends the search. */
        NONE,
        /** The goal, or a cell next to uncaptured terrain. */
        UNKNOWN_ONLY,
        /** The goal, a cell next to uncaptured terrain, or the window border. */
        ANY
    }

    private SearchResult run(Terrain terrain, SearchRequest request, Start start, Exits exits, long started) {
        Window window = request.window();
        Goal goal = request.goal();
        Map<Long, Node> nodes = new HashMap<>();
        PriorityQueue<Entry> open = new PriorityQueue<>();
        Node first = new Node(start.x(), start.y(), start.z(), start.surface());
        first.g = 0.0;
        nodes.put(first.key, first);
        open.add(new Entry(first, 0.0, goal.estimate(start.x(), start.y(), start.z())));
        int expansions = 0;
        while (!open.isEmpty()) {
            Entry entry = open.poll();
            Node node = entry.node();
            if (node.closed || entry.g() != node.g) {
                continue;
            }
            node.closed = true;
            if (goal.contains(node.x, node.y, node.z)) {
                return finish(SearchResult.Status.COMPLETE, node, 0.0, expansions, started);
            }
            if (exits != Exits.NONE && node != first && isExit(terrain, request, node.x, node.z, exits == Exits.ANY)) {
                return finish(SearchResult.Status.PARTIAL, node, goal.estimate(node.x, node.y, node.z), expansions, started);
            }
            if (expansions >= request.maxExpansions()) {
                return SearchResult.failed(SearchResult.Status.BUDGET, expansions, nanoClock.getAsLong() - started);
            }
            expansions++;
            if ((expansions & CLOCK_CHECK_MASK) == 0 && nanoClock.getAsLong() > request.deadlineNanos()) {
                return SearchResult.failed(SearchResult.Status.BUDGET, expansions, nanoClock.getAsLong() - started);
            }
            Node from = node;
            rules.neighbours(terrain, node.x, node.z, node.surface, (x, y, z, surface, cost) -> {
                if (!window.contains(x, z)) {
                    return;
                }
                long key = Cell.key(x, y, z);
                double g = from.g + cost;
                Node next = nodes.get(key);
                if (next == null) {
                    next = new Node(x, y, z, surface);
                    nodes.put(key, next);
                } else if (next.closed || g >= next.g) {
                    return;
                }
                next.g = g;
                next.parent = from;
                open.add(new Entry(next, g, goal.estimate(x, y, z)));
            });
        }
        return SearchResult.failed(SearchResult.Status.NO_ROUTE, expansions, nanoClock.getAsLong() - started);
    }

    /**
     * The nearest surface to a player position: rings of growing horizontal distance up to
     * {@link #SNAP_RADIUS}, within {@link #SNAP_RADIUS} blocks of height, closest first.
     */
    public static Start snap(Terrain terrain, double x, double y, double z, Window window) {
        int fx = (int) Math.floor(x);
        int fy = (int) Math.floor(y);
        int fz = (int) Math.floor(z);
        for (int r = 0; r <= SNAP_RADIUS; r++) {
            Start best = null;
            int bestDistance = Integer.MAX_VALUE;
            int bestDy = Integer.MAX_VALUE;
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) {
                        continue;
                    }
                    int cx = fx + dx;
                    int cz = fz + dz;
                    if (!window.contains(cx, cz)) {
                        continue;
                    }
                    int[] column = terrain.surfaces(cx, cz);
                    if (column == null) {
                        continue;
                    }
                    for (int s : column) {
                        int dy = Math.abs(Surfaces.y(s) - fy);
                        int d = dx * dx + dz * dz;
                        if (dy <= SNAP_RADIUS && (d < bestDistance || (d == bestDistance && dy < bestDy))) {
                            best = new Start(cx, Surfaces.y(s), cz, s);
                            bestDistance = d;
                            bestDy = dy;
                        }
                    }
                }
            }
            if (best != null) {
                return best;
            }
        }
        return null;
    }

    private static boolean isExit(Terrain terrain, SearchRequest request, int x, int z, boolean border) {
        boolean edge = (border && request.window().onBorder(x, z))
                || terrain.surfaces(x + 1, z) == null || terrain.surfaces(x - 1, z) == null
                || terrain.surfaces(x, z + 1) == null || terrain.surfaces(x, z - 1) == null;
        if (!edge) {
            return false;
        }
        for (Cell spent : request.spentExits()) {
            if (Math.abs(spent.x() - x) <= SPENT_EXIT_RADIUS && Math.abs(spent.z() - z) <= SPENT_EXIT_RADIUS) {
                return false;
            }
        }
        return true;
    }

    private SearchResult finish(SearchResult.Status status, Node last, double endEstimate, int expansions, long started) {
        List<Cell> cells = new ArrayList<>();
        for (Node n = last; n != null; n = n.parent) {
            cells.add(new Cell(n.x, n.y, n.z));
        }
        Collections.reverse(cells);
        Route route = Route.of(cells, status == SearchResult.Status.COMPLETE, endEstimate);
        return new SearchResult(status, route, last.g, expansions, nanoClock.getAsLong() - started);
    }

    private static final class Node {
        final int x;
        final int y;
        final int z;
        final int surface;
        final long key;
        double g = Double.POSITIVE_INFINITY;
        Node parent;
        boolean closed;

        Node(int x, int y, int z, int surface) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.surface = surface;
            this.key = Cell.key(x, y, z);
        }
    }

    /** A queue entry; stale once its node has a lower g. Ties break on h, then on position. */
    private record Entry(Node node, double g, double f, double h) implements Comparable<Entry> {
        Entry(Node node, double g, double h) {
            this(node, g, g + h, h);
        }

        @Override
        public int compareTo(Entry o) {
            int c = Double.compare(f, o.f);
            if (c != 0) {
                return c;
            }
            c = Double.compare(h, o.h);
            if (c != 0) {
                return c;
            }
            return Long.compare(node.key, o.node.key);
        }
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew test --tests '*WindowSearchTest'`
Expected: PASS (20 tests). If `matchesDijkstraOnRandomWorlds` fails on a seed, do not loosen the tolerance. The two implementations share `WalkRules`, so a mismatch means the A\* itself is wrong: stale-entry handling, or a heuristic that overestimates. Use superpowers:systematic-debugging.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/minecraftcitiesnetwork/directions/walk src/test/java/com/minecraftcitiesnetwork/directions/walk
git commit -m "feat(walk): bounded A* that reaches the goal or the best window exit"
```

---

### Task 7: Guide policy

**Files:**
- Create: `src/main/java/com/minecraftcitiesnetwork/directions/walk/GuidePolicy.java`
- Test: `src/test/java/com/minecraftcitiesnetwork/directions/walk/GuidePolicyTest.java`

**Interfaces:**
- Consumes: `Cell`, `Route` (Task 5); `SearchResult` (Task 6).
- Produces: `final class GuidePolicy(Tuning)` with:
  - `record Tuning(double extendDistance, double offRouteDistance, long cooldownMillis)`
  - `enum Reason { INITIAL, EXTEND, OFF_ROUTE, BLOCKED, RETRY }`
  - `record Observation(long nowMillis, double x, double y, double z, boolean onFoot, boolean blockedAhead)`
  - `record SearchOrder(int generation, Reason reason, List<Cell> spentExits)`
  - `SearchOrder tick(Observation)` (null when no search is needed), `void onResult(int generation, SearchResult result, long nowMillis)`, `void reset()`, `Route route()` (null unless following), `int cursor()`, `boolean failed()`, `boolean takeNoRouteMessage()`
  - constants `TELEPORT_DISTANCE = 64`, `RETRY_MILLIS = 10_000`, `RETRY_DISTANCE = 16`, `MIN_INTERVAL_MILLIS = 250`, `MAX_SPENT_EXITS = 32`.

This replaces the spec's "10% better" rule with spent exits (spec §7). Each extension search is made from the player's current position, so a 10% comparison would reject legitimate extensions. The real way local search flip-flops is returning to an exit it already led the player toward. When an EXTEND search is ordered, the old route's end is recorded as spent, and the search won't end within 8 blocks of it again during this leg.

- [ ] **Step 1: Write the failing tests**

`GuidePolicyTest.java`:

```java
package com.minecraftcitiesnetwork.directions.walk;

import com.minecraftcitiesnetwork.directions.walk.GuidePolicy.Observation;
import com.minecraftcitiesnetwork.directions.walk.GuidePolicy.Reason;
import com.minecraftcitiesnetwork.directions.walk.GuidePolicy.SearchOrder;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GuidePolicyTest {
    private static final GuidePolicy.Tuning TUNING = new GuidePolicy.Tuning(48, 6, 2000);

    private static Route straight(int fromX, int toX, boolean complete, double endEstimate) {
        List<Cell> cells = new ArrayList<>();
        for (int x = fromX; x <= toX; x++) {
            cells.add(new Cell(x, 65, 0));
        }
        return Route.of(cells, complete, endEstimate);
    }

    private static SearchResult found(Route route) {
        return new SearchResult(route.complete() ? SearchResult.Status.COMPLETE : SearchResult.Status.PARTIAL,
                route, route.length(), 10, 1000);
    }

    private static SearchResult noRoute() {
        return SearchResult.failed(SearchResult.Status.NO_ROUTE, 10, 1000);
    }

    private static Observation at(long now, double x) {
        return new Observation(now, x + 0.5, 65, 0.5, true, false);
    }

    /** A policy following {@code route}, with its one search issued and answered at time 0. */
    private static GuidePolicy following(Route route) {
        GuidePolicy p = new GuidePolicy(TUNING);
        SearchOrder first = p.tick(at(0, 0));
        p.onResult(first.generation(), found(route), 0);
        return p;
    }

    @Test
    void firstTickOrdersTheInitialSearch() {
        SearchOrder order = new GuidePolicy(TUNING).tick(at(0, 0));
        assertNotNull(order);
        assertEquals(Reason.INITIAL, order.reason());
        assertEquals(0, order.generation());
        assertTrue(order.spentExits().isEmpty());
    }

    @Test
    void onlyOneSearchIsInFlight() {
        GuidePolicy p = new GuidePolicy(TUNING);
        p.tick(at(0, 0));
        assertNull(p.tick(at(5000, 0)));
    }

    @Test
    void completeRouteIsFollowedWithoutReplanning() {
        GuidePolicy p = following(straight(0, 100, true, 0));
        assertNull(p.tick(at(5000, 10)));
        assertNotNull(p.route());
        assertEquals(10, p.cursor());
    }

    @Test
    void strayingReplansAfterTheCooldown() {
        GuidePolicy p = following(straight(0, 100, true, 0));
        assertNull(p.tick(new Observation(1000, 10.5, 65, 10.5, true, false)));
        SearchOrder order = p.tick(new Observation(2500, 10.5, 65, 10.5, true, false));
        assertNotNull(order);
        assertEquals(Reason.OFF_ROUTE, order.reason());
    }

    @Test
    void partialRouteIsExtendedNearItsEndAndItsExitIsSpent() {
        GuidePolicy p = following(straight(0, 100, false, 50));
        assertNull(p.tick(at(3000, 40)));
        SearchOrder order = p.tick(at(3000, 60));
        assertNotNull(order);
        assertEquals(Reason.EXTEND, order.reason());
        assertEquals(List.of(new Cell(100, 65, 0)), order.spentExits());
    }

    @Test
    void blockedRouteReplansWithoutWaitingForTheCooldown() {
        GuidePolicy p = following(straight(0, 100, true, 0));
        assertNull(p.tick(new Observation(100, 5.5, 65, 0.5, true, true)));
        SearchOrder order = p.tick(new Observation(300, 5.5, 65, 0.5, true, true));
        assertNotNull(order);
        assertEquals(Reason.BLOCKED, order.reason());
    }

    @Test
    void notOnFootSuppressesStrayingButNotBlocking() {
        GuidePolicy p = following(straight(0, 100, true, 0));
        assertNull(p.tick(new Observation(5000, 10.5, 70, 10.5, false, false)));
        SearchOrder order = p.tick(new Observation(5000, 10.5, 70, 10.5, false, true));
        assertNotNull(order);
        assertEquals(Reason.BLOCKED, order.reason());
    }

    @Test
    void failureIsAnnouncedOnceAndRetried() {
        GuidePolicy p = new GuidePolicy(TUNING);
        SearchOrder first = p.tick(at(0, 0));
        p.onResult(first.generation(), noRoute(), 0);
        assertTrue(p.failed());
        assertNull(p.route());
        assertTrue(p.takeNoRouteMessage());
        assertFalse(p.takeNoRouteMessage());

        assertNull(p.tick(at(5000, 0)));
        SearchOrder retry = p.tick(at(10_000, 0));
        assertNotNull(retry);
        assertEquals(Reason.RETRY, retry.reason());
        p.onResult(retry.generation(), noRoute(), 10_000);
        assertFalse(p.takeNoRouteMessage());

        SearchOrder moved = p.tick(at(11_000, 20));
        assertNotNull(moved);
        assertEquals(Reason.RETRY, moved.reason());
    }

    @Test
    void failedExtensionKeepsTheCurrentRoute() {
        GuidePolicy p = following(straight(0, 100, false, 50));
        SearchOrder order = p.tick(at(3000, 60));
        p.onResult(order.generation(), noRoute(), 3000);
        assertNotNull(p.route());
        assertFalse(p.failed());
    }

    @Test
    void teleportFarFromTheRouteStartsAgain() {
        GuidePolicy p = following(straight(0, 100, true, 0));
        SearchOrder order = p.tick(at(5000, 500));
        assertNotNull(order);
        assertEquals(Reason.INITIAL, order.reason());
        assertEquals(1, order.generation());
        assertNull(p.route());
    }

    @Test
    void resultOfAnEarlierGenerationIsIgnored() {
        GuidePolicy p = new GuidePolicy(TUNING);
        SearchOrder old = p.tick(at(0, 0));
        p.reset();
        assertNull(p.tick(at(500, 0)));
        p.onResult(old.generation(), found(straight(0, 10, true, 0)), 500);
        assertNull(p.route());
        SearchOrder fresh = p.tick(at(1000, 0));
        assertNotNull(fresh);
        assertEquals(1, fresh.generation());
    }

    @Test
    void resetForgetsSpentExits() {
        GuidePolicy p = following(straight(0, 100, false, 50));
        SearchOrder extend = p.tick(at(3000, 60));
        p.onResult(extend.generation(), noRoute(), 3000);
        p.reset();
        SearchOrder order = p.tick(at(4000, 60));
        assertNotNull(order);
        assertTrue(order.spentExits().isEmpty());
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew test --tests '*GuidePolicyTest'`
Expected: compilation FAILS ("cannot find symbol: class GuidePolicy").

- [ ] **Step 3: Implement**

`GuidePolicy.java`:

```java
package com.minecraftcitiesnetwork.directions.walk;

import java.util.ArrayList;
import java.util.List;

/**
 * Decides when one walking leg needs a new search and what to do with the answer. Pure: the caller
 * supplies time, position and search results. One instance per walking leg; not thread-safe.
 */
public final class GuidePolicy {
    public static final double TELEPORT_DISTANCE = 64;
    public static final long RETRY_MILLIS = 10_000;
    public static final double RETRY_DISTANCE = 16;
    public static final long MIN_INTERVAL_MILLIS = 250;
    public static final int MAX_SPENT_EXITS = 32;

    public record Tuning(double extendDistance, double offRouteDistance, long cooldownMillis) {
    }

    public enum Reason {
        INITIAL, EXTEND, OFF_ROUTE, BLOCKED, RETRY
    }

    /** What the guide sees this tick. {@code blockedAhead}: a cell of the drawn trail became impassable. */
    public record Observation(long nowMillis, double x, double y, double z, boolean onFoot, boolean blockedAhead) {
    }

    public record SearchOrder(int generation, Reason reason, List<Cell> spentExits) {
    }

    private enum State {
        IDLE, FOLLOWING, FAILED
    }

    private final Tuning tuning;
    private final List<Cell> spentExits = new ArrayList<>();
    private State state = State.IDLE;
    private int generation;
    private Route route;
    private int cursor;
    private boolean inFlight;
    private Reason inFlightReason;
    private long lastSearchAt = Long.MIN_VALUE / 2;
    private long failedAt;
    private double failedX;
    private double failedZ;
    private double lastX;
    private double lastZ;
    private boolean announced;
    private boolean pendingMessage;

    public GuidePolicy(Tuning tuning) {
        this.tuning = tuning;
    }

    /** Updates position along the route and returns a search to start now, or null. */
    public SearchOrder tick(Observation o) {
        lastX = o.x();
        lastZ = o.z();
        double offRoute = 0.0;
        if (route != null) {
            cursor = route.nearestIndex(o.x(), o.y(), o.z(), cursor);
            offRoute = route.distanceTo(cursor, o.x(), o.y(), o.z());
            if (offRoute > TELEPORT_DISTANCE) {
                reset();
            }
        }
        Reason want = switch (state) {
            case IDLE -> Reason.INITIAL;
            case FAILED -> retryDue(o) ? Reason.RETRY : null;
            case FOLLOWING -> followingReason(o, offRoute);
        };
        if (want == null || inFlight) {
            return null;
        }
        long since = o.nowMillis() - lastSearchAt;
        if (since < MIN_INTERVAL_MILLIS) {
            return null;
        }
        boolean cooled = since >= tuning.cooldownMillis();
        if ((want == Reason.EXTEND || want == Reason.OFF_ROUTE) && !cooled) {
            return null;
        }
        if (want == Reason.EXTEND) {
            if (spentExits.size() >= MAX_SPENT_EXITS) {
                spentExits.remove(0);
            }
            spentExits.add(route.end());
        }
        inFlight = true;
        inFlightReason = want;
        lastSearchAt = o.nowMillis();
        return new SearchOrder(generation, want, List.copyOf(spentExits));
    }

    public void onResult(int resultGeneration, SearchResult result, long nowMillis) {
        inFlight = false;
        if (resultGeneration != generation) {
            return;
        }
        if (result.found()) {
            route = result.route();
            cursor = 0;
            state = State.FOLLOWING;
            return;
        }
        if (route != null && (inFlightReason == Reason.EXTEND || inFlightReason == Reason.OFF_ROUTE)) {
            return;
        }
        route = null;
        state = State.FAILED;
        failedAt = nowMillis;
        failedX = lastX;
        failedZ = lastZ;
        if (!announced) {
            announced = true;
            pendingMessage = true;
        }
    }

    /** Forgets the route and starts the leg again, e.g. after a teleport or a world change. */
    public void reset() {
        generation++;
        route = null;
        cursor = 0;
        state = State.IDLE;
        spentExits.clear();
    }

    public Route route() {
        return state == State.FOLLOWING ? route : null;
    }

    public int cursor() {
        return cursor;
    }

    public boolean failed() {
        return state == State.FAILED;
    }

    /** True once per leg, after the first failed search. */
    public boolean takeNoRouteMessage() {
        boolean message = pendingMessage;
        pendingMessage = false;
        return message;
    }

    private Reason followingReason(Observation o, double offRoute) {
        if (o.blockedAhead()) {
            return Reason.BLOCKED;
        }
        if (!o.onFoot()) {
            return null;
        }
        if (offRoute > tuning.offRouteDistance()) {
            return Reason.OFF_ROUTE;
        }
        if (!route.complete() && route.remainingFrom(cursor, o.x(), o.y(), o.z()) <= tuning.extendDistance()) {
            return Reason.EXTEND;
        }
        return null;
    }

    private boolean retryDue(Observation o) {
        return o.nowMillis() - failedAt >= RETRY_MILLIS
                || Math.hypot(o.x() - failedX, o.z() - failedZ) >= RETRY_DISTANCE;
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew test --tests '*GuidePolicyTest'`
Expected: PASS (12 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/minecraftcitiesnetwork/directions/walk src/test/java/com/minecraftcitiesnetwork/directions/walk
git commit -m "feat(walk): guide policy for re-planning, retries and spent exits"
```

---

### Task 8: Column store and stats

**Files:**
- Create: `src/main/java/com/minecraftcitiesnetwork/directions/walk/ColumnStore.java`
- Create: `src/main/java/com/minecraftcitiesnetwork/directions/walk/WalkStats.java`
- Test: `src/test/java/com/minecraftcitiesnetwork/directions/walk/ColumnStoreTest.java`
- Test: `src/test/java/com/minecraftcitiesnetwork/directions/walk/WalkStatsTest.java`

**Interfaces:**
- Consumes: `ColumnChunk`, `ColumnDecoder`, `Terrain` (Task 2); `SearchResult` (Task 6); `BlockWorld` test helper.
- Produces:
  - `final class ColumnStore(int maxChunks, LongSupplier millisClock)` with `put(UUID world, ColumnChunk)`, `contains(UUID, int cx, int cz)`, `remove(UUID, int cx, int cz)`, `clear()`, `size()`, `Terrain terrain(UUID world)`. Safe for concurrent `put` and reads. Each `terrain(...)` view is single-threaded: create one per search, and one per main-thread use.
  - `final class WalkStats(LongSupplier millisClock)` with `record(SearchResult)`, `cacheHit()`, `cacheMiss()`, `recordPumpTick(long nanos)`, `searchesPerSecond()`, `percentileNanos(double p)`, `results(SearchResult.Status)`, `hitRate()`, `worstPumpNanos()`, `List<String> describe(int cachedChunks)`. All methods are thread-safe.

- [ ] **Step 1: Write the failing tests**

`ColumnStoreTest.java`:

```java
package com.minecraftcitiesnetwork.directions.walk;

import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ColumnStoreTest {
    private static final UUID WORLD = UUID.randomUUID();
    private static final UUID OTHER = UUID.randomUUID();

    private static ColumnChunk floorChunk(int cx, int cz) {
        return ColumnDecoder.decode(cx, cz, new ChunkBlocks() {
            @Override
            public int minY() {
                return 64;
            }

            @Override
            public int maxY() {
                return 70;
            }

            @Override
            public BlockKind kind(int localX, int y, int localZ) {
                return y == 64 ? BlockKind.SOLID : BlockKind.OPEN;
            }
        });
    }

    @Test
    void storedChunksAreVisibleThroughTerrain() {
        ColumnStore store = new ColumnStore(100, System::currentTimeMillis);
        store.put(WORLD, floorChunk(0, 0));
        assertTrue(store.contains(WORLD, 0, 0));
        assertEquals(65, Surfaces.y(store.terrain(WORLD).surfaces(3, 7)[0]));
        assertNull(store.terrain(WORLD).surfaces(16, 0));
        assertNull(store.terrain(OTHER).surfaces(3, 7));
    }

    @Test
    void negativeCoordinatesMapToTheRightChunk() {
        ColumnStore store = new ColumnStore(100, System::currentTimeMillis);
        store.put(WORLD, floorChunk(-1, -1));
        assertEquals(1, store.terrain(WORLD).surfaces(-1, -16).length);
        assertNull(store.terrain(WORLD).surfaces(-17, -1));
        assertNull(store.terrain(WORLD).surfaces(0, -1));
    }

    @Test
    void removeAndClear() {
        ColumnStore store = new ColumnStore(100, System::currentTimeMillis);
        store.put(WORLD, floorChunk(0, 0));
        store.put(WORLD, floorChunk(1, 0));
        store.remove(WORLD, 0, 0);
        assertFalse(store.contains(WORLD, 0, 0));
        assertEquals(1, store.size());
        store.clear();
        assertEquals(0, store.size());
    }

    @Test
    void trimsLeastRecentlyUsedBeyondTheLimit() {
        AtomicLong clock = new AtomicLong();
        ColumnStore store = new ColumnStore(10, clock::get);
        for (int i = 0; i < 10; i++) {
            clock.set(i);
            store.put(WORLD, floorChunk(i, 0));
        }
        clock.set(100);
        store.terrain(WORLD).surfaces(0, 0);
        clock.set(101);
        store.put(WORLD, floorChunk(10, 0));
        assertEquals(9, store.size());
        assertTrue(store.contains(WORLD, 0, 0), "recently read chunk must survive");
        assertTrue(store.contains(WORLD, 10, 0), "newest chunk must survive");
        assertFalse(store.contains(WORLD, 1, 0), "least recently used chunk must go");
    }
}
```

`WalkStatsTest.java`:

```java
package com.minecraftcitiesnetwork.directions.walk;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WalkStatsTest {

    @Test
    void percentilesOverRecordedSearches() {
        WalkStats stats = new WalkStats(() -> 0L);
        for (int ms = 1; ms <= 100; ms++) {
            stats.record(SearchResult.failed(SearchResult.Status.NO_ROUTE, 0, ms * 1_000_000L));
        }
        assertEquals(50_000_000L, stats.percentileNanos(0.50));
        assertEquals(95_000_000L, stats.percentileNanos(0.95));
        assertEquals(100, stats.results(SearchResult.Status.NO_ROUTE));
    }

    @Test
    void rateCountsTheLastMinuteOnly() {
        AtomicLong clock = new AtomicLong();
        WalkStats stats = new WalkStats(clock::get);
        for (int i = 0; i < 30; i++) {
            stats.record(SearchResult.failed(SearchResult.Status.BUDGET, 0, 1));
        }
        assertEquals(0.5, stats.searchesPerSecond(), 1e-9);
        clock.set(61_000);
        assertEquals(0.0, stats.searchesPerSecond(), 1e-9);
    }

    @Test
    void worstPumpTickCoversTheLastMinute() {
        AtomicLong clock = new AtomicLong();
        WalkStats stats = new WalkStats(clock::get);
        stats.recordPumpTick(400_000);
        clock.set(61_000);
        stats.recordPumpTick(100_000);
        assertEquals(400_000, stats.worstPumpNanos());
        clock.set(122_000);
        stats.recordPumpTick(50_000);
        assertEquals(100_000, stats.worstPumpNanos());
    }

    @Test
    void hitRateAndDescription() {
        WalkStats stats = new WalkStats(() -> 0L);
        stats.cacheHit();
        stats.cacheHit();
        stats.cacheHit();
        stats.cacheMiss();
        assertEquals(0.75, stats.hitRate(), 1e-9);
        assertTrue(stats.describe(812).stream().anyMatch(line -> line.contains("812 chunks") && line.contains("75%")));
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew test --tests '*ColumnStoreTest' --tests '*WalkStatsTest'`
Expected: compilation FAILS ("cannot find symbol: class ColumnStore").

- [ ] **Step 3: Implement**

`ColumnStore.java`:

```java
package com.minecraftcitiesnetwork.directions.walk;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Decoded chunks for every world, shared by all players. Writers (decode threads) and readers
 * (searches, the main thread) may run concurrently. Holds at most {@code maxChunks}; beyond that
 * the least recently read are dropped, down to 90% of the limit.
 */
public final class ColumnStore {
    private record Key(UUID world, int x, int z) {
    }

    private static final class Entry {
        final ColumnChunk chunk;
        volatile long lastAccess;

        Entry(ColumnChunk chunk, long now) {
            this.chunk = chunk;
            this.lastAccess = now;
        }
    }

    private record Aged(Key key, Entry entry, long lastAccess) {
    }

    private final ConcurrentHashMap<Key, Entry> entries = new ConcurrentHashMap<>();
    private final int maxChunks;
    private final LongSupplier clock;

    public ColumnStore(int maxChunks, LongSupplier millisClock) {
        this.maxChunks = maxChunks;
        this.clock = millisClock;
    }

    public void put(UUID world, ColumnChunk chunk) {
        entries.put(new Key(world, chunk.chunkX(), chunk.chunkZ()), new Entry(chunk, clock.getAsLong()));
        if (entries.size() > maxChunks) {
            trim();
        }
    }

    public boolean contains(UUID world, int chunkX, int chunkZ) {
        return entries.containsKey(new Key(world, chunkX, chunkZ));
    }

    public void remove(UUID world, int chunkX, int chunkZ) {
        entries.remove(new Key(world, chunkX, chunkZ));
    }

    public void clear() {
        entries.clear();
    }

    public int size() {
        return entries.size();
    }

    /** A view for one thread. It remembers the last chunk it read, so make a new one per search. */
    public Terrain terrain(UUID world) {
        return new View(world);
    }

    private synchronized void trim() {
        if (entries.size() <= maxChunks) {
            return;
        }
        List<Aged> all = new ArrayList<>(entries.size());
        entries.forEach((key, entry) -> all.add(new Aged(key, entry, entry.lastAccess)));
        all.sort(Comparator.comparingLong(Aged::lastAccess));
        int target = maxChunks * 9 / 10;
        for (int i = 0; i < all.size() - target; i++) {
            entries.remove(all.get(i).key(), all.get(i).entry());
        }
    }

    private final class View implements Terrain {
        private final UUID world;
        private int lastX = Integer.MIN_VALUE;
        private int lastZ = Integer.MIN_VALUE;
        private ColumnChunk last;

        View(UUID world) {
            this.world = world;
        }

        @Override
        public int[] surfaces(int x, int z) {
            int cx = x >> 4;
            int cz = z >> 4;
            if (cx != lastX || cz != lastZ) {
                Entry entry = entries.get(new Key(world, cx, cz));
                lastX = cx;
                lastZ = cz;
                last = entry == null ? null : entry.chunk;
                if (entry != null) {
                    entry.lastAccess = clock.getAsLong();
                }
            }
            return last == null ? null : last.column(x & 15, z & 15);
        }
    }
}
```

`WalkStats.java`:

```java
package com.minecraftcitiesnetwork.directions.walk;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.function.LongSupplier;

/** Counters and timings for {@code /directions debug walk}. Thread-safe. */
public final class WalkStats {
    private static final int SAMPLES = 512;
    private static final long MINUTE = 60_000;

    private final LongSupplier millis;
    private final long[] durations = new long[SAMPLES];
    private final EnumMap<SearchResult.Status, Long> results = new EnumMap<>(SearchResult.Status.class);
    private final ArrayDeque<Long> recent = new ArrayDeque<>();
    private int stored;
    private int next;
    private long hits;
    private long misses;
    private long worstPump;
    private long previousWorstPump;
    private long pumpWindowStart;

    public WalkStats(LongSupplier millisClock) {
        this.millis = millisClock;
        this.pumpWindowStart = millisClock.getAsLong();
    }

    public synchronized void record(SearchResult result) {
        durations[next] = result.nanos();
        next = (next + 1) % SAMPLES;
        stored = Math.min(stored + 1, SAMPLES);
        results.merge(result.status(), 1L, Long::sum);
        long now = millis.getAsLong();
        recent.addLast(now);
        prune(now);
    }

    public synchronized void cacheHit() {
        hits++;
    }

    public synchronized void cacheMiss() {
        misses++;
    }

    public synchronized void recordPumpTick(long nanos) {
        long now = millis.getAsLong();
        if (now - pumpWindowStart >= MINUTE) {
            previousWorstPump = worstPump;
            worstPump = 0;
            pumpWindowStart = now;
        }
        worstPump = Math.max(worstPump, nanos);
    }

    public synchronized double searchesPerSecond() {
        prune(millis.getAsLong());
        return recent.size() / 60.0;
    }

    public synchronized long percentileNanos(double p) {
        if (stored == 0) {
            return 0;
        }
        long[] copy = Arrays.copyOf(durations, stored);
        Arrays.sort(copy);
        int index = (int) Math.ceil(p * stored) - 1;
        return copy[Math.max(0, Math.min(stored - 1, index))];
    }

    public synchronized long results(SearchResult.Status status) {
        return results.getOrDefault(status, 0L);
    }

    public synchronized double hitRate() {
        long total = hits + misses;
        return total == 0 ? 1.0 : (double) hits / total;
    }

    /** The slowest chunk-capture tick over the last one to two minutes. */
    public synchronized long worstPumpNanos() {
        return Math.max(worstPump, previousWorstPump);
    }

    public synchronized List<String> describe(int cachedChunks) {
        StringBuilder counts = new StringBuilder("Results:");
        for (SearchResult.Status status : SearchResult.Status.values()) {
            counts.append(' ').append(status).append(' ').append(results(status));
        }
        return List.of(
                String.format(Locale.ROOT, "Searches: %.2f/s over the last minute", searchesPerSecond()),
                String.format(Locale.ROOT, "Search time: p50 %.1f ms, p95 %.1f ms (last %d)",
                        percentileNanos(0.50) / 1e6, percentileNanos(0.95) / 1e6, stored),
                counts.toString(),
                String.format(Locale.ROOT, "Chunk cache: %d chunks, hit rate %.0f%%", cachedChunks, hitRate() * 100),
                String.format(Locale.ROOT, "Worst capture tick (last minute): %.2f ms", worstPumpNanos() / 1e6));
    }

    private void prune(long now) {
        while (!recent.isEmpty() && now - recent.peekFirst() > MINUTE) {
            recent.pollFirst();
        }
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew test --tests '*ColumnStoreTest' --tests '*WalkStatsTest'`
Expected: PASS (8 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/minecraftcitiesnetwork/directions/walk src/test/java/com/minecraftcitiesnetwork/directions/walk
git commit -m "feat(walk): shared LRU column store and walking stats"
```

---

### Task 9: Legs in navigation, and a dev server

The trip model moves from waypoints to legs. Behaviour is unchanged; this task only makes walk and ride legs distinguishable. It also adds `runServer` so this and later tasks can be checked in a real server.

**Files:**
- Create: `src/main/java/com/minecraftcitiesnetwork/directions/navigation/LegKind.java`
- Create: `src/main/java/com/minecraftcitiesnetwork/directions/navigation/Leg.java`
- Create: `src/main/java/com/minecraftcitiesnetwork/directions/navigation/LegPlanner.java`
- Modify: `src/main/java/com/minecraftcitiesnetwork/directions/navigation/Session.java` (whole file below)
- Modify: `src/main/java/com/minecraftcitiesnetwork/directions/navigation/NavigationService.java` (start methods)
- Modify: `src/main/java/com/minecraftcitiesnetwork/directions/command/DirectionsCommand.java`
- Modify: `src/main/java/com/minecraftcitiesnetwork/directions/command/GpsCommand.java:77`
- Modify: `src/main/java/com/minecraftcitiesnetwork/directions/command/CompassCommand.java:74`
- Modify: `build.gradle.kts`, `.gitignore`
- Test: `src/test/java/com/minecraftcitiesnetwork/directions/navigation/LegPlannerTest.java`

**Interfaces:**
- Consumes: `Waypoint` (existing), `TransitGraph.isTransitHop(String, String)` (existing).
- Produces:
  - `enum LegKind { WALK, RIDE }`; `record Leg(Waypoint target, LegKind kind)` with `static walk(Waypoint)`, `static ride(Waypoint)`
  - `LegPlanner.plan(List<String> pathNodes, String startNode, String destNode, Waypoint finalWaypoint, BiPredicate<String, String> isTransitHop) → List<Leg>`. Its targets are identical to the old `DirectionsCommand.buildWaypoints`.
  - `NavigationService.startNavigation(Player, List<Leg>)`, `NavigationService.startGpsNavigation(Player, Waypoint)`
  - `Session(DirectionsPlugin, UUID, List<Leg>, int, List<ArmorStand>, BossBar, boolean)` with `currentLeg()`. Waypoint accessors are unchanged.

- [ ] **Step 1: Write the failing test**

`LegPlannerTest.java`:

```java
package com.minecraftcitiesnetwork.directions.navigation;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.function.BiPredicate;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LegPlannerTest {
    private static final String START = "__start__";
    private static final String DEST = "__dest__";
    private static final Waypoint.Coordinates HOME = new Waypoint.Coordinates(500, 64, 500, false);

    private static BiPredicate<String, String> rides(String... pairs) {
        Set<String> set = Set.of(pairs);
        return (a, b) -> set.contains(a + ">" + b);
    }

    private static Leg walk(String stop) {
        return Leg.walk(new Waypoint.Region(stop));
    }

    private static Leg ride(String stop) {
        return Leg.ride(new Waypoint.Region(stop));
    }

    @Test
    void walkRideRideWalk() {
        List<Leg> legs = LegPlanner.plan(List.of(START, "a", "b", "c", DEST), START, DEST, HOME, rides("a>b", "b>c"));
        assertEquals(List.of(walk("a"), ride("b"), ride("c"), Leg.walk(HOME)), legs);
    }

    @Test
    void walkingTransferBetweenLinesIsAWalk() {
        List<Leg> legs = LegPlanner.plan(List.of(START, "a", "b", "x", "y", DEST), START, DEST, HOME, rides("a>b", "x>y"));
        assertEquals(List.of(walk("a"), ride("b"), walk("x"), ride("y"), Leg.walk(HOME)), legs);
    }

    @Test
    void destinationThatIsTheLastStopAddsNoExtraLeg() {
        Waypoint.Region last = new Waypoint.Region("c");
        List<Leg> legs = LegPlanner.plan(List.of(START, "a", "c", DEST), START, DEST, last, rides("a>c"));
        assertEquals(List.of(walk("a"), ride("c")), legs);
    }

    @Test
    void walkOnlyRouteIsOneWalk() {
        assertEquals(List.of(Leg.walk(HOME)), LegPlanner.plan(List.of(START, DEST), START, DEST, HOME, rides()));
    }

    @Test
    void theFirstLegIsAlwaysAWalk() {
        List<Leg> legs = LegPlanner.plan(List.of(START, "a", DEST), START, DEST, HOME, (x, y) -> true);
        assertEquals(List.of(walk("a"), Leg.walk(HOME)), legs);
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew test --tests '*LegPlannerTest'`
Expected: compilation FAILS ("cannot find symbol: class Leg").

- [ ] **Step 3: Implement the leg types**

`LegKind.java`:

```java
package com.minecraftcitiesnetwork.directions.navigation;

/** How the player travels a leg. */
public enum LegKind {
    /** On foot: gets a walking trail. */
    WALK,
    /** On a line, stop to stop by teleport: no trail. */
    RIDE
}
```

`Leg.java`:

```java
package com.minecraftcitiesnetwork.directions.navigation;

/** One part of a trip: where it ends and how it is travelled. */
public record Leg(Waypoint target, LegKind kind) {
    public static Leg walk(Waypoint target) {
        return new Leg(target, LegKind.WALK);
    }

    public static Leg ride(Waypoint target) {
        return new Leg(target, LegKind.RIDE);
    }
}
```

`LegPlanner.java`:

```java
package com.minecraftcitiesnetwork.directions.navigation;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiPredicate;

/** Turns a routed node list into legs, marking which are rides and which are walks. */
public final class LegPlanner {
    private LegPlanner() {
    }

    /**
     * @param pathNodes     route nodes, starting with {@code startNode} and usually ending with {@code destNode}
     * @param isTransitHop  whether two consecutive stops are connected by a line
     */
    public static List<Leg> plan(List<String> pathNodes, String startNode, String destNode,
                                 Waypoint finalWaypoint, BiPredicate<String, String> isTransitHop) {
        List<Leg> legs = new ArrayList<>();
        for (int i = 0; i + 1 < pathNodes.size(); i++) {
            String from = pathNodes.get(i);
            String to = pathNodes.get(i + 1);
            if (to.equals(destNode)) {
                continue;
            }
            boolean ride = !from.equals(startNode) && isTransitHop.test(from, to);
            Waypoint target = new Waypoint.Region(to);
            legs.add(ride ? Leg.ride(target) : Leg.walk(target));
        }
        if (legs.isEmpty() || !legs.getLast().target().equals(finalWaypoint)) {
            legs.add(Leg.walk(finalWaypoint));
        }
        return legs;
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew test --tests '*LegPlannerTest'`
Expected: PASS (5 tests).

- [ ] **Step 5: Switch `Session` to legs**

Replace the whole of `navigation/Session.java` with:

```java
package com.minecraftcitiesnetwork.directions.navigation;

import com.minecraftcitiesnetwork.directions.DirectionsPlugin;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.ArmorStand;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.UUID;

final class Session implements SessionView {
    private final DirectionsPlugin plugin;
    private final UUID playerId;
    private final List<Leg> legs;
    private int index;
    private final List<ArmorStand> arrowStands;
    private final BossBar bossBar;
    private final boolean gps;
    private double legStartDistance;
    private int upcomingHintSentForIndex;

    Session(DirectionsPlugin plugin, UUID playerId, List<Leg> legs, int index, List<ArmorStand> arrowStands, BossBar bossBar, boolean gps) {
        this.plugin = plugin;
        this.playerId = playerId;
        this.legs = List.copyOf(legs);
        this.index = index;
        this.arrowStands = arrowStands;
        this.bossBar = bossBar;
        this.gps = gps;
        this.legStartDistance = -1.0;
        this.upcomingHintSentForIndex = -1;
    }

    boolean gps() {
        return gps;
    }

    UUID playerId() {
        return playerId;
    }

    DirectionsPlugin plugin() {
        return plugin;
    }

    List<ArmorStand> arrowStands() {
        return arrowStands;
    }

    BossBar bossBar() {
        return bossBar;
    }

    double legStartDistance() {
        return legStartDistance;
    }

    void setLegStartDistance(double legStartDistance) {
        this.legStartDistance = legStartDistance;
    }

    void resetLegProgress() {
        this.legStartDistance = -1.0;
    }

    int index() {
        return index;
    }

    int upcomingHintSentForIndex() {
        return upcomingHintSentForIndex;
    }

    void setUpcomingHintSentForIndex(int idx) {
        this.upcomingHintSentForIndex = idx;
    }

    @Nullable Leg currentLeg() {
        return legAt(index);
    }

    @Nullable Waypoint currentWaypoint() {
        return targetAt(index);
    }

    @Nullable Waypoint nextWaypoint() {
        return targetAt(index + 1);
    }

    @Nullable Waypoint nextWaypointAfterNext() {
        return targetAt(index + 2);
    }

    @Override
    public @Nullable Waypoint previousWaypoint() {
        return targetAt(index - 1);
    }

    void advance() {
        index++;
    }

    private @Nullable Leg legAt(int i) {
        if (i < 0 || i >= legs.size()) {
            return null;
        }
        return legs.get(i);
    }

    private @Nullable Waypoint targetAt(int i) {
        Leg leg = legAt(i);
        return leg == null ? null : leg.target();
    }
}
```

- [ ] **Step 6: Change the navigation entry points**

In `navigation/NavigationService.java`, replace:

```java
    public void startNavigation(Player player, List<Waypoint> waypoints) {
        startNavigation(player, waypoints, false);
    }

    public void startGpsNavigation(Player player, List<Waypoint> waypoints) {
        startNavigation(player, waypoints, true);
    }

    private void startNavigation(Player player, List<Waypoint> waypoints, boolean gps) {
        stopNavigation(player);
        if (waypoints.isEmpty()) {
            return;
        }
```

with:

```java
    public void startNavigation(Player player, List<Leg> legs) {
        startNavigation(player, legs, false);
    }

    /** Direct navigation (/gps, /compass, fallbacks): one walking leg to the destination. */
    public void startGpsNavigation(Player player, Waypoint destination) {
        startNavigation(player, List.of(Leg.walk(destination)), true);
    }

    private void startNavigation(Player player, List<Leg> legs, boolean gps) {
        stopNavigation(player);
        if (legs.isEmpty()) {
            return;
        }
```

In the same method, change `new Session(plugin, player.getUniqueId(), waypoints, 0, arrowStands, bossBar, gps)` to `new Session(plugin, player.getUniqueId(), legs, 0, arrowStands, bossBar, gps)`.

- [ ] **Step 7: Update the callers**

In `command/DirectionsCommand.java`:
- Add imports `com.minecraftcitiesnetwork.directions.navigation.Leg` and `com.minecraftcitiesnetwork.directions.navigation.LegPlanner`.
- Replace `List<Waypoint> navWaypoints = buildWaypoints(route.pathNodes(), finalWaypoint);` with `List<Leg> legs = LegPlanner.plan(route.pathNodes(), START_NODE, DEST_NODE, finalWaypoint, graph::isTransitHop);`
- Replace `plugin.getNavigationService().startNavigation(player, List.of(finalWaypoint));` with `plugin.getNavigationService().startNavigation(player, List.of(Leg.walk(finalWaypoint)));`
- Replace `plugin.getNavigationService().startNavigation(player, navWaypoints);` with `plugin.getNavigationService().startNavigation(player, legs);`
- In `startGpsFallback`, replace `startGpsNavigation(player, List.of(finalWaypoint))` with `startGpsNavigation(player, finalWaypoint)`.
- Delete the now-unused `private static List<Waypoint> buildWaypoints(...)` method.

In `command/GpsCommand.java` and `command/CompassCommand.java`, replace `plugin.getNavigationService().startGpsNavigation(player, List.of(destination));` with `plugin.getNavigationService().startGpsNavigation(player, destination);`. Remove the `java.util.List` import if the compiler reports it unused.

- [ ] **Step 8: Add the dev server**

In `build.gradle.kts`, add the plugin and the task configuration:

```kotlin
plugins {
    java
    id("com.gradleup.shadow") version "8.3.5"
    id("xyz.jpenilla.run-paper") version "2.3.1"
}
```

and inside `tasks { ... }`:

```kotlin
    // `./gradlew runServer` starts Paper 1.21.8 in run/ with WorldGuard and WorldEdit.
    runServer {
        minecraftVersion("1.21.8")
        runDirectory.set(file("run"))
        downloadPlugins {
            // Direct Bukkit builds: Modrinth publishes Fabric jars under the same version numbers.
            url("https://cdn.modrinth.com/data/1u6JkXh5/versions/Jk1z2u7n/worldedit-bukkit-7.3.16.jar")
            url("https://cdn.modrinth.com/data/DKY9btbd/versions/PO4MKx7e/worldguard-bukkit-7.0.14-dist.jar")
        }
    }
```

Append to `.gitignore`:

```
# Dev server (./gradlew runServer)
run/
```

- [ ] **Step 9: Verify**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew build`
Expected: BUILD SUCCESSFUL, all tests pass.

Manual check on the dev server: run `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew runServer`. Accept the EULA in `run/eula.txt` on first start and restart. Join on `localhost`. Use WorldEdit (`//wand`, then `//pos1`/`//pos2`) and `/rg define stop-a`, `/rg define stop-b`, `/rg define stop-c` to make three regions about 100 blocks apart. In `run/plugins/Directions/config.yml`, define line `test_line` with stops `[stop-a, stop-b]`. Run `/directions reload` and `/directions stop-c`. Expected: the same chat route, arrow and boss bar as on `main`. Also run `/gps stop-c`, then `/compass 0 0`, and check each navigates as before.

- [ ] **Step 10: Commit**

```bash
git add build.gradle.kts .gitignore src/main/java/com/minecraftcitiesnetwork/directions src/test/java/com/minecraftcitiesnetwork/directions/navigation
git commit -m "refactor(navigation): trips are legs that know whether they are walked or ridden"
```

---

### Task 10: Configuration

**Files:**
- Create: `src/main/java/com/minecraftcitiesnetwork/directions/walk/ParticleSpec.java`
- Create: `src/main/java/com/minecraftcitiesnetwork/directions/walk/WalkSettings.java`
- Create: `src/main/java/com/minecraftcitiesnetwork/directions/config/WalkSettingsParser.java`
- Modify: `src/main/java/com/minecraftcitiesnetwork/directions/config/ConfigLoader.java`
- Modify: `src/main/resources/config.yml`
- Modify: `src/test/java/com/minecraftcitiesnetwork/directions/config/LoadedDataDisplayNameTest.java`
- Test: `src/test/java/com/minecraftcitiesnetwork/directions/config/WalkSettingsParserTest.java`

**Interfaces:**
- Consumes: `WalkerProfile` (Task 3), `GuidePolicy.Tuning` (Task 7).
- Produces:
  - `record ParticleSpec(String type, String color, double size)`
  - `record WalkSettings(boolean enabled, int windowRadius, long timeBudgetMillis, int maxExpansions, int snapshotsPerTick, long captureWaitMillis, int cacheMaxChunks, int maxDrop, double waterCost, double climbCost, double doorCost, double extendDistance, double offRouteDistance, long replanCooldownMillis, int trailLength, int trailRefreshTicks, int pulseStep, double arrowLookAhead, ParticleSpec trailParticle, ParticleSpec pulseParticle, ParticleSpec endParticle)` with `DEFAULTS`, `walker()`, `tuning()`
  - `WalkSettingsParser.parse(ConfigLoader.WalkingPathsSection, Consumer<String> warn) → WalkSettings`
  - `ConfigLoader.LoadedData` gains a last component `WalkSettings walk`
  - `ConfigLoader.WalkingPathsSection`, `ConfigLoader.ParticlesSection`, `ConfigLoader.ParticleSection` (Configurate-mapped)

- [ ] **Step 1: Write the failing test**

`WalkSettingsParserTest.java`:

```java
package com.minecraftcitiesnetwork.directions.config;

import com.minecraftcitiesnetwork.directions.walk.WalkSettings;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WalkSettingsParserTest {

    @Test
    void defaultSectionParsesToTheDefaultsWithoutWarnings() {
        List<String> warnings = new ArrayList<>();
        WalkSettings settings = WalkSettingsParser.parse(new ConfigLoader.WalkingPathsSection(), warnings::add);
        assertEquals(WalkSettings.DEFAULTS, settings);
        assertTrue(warnings.isEmpty(), warnings.toString());
    }

    @Test
    void outOfRangeValuesFallBackWithAWarningEach() {
        ConfigLoader.WalkingPathsSection section = new ConfigLoader.WalkingPathsSection();
        section.windowRadius = 5000;
        section.waterCost = 0.5;
        section.maxDrop = 9;
        List<String> warnings = new ArrayList<>();
        WalkSettings settings = WalkSettingsParser.parse(section, warnings::add);
        assertEquals(80, settings.windowRadius());
        assertEquals(2.5, settings.waterCost());
        assertEquals(3, settings.maxDrop());
        assertEquals(3, warnings.size());
        assertTrue(warnings.get(0).contains("walking-paths.window-radius"), warnings.get(0));
    }

    @Test
    void disabledIsKept() {
        ConfigLoader.WalkingPathsSection section = new ConfigLoader.WalkingPathsSection();
        section.enabled = false;
        assertFalse(WalkSettingsParser.parse(section, w -> { }).enabled());
    }

    @Test
    void missingParticleFallsBackToTheDefault() {
        ConfigLoader.WalkingPathsSection section = new ConfigLoader.WalkingPathsSection();
        section.particles.trail = null;
        section.particles.pulse.type = " ";
        WalkSettings settings = WalkSettingsParser.parse(section, w -> { });
        assertEquals(WalkSettings.DEFAULTS.trailParticle(), settings.trailParticle());
        assertEquals(WalkSettings.DEFAULTS.pulseParticle(), settings.pulseParticle());
    }

    @Test
    void settingsProduceTheWalkerAndTuning() {
        WalkSettings d = WalkSettings.DEFAULTS;
        assertEquals(3, d.walker().maxDrop());
        assertEquals(48.0, d.tuning().extendDistance());
        assertEquals(2000L, d.tuning().cooldownMillis());
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew test --tests '*WalkSettingsParserTest'`
Expected: compilation FAILS ("cannot find symbol: class WalkSettings").

- [ ] **Step 3: Implement the settings types**

`ParticleSpec.java`:

```java
package com.minecraftcitiesnetwork.directions.walk;

/** A particle as configured: a Bukkit particle name, a {@code #RRGGBB} colour for dust, and a dust size. */
public record ParticleSpec(String type, String color, double size) {
}
```

`WalkSettings.java`:

```java
package com.minecraftcitiesnetwork.directions.walk;

/** Everything under {@code walking-paths:} in config.yml, validated. Immutable. */
public record WalkSettings(
        boolean enabled,
        int windowRadius,
        long timeBudgetMillis,
        int maxExpansions,
        int snapshotsPerTick,
        long captureWaitMillis,
        int cacheMaxChunks,
        int maxDrop,
        double waterCost,
        double climbCost,
        double doorCost,
        double extendDistance,
        double offRouteDistance,
        long replanCooldownMillis,
        int trailLength,
        int trailRefreshTicks,
        int pulseStep,
        double arrowLookAhead,
        ParticleSpec trailParticle,
        ParticleSpec pulseParticle,
        ParticleSpec endParticle
) {
    public static final WalkSettings DEFAULTS = new WalkSettings(
            true, 80, 50, 200_000, 6, 1500, 4096, 3, 2.5, 2.0, 1.5, 48, 6, 2000, 40, 10, 4, 6,
            new ParticleSpec("DUST", "#5BC0EB", 1.0),
            new ParticleSpec("DUST", "#FFFFFF", 1.4),
            new ParticleSpec("HAPPY_VILLAGER", "", 1.0));

    public WalkerProfile walker() {
        return new WalkerProfile(maxDrop, waterCost, climbCost, doorCost);
    }

    public GuidePolicy.Tuning tuning() {
        return new GuidePolicy.Tuning(extendDistance, offRouteDistance, replanCooldownMillis);
    }
}
```

`WalkSettingsParser.java`:

```java
package com.minecraftcitiesnetwork.directions.config;

import com.minecraftcitiesnetwork.directions.walk.ParticleSpec;
import com.minecraftcitiesnetwork.directions.walk.WalkSettings;

import java.util.Locale;
import java.util.function.Consumer;

/** Validates the {@code walking-paths} section: each bad value is reported and replaced by its default. */
public final class WalkSettingsParser {
    private static final String PREFIX = "walking-paths.";

    private WalkSettingsParser() {
    }

    public static WalkSettings parse(ConfigLoader.WalkingPathsSection s, Consumer<String> warn) {
        WalkSettings d = WalkSettings.DEFAULTS;
        ConfigLoader.ParticlesSection particles = s.particles == null ? new ConfigLoader.ParticlesSection() : s.particles;
        return new WalkSettings(
                s.enabled,
                intIn(warn, "window-radius", s.windowRadius, 16, 160, d.windowRadius()),
                longIn(warn, "time-budget-ms", s.timeBudgetMs, 5, 500, d.timeBudgetMillis()),
                intIn(warn, "max-expansions", s.maxExpansions, 1_000, 2_000_000, d.maxExpansions()),
                intIn(warn, "snapshots-per-tick", s.snapshotsPerTick, 1, 64, d.snapshotsPerTick()),
                longIn(warn, "capture-wait-ms", s.captureWaitMs, 0, 10_000, d.captureWaitMillis()),
                intIn(warn, "cache-max-chunks", s.cacheMaxChunks, 256, 65_536, d.cacheMaxChunks()),
                intIn(warn, "max-drop", s.maxDrop, 0, 5, d.maxDrop()),
                doubleIn(warn, "water-cost", s.waterCost, 1, 10, d.waterCost()),
                doubleIn(warn, "climb-cost", s.climbCost, 1, 10, d.climbCost()),
                doubleIn(warn, "door-cost", s.doorCost, 1, 10, d.doorCost()),
                doubleIn(warn, "extend-distance", s.extendDistance, 8, 160, d.extendDistance()),
                doubleIn(warn, "off-route-distance", s.offRouteDistance, 2, 32, d.offRouteDistance()),
                longIn(warn, "replan-cooldown-ms", s.replanCooldownMs, 250, 30_000, d.replanCooldownMillis()),
                intIn(warn, "trail-length", s.trailLength, 4, 200, d.trailLength()),
                intIn(warn, "trail-refresh-ticks", s.trailRefreshTicks, 2, 100, d.trailRefreshTicks()),
                intIn(warn, "pulse-step", s.pulseStep, 1, 50, d.pulseStep()),
                doubleIn(warn, "arrow-look-ahead", s.arrowLookAhead, 1, 32, d.arrowLookAhead()),
                particle(particles.trail, d.trailParticle()),
                particle(particles.pulse, d.pulseParticle()),
                particle(particles.end, d.endParticle()));
    }

    private static int intIn(Consumer<String> warn, String key, int value, int min, int max, int fallback) {
        if (value < min || value > max) {
            warn.accept(String.format(Locale.ROOT, "%s%s must be between %d and %d; using %d", PREFIX, key, min, max, fallback));
            return fallback;
        }
        return value;
    }

    private static long longIn(Consumer<String> warn, String key, long value, long min, long max, long fallback) {
        if (value < min || value > max) {
            warn.accept(String.format(Locale.ROOT, "%s%s must be between %d and %d; using %d", PREFIX, key, min, max, fallback));
            return fallback;
        }
        return value;
    }

    private static double doubleIn(Consumer<String> warn, String key, double value, double min, double max, double fallback) {
        if (!(value >= min && value <= max)) {
            warn.accept(String.format(Locale.ROOT, "%s%s must be between %s and %s; using %s", PREFIX, key, min, max, fallback));
            return fallback;
        }
        return value;
    }

    private static ParticleSpec particle(ConfigLoader.ParticleSection section, ParticleSpec fallback) {
        if (section == null || section.type == null || section.type.isBlank()) {
            return fallback;
        }
        return new ParticleSpec(section.type.trim(), section.color == null ? "" : section.color.trim(), section.size);
    }
}
```

- [ ] **Step 4: Wire it into `ConfigLoader`**

In `config/ConfigLoader.java`:

1. Add import `com.minecraftcitiesnetwork.directions.walk.WalkSettings`.
2. In `loadAndValidate()`, after the `walkingTransferPolicy` validation, add:

```java
            WalkSettings walk = WalkSettingsParser.parse(
                    root.walkingPaths == null ? new WalkingPathsSection() : root.walkingPaths,
                    message -> plugin.getLogger().warning(message));
```

3. Pass `walk` as the new last argument of `new LoadedData(...)`, after `stops`.
4. Add `WalkSettings walk` as the last component of `record LoadedData(...)`, after `Map<String, Stop> stopsById`.
5. In `MainConfig`, after `lines`, add:

```java
        @Setting("walking-paths")
        public WalkingPathsSection walkingPaths = new WalkingPathsSection();
```

6. Add these nested classes after `LineSection`:

```java
    @ConfigSerializable
    public static final class WalkingPathsSection {
        @Setting("enabled")
        public boolean enabled = true;
        @Setting("window-radius")
        public int windowRadius = 80;
        @Setting("time-budget-ms")
        public long timeBudgetMs = 50;
        @Setting("max-expansions")
        public int maxExpansions = 200_000;
        @Setting("snapshots-per-tick")
        public int snapshotsPerTick = 6;
        @Setting("capture-wait-ms")
        public long captureWaitMs = 1500;
        @Setting("cache-max-chunks")
        public int cacheMaxChunks = 4096;
        @Setting("max-drop")
        public int maxDrop = 3;
        @Setting("water-cost")
        public double waterCost = 2.5;
        @Setting("climb-cost")
        public double climbCost = 2.0;
        @Setting("door-cost")
        public double doorCost = 1.5;
        @Setting("extend-distance")
        public double extendDistance = 48;
        @Setting("off-route-distance")
        public double offRouteDistance = 6;
        @Setting("replan-cooldown-ms")
        public long replanCooldownMs = 2000;
        @Setting("trail-length")
        public int trailLength = 40;
        @Setting("trail-refresh-ticks")
        public int trailRefreshTicks = 10;
        @Setting("pulse-step")
        public int pulseStep = 4;
        @Setting("arrow-look-ahead")
        public double arrowLookAhead = 6;
        @Setting("particles")
        public ParticlesSection particles = new ParticlesSection();
    }

    @ConfigSerializable
    public static final class ParticlesSection {
        @Setting("trail")
        public ParticleSection trail = ParticleSection.of("DUST", "#5BC0EB", 1.0);
        @Setting("pulse")
        public ParticleSection pulse = ParticleSection.of("DUST", "#FFFFFF", 1.4);
        @Setting("end")
        public ParticleSection end = ParticleSection.of("HAPPY_VILLAGER", "", 1.0);
    }

    @ConfigSerializable
    public static final class ParticleSection {
        @Setting("type")
        public String type = "DUST";
        @Setting("color")
        public String color = "";
        @Setting("size")
        public double size = 1.0;

        static ParticleSection of(String type, String color, double size) {
            ParticleSection section = new ParticleSection();
            section.type = type;
            section.color = color;
            section.size = size;
            return section;
        }
    }
```

7. In `LoadedDataDisplayNameTest`, add `WalkSettings.DEFAULTS` as the last constructor argument in both tests, and import `com.minecraftcitiesnetwork.directions.walk.WalkSettings`.

- [ ] **Step 5: Document the section in `config.yml`**

In `src/main/resources/config.yml`, insert before the `# Demo network:` comment:

```yaml
# Walking trails: a particle trail along the real walking route for every walking part of a trip
# (to the first stop, between stops, to the destination, /gps and /compass). Ride legs get none.
# Routes are computed from loaded terrain around the player only; chunks are never loaded for this.
walking-paths:
  enabled: true
  # Search window: blocks each way from the player (the window is twice this across). Capped by
  # the server view distance. 16-160.
  window-radius: 80
  time-budget-ms: 50
  max-expansions: 200000
  # Chunk capture: snapshots taken on the main thread per tick, how long a search waits for the
  # chunks around a player to be captured, and how many decoded chunks to keep.
  snapshots-per-tick: 6
  capture-wait-ms: 1500
  cache-max-chunks: 4096
  # Walker: longest drop (0-5) and how much slower water, ladders and doors are (>= 1).
  max-drop: 3
  water-cost: 2.5
  climb-cost: 2.0
  door-cost: 1.5
  # Re-planning: extend a partial route this many blocks before its end; re-plan when this far off
  # the route; at most one re-plan per cooldown (except when the route gets blocked).
  extend-distance: 48
  off-route-distance: 6
  replan-cooldown-ms: 2000
  # Trail: cells drawn ahead of the player, redraw interval, how fast the bright pulse travels,
  # and how far ahead along the route the arrow points.
  trail-length: 40
  trail-refresh-ticks: 10
  pulse-step: 4
  arrow-look-ahead: 6
  particles:
    trail: { type: DUST, color: "#5BC0EB", size: 1.0 }
    pulse: { type: DUST, color: "#FFFFFF", size: 1.4 }
    end: { type: HAPPY_VILLAGER }
```

- [ ] **Step 6: Run the tests to verify they pass**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew test`
Expected: PASS, including `WalkSettingsParserTest` (5 tests) and both `LoadedDataDisplayNameTest` tests.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/minecraftcitiesnetwork/directions src/main/resources/config.yml src/test/java/com/minecraftcitiesnetwork/directions/config
git commit -m "feat(config): walking-paths settings with validation and defaults"
```

---

### Task 11: Terrain capture, the search service and the debug command

**Files:**
- Create: `src/main/java/com/minecraftcitiesnetwork/directions/walk/bukkit/MaterialKinds.java`
- Create: `src/main/java/com/minecraftcitiesnetwork/directions/walk/bukkit/SnapshotBlocks.java`
- Create: `src/main/java/com/minecraftcitiesnetwork/directions/walk/bukkit/ChunkColumnCache.java`
- Create: `src/main/java/com/minecraftcitiesnetwork/directions/walk/bukkit/ChunkChangeListener.java`
- Create: `src/main/java/com/minecraftcitiesnetwork/directions/walk/bukkit/WalkSearchService.java`
- Modify: `src/main/java/com/minecraftcitiesnetwork/directions/DirectionsPlugin.java` (whole file below)
- Modify: `src/main/java/com/minecraftcitiesnetwork/directions/command/DirectionsCommand.java` (add `debugWalk`)
- Modify: `src/main/java/com/minecraftcitiesnetwork/directions/command/DirectionsBrigadierRegistrar.java`
- Modify: `src/main/resources/lang.yml`, `src/main/resources/paper-plugin.yml`

**Interfaces:**
- Consumes: `BlockKinds`, `ChunkBlocks`, `ColumnDecoder`, `ColumnStore`, `WalkStats`, `WindowSearch`, `WalkRules`, `SearchRequest`, `SearchResult`, `Window`, `Goal`, `Cell`, `Terrain`, `WalkSettings` (Tasks 1–10).
- Produces:
  - `WalkSearchService(JavaPlugin, WalkSettings)` with `start()`, `shutdown()`, `settings()`, `stats()`, `cachedChunks()`, `Terrain terrain(World)` (a fresh view; main thread), and `CompletableFuture<SearchResult> plan(World world, double x, double y, double z, Goal goal, List<Cell> spentExits)`. `plan` is called on the main thread, and its future always completes on the main thread.
  - `DirectionsPlugin.getWalkService()` is `@Nullable`: null when walking paths are disabled.
  - Command `/directions debug walk` (permission `directions.debug`).

This shell has no unit seam. The logic it calls is tested in Tasks 1–8, and the shell is verified on the dev server.

- [ ] **Step 1: Material lookup and snapshot adapter**

`MaterialKinds.java`:

```java
package com.minecraftcitiesnetwork.directions.walk.bukkit;

import com.minecraftcitiesnetwork.directions.walk.BlockKind;
import com.minecraftcitiesnetwork.directions.walk.BlockKinds;
import org.bukkit.Material;

/** {@link BlockKinds} memoised per material ordinal. Immutable after construction; thread-safe. */
public final class MaterialKinds {
    private final BlockKind[] byOrdinal;

    @SuppressWarnings("deprecation")
    public MaterialKinds() {
        Material[] all = Material.values();
        byOrdinal = new BlockKind[all.length];
        for (Material m : all) {
            byOrdinal[m.ordinal()] = m.isLegacy() || !m.isBlock()
                    ? BlockKind.OPEN
                    : BlockKinds.classify(m.name(), m.isSolid());
        }
    }

    public BlockKind of(Material material) {
        return byOrdinal[material.ordinal()];
    }
}
```

`SnapshotBlocks.java`:

```java
package com.minecraftcitiesnetwork.directions.walk.bukkit;

import com.minecraftcitiesnetwork.directions.walk.BlockKind;
import com.minecraftcitiesnetwork.directions.walk.ChunkBlocks;
import org.bukkit.ChunkSnapshot;

/** A chunk snapshot seen as block kinds. Safe to read off the main thread. */
final class SnapshotBlocks implements ChunkBlocks {
    private final ChunkSnapshot snapshot;
    private final MaterialKinds kinds;
    private final int minY;
    private final int maxY;

    SnapshotBlocks(ChunkSnapshot snapshot, MaterialKinds kinds, int minY, int maxY) {
        this.snapshot = snapshot;
        this.kinds = kinds;
        this.minY = minY;
        this.maxY = maxY;
    }

    @Override
    public int minY() {
        return minY;
    }

    @Override
    public int maxY() {
        return maxY;
    }

    @Override
    public BlockKind kind(int localX, int y, int localZ) {
        return kinds.of(snapshot.getBlockType(localX, y, localZ));
    }
}
```

- [ ] **Step 2: The chunk cache and its listener**

`ChunkColumnCache.java`:

```java
package com.minecraftcitiesnetwork.directions.walk.bukkit;

import com.minecraftcitiesnetwork.directions.walk.ColumnDecoder;
import com.minecraftcitiesnetwork.directions.walk.ColumnStore;
import com.minecraftcitiesnetwork.directions.walk.WalkSettings;
import com.minecraftcitiesnetwork.directions.walk.WalkStats;
import com.minecraftcitiesnetwork.directions.walk.Window;
import org.bukkit.Bukkit;
import org.bukkit.ChunkSnapshot;
import org.bukkit.World;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * Keeps {@link ColumnStore} filled with the loaded chunks searches need. Snapshots are taken on the
 * main thread, at most {@code snapshots-per-tick} per tick, and decoded on the search pool. Never
 * loads a chunk. Changed chunks are re-captured at most once per second.
 */
public final class ChunkColumnCache {
    private static final long RECAPTURE_INTERVAL_MILLIS = 1000;

    record ChunkKey(UUID world, int x, int z) {
    }

    private final JavaPlugin plugin;
    private final ColumnStore store;
    private final MaterialKinds kinds;
    private final Executor decoder;
    private final WalkSettings settings;
    private final WalkStats stats;
    private final ArrayDeque<ChunkKey> queue = new ArrayDeque<>();
    private final Set<ChunkKey> dirty = new HashSet<>();
    private final Map<ChunkKey, CompletableFuture<Void>> pending = new ConcurrentHashMap<>();
    private final Map<ChunkKey, Long> capturedAt = new ConcurrentHashMap<>();
    private BukkitTask task;

    ChunkColumnCache(JavaPlugin plugin, ColumnStore store, MaterialKinds kinds, Executor decoder,
                     WalkSettings settings, WalkStats stats) {
        this.plugin = plugin;
        this.store = store;
        this.kinds = kinds;
        this.decoder = decoder;
        this.settings = settings;
        this.stats = stats;
    }

    void start() {
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::pump, 1L, 1L);
    }

    void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        queue.clear();
        dirty.clear();
        pending.values().forEach(f -> f.complete(null));
        pending.clear();
        capturedAt.clear();
        store.clear();
    }

    /**
     * Main thread. Queues every loaded, uncaptured chunk under the window. Completes when they are
     * all captured, or after {@code capture-wait-ms}, whichever comes first.
     */
    CompletableFuture<Void> capture(World world, Window window) {
        UUID id = world.getUID();
        List<CompletableFuture<Void>> waits = new ArrayList<>();
        for (int cx = window.minX() >> 4; cx <= window.maxX() >> 4; cx++) {
            for (int cz = window.minZ() >> 4; cz <= window.maxZ() >> 4; cz++) {
                if (store.contains(id, cx, cz)) {
                    stats.cacheHit();
                    continue;
                }
                if (!world.isChunkLoaded(cx, cz)) {
                    continue;
                }
                stats.cacheMiss();
                waits.add(enqueue(new ChunkKey(id, cx, cz)));
            }
        }
        if (waits.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        return CompletableFuture.allOf(waits.toArray(new CompletableFuture[0]))
                .completeOnTimeout(null, settings.captureWaitMillis(), TimeUnit.MILLISECONDS);
    }

    /** Main thread. A block in this chunk changed. */
    void markDirty(World world, int chunkX, int chunkZ) {
        dirty.add(new ChunkKey(world.getUID(), chunkX, chunkZ));
    }

    /** Main thread. The chunk unloaded. */
    void evict(World world, int chunkX, int chunkZ) {
        ChunkKey key = new ChunkKey(world.getUID(), chunkX, chunkZ);
        store.remove(key.world(), chunkX, chunkZ);
        capturedAt.remove(key);
        dirty.remove(key);
    }

    private CompletableFuture<Void> enqueue(ChunkKey key) {
        return pending.computeIfAbsent(key, k -> {
            queue.add(k);
            return new CompletableFuture<>();
        });
    }

    private void pump() {
        long started = System.nanoTime();
        long now = System.currentTimeMillis();
        for (Iterator<ChunkKey> it = dirty.iterator(); it.hasNext(); ) {
            ChunkKey key = it.next();
            Long at = capturedAt.get(key);
            if (at != null && now - at < RECAPTURE_INTERVAL_MILLIS) {
                continue;
            }
            it.remove();
            if (store.contains(key.world(), key.x(), key.z())) {
                enqueue(key);
            }
        }
        int taken = 0;
        while (taken < settings.snapshotsPerTick() && !queue.isEmpty()) {
            ChunkKey key = queue.poll();
            World world = Bukkit.getWorld(key.world());
            if (world == null || !world.isChunkLoaded(key.x(), key.z())) {
                finish(key);
                continue;
            }
            ChunkSnapshot snapshot = world.getChunkAt(key.x(), key.z()).getChunkSnapshot(false, false, false);
            int minY = world.getMinHeight();
            int maxY = world.getMaxHeight();
            taken++;
            try {
                decoder.execute(() -> decode(key, snapshot, minY, maxY));
            } catch (RejectedExecutionException e) {
                finish(key);
            }
        }
        stats.recordPumpTick(System.nanoTime() - started);
    }

    private void decode(ChunkKey key, ChunkSnapshot snapshot, int minY, int maxY) {
        try {
            store.put(key.world(), ColumnDecoder.decode(key.x(), key.z(), new SnapshotBlocks(snapshot, kinds, minY, maxY)));
            capturedAt.put(key, System.currentTimeMillis());
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to decode chunk " + key, e);
        } finally {
            finish(key);
        }
    }

    private void finish(ChunkKey key) {
        CompletableFuture<Void> waiting = pending.remove(key);
        if (waiting != null) {
            waiting.complete(null);
        }
    }
}
```

`ChunkChangeListener.java`:

```java
package com.minecraftcitiesnetwork.directions.walk.bukkit;

import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFadeEvent;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.event.world.StructureGrowEvent;

import java.util.List;

/** Tells the chunk cache when terrain changes or a chunk unloads. */
final class ChunkChangeListener implements Listener {
    private final ChunkColumnCache cache;

    ChunkChangeListener(ChunkColumnCache cache) {
        this.cache = cache;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        mark(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        mark(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBurn(BlockBurnEvent event) {
        mark(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFade(BlockFadeEvent event) {
        mark(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onForm(BlockFormEvent event) {
        mark(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFlow(BlockFromToEvent event) {
        mark(event.getToBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        markAll(event.blockList());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        markAll(event.blockList());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        mark(event.getBlock());
        for (Block moved : event.getBlocks()) {
            mark(moved);
            mark(moved.getRelative(event.getDirection()));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        mark(event.getBlock());
        for (Block moved : event.getBlocks()) {
            mark(moved);
            mark(moved.getRelative(event.getDirection()));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGrow(StructureGrowEvent event) {
        for (BlockState state : event.getBlocks()) {
            cache.markDirty(state.getWorld(), state.getX() >> 4, state.getZ() >> 4);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onUnload(ChunkUnloadEvent event) {
        cache.evict(event.getWorld(), event.getChunk().getX(), event.getChunk().getZ());
    }

    private void markAll(List<Block> blocks) {
        for (Block block : blocks) {
            mark(block);
        }
    }

    private void mark(Block block) {
        cache.markDirty(block.getWorld(), block.getX() >> 4, block.getZ() >> 4);
    }
}
```

- [ ] **Step 3: The search service**

`WalkSearchService.java`:

```java
package com.minecraftcitiesnetwork.directions.walk.bukkit;

import com.minecraftcitiesnetwork.directions.walk.Cell;
import com.minecraftcitiesnetwork.directions.walk.ColumnStore;
import com.minecraftcitiesnetwork.directions.walk.Goal;
import com.minecraftcitiesnetwork.directions.walk.SearchRequest;
import com.minecraftcitiesnetwork.directions.walk.SearchResult;
import com.minecraftcitiesnetwork.directions.walk.Terrain;
import com.minecraftcitiesnetwork.directions.walk.WalkRules;
import com.minecraftcitiesnetwork.directions.walk.WalkSettings;
import com.minecraftcitiesnetwork.directions.walk.WalkStats;
import com.minecraftcitiesnetwork.directions.walk.Window;
import com.minecraftcitiesnetwork.directions.walk.WindowSearch;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;

/**
 * The façade navigation uses for walking routes: owns the two search threads, the chunk cache and
 * the stats. One instance per config load.
 */
public final class WalkSearchService {
    private static final int THREADS = 2;

    private final JavaPlugin plugin;
    private final WalkSettings settings;
    private final ExecutorService executor;
    private final WalkStats stats = new WalkStats(System::currentTimeMillis);
    private final ColumnStore store;
    private final ChunkColumnCache cache;
    private final ChunkChangeListener listener;
    private final WindowSearch search;
    private volatile boolean running;

    public WalkSearchService(JavaPlugin plugin, WalkSettings settings) {
        this.plugin = plugin;
        this.settings = settings;
        AtomicInteger threadNumber = new AtomicInteger();
        this.executor = Executors.newFixedThreadPool(THREADS, r -> {
            Thread t = new Thread(r, "Directions-Walk-" + threadNumber.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
        this.store = new ColumnStore(settings.cacheMaxChunks(), System::currentTimeMillis);
        this.cache = new ChunkColumnCache(plugin, store, new MaterialKinds(), executor, settings, stats);
        this.listener = new ChunkChangeListener(cache);
        this.search = new WindowSearch(new WalkRules(settings.walker()), System::nanoTime);
    }

    public void start() {
        running = true;
        cache.start();
        Bukkit.getPluginManager().registerEvents(listener, plugin);
    }

    public void shutdown() {
        running = false;
        HandlerList.unregisterAll(listener);
        cache.stop();
        executor.shutdownNow();
    }

    public WalkSettings settings() {
        return settings;
    }

    public WalkStats stats() {
        return stats;
    }

    public int cachedChunks() {
        return store.size();
    }

    /** Main thread. A fresh view of the captured terrain; do not keep it across ticks. */
    public Terrain terrain(World world) {
        return store.terrain(world.getUID());
    }

    /**
     * Main thread. Captures the window around the position, searches it on the pool, and completes
     * the returned future on the main thread. Never completes exceptionally.
     */
    public CompletableFuture<SearchResult> plan(World world, double x, double y, double z, Goal goal, List<Cell> spentExits) {
        CompletableFuture<SearchResult> result = new CompletableFuture<>();
        if (!running) {
            result.complete(SearchResult.failed(SearchResult.Status.NO_ROUTE, 0, 0));
            return result;
        }
        UUID worldId = world.getUID();
        int radius = Math.min(settings.windowRadius(), Math.max(16, (world.getViewDistance() - 1) * 16));
        Window window = Window.around((int) Math.floor(x), (int) Math.floor(z), radius);
        cache.capture(world, window).thenRunAsync(() -> {
            long deadline = System.nanoTime() + settings.timeBudgetMillis() * 1_000_000L;
            SearchRequest request = new SearchRequest(x, y, z, goal, window, spentExits, settings.maxExpansions(), deadline);
            SearchResult r = search.search(store.terrain(worldId), request);
            stats.record(r);
            toMain(() -> result.complete(r));
        }, executor).exceptionally(error -> {
            if (running) {
                plugin.getLogger().log(Level.WARNING, "Walking route search failed", error);
            }
            toMain(() -> result.complete(SearchResult.failed(SearchResult.Status.NO_ROUTE, 0, 0)));
            return null;
        });
        return result;
    }

    private void toMain(Runnable task) {
        if (plugin.isEnabled()) {
            Bukkit.getScheduler().runTask(plugin, task);
        }
    }
}
```

- [ ] **Step 4: Plugin lifecycle**

Replace the whole of `DirectionsPlugin.java` with:

```java
package com.minecraftcitiesnetwork.directions;

import com.minecraftcitiesnetwork.directions.command.DirectionsCommand;
import com.minecraftcitiesnetwork.directions.command.DirectionsBrigadierRegistrar;
import com.minecraftcitiesnetwork.directions.command.CompassCommand;
import com.minecraftcitiesnetwork.directions.command.CompassBrigadierRegistrar;
import com.minecraftcitiesnetwork.directions.command.GpsCommand;
import com.minecraftcitiesnetwork.directions.command.GpsBrigadierRegistrar;
import com.minecraftcitiesnetwork.directions.config.ConfigLoader;
import com.minecraftcitiesnetwork.directions.graph.TransitGraph;
import com.minecraftcitiesnetwork.directions.i18n.LangService;
import com.minecraftcitiesnetwork.directions.navigation.NavigationService;
import com.minecraftcitiesnetwork.directions.walk.bukkit.WalkSearchService;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.Nullable;

public class DirectionsPlugin extends JavaPlugin {
    private ConfigLoader.LoadedData loadedData;
    private TransitGraph transitGraph;
    private NavigationService navigationService;
    private WalkSearchService walkService;
    private LangService lang;

    @Override
    public void onEnable() {
        this.lang = new LangService(this);
        this.lang.load();
        reloadDirectionsData();
        this.navigationService = new NavigationService(this);
        this.navigationService.start();

        DirectionsCommand command = new DirectionsCommand(this);
        new DirectionsBrigadierRegistrar(this, command).register();
        GpsCommand gpsCommand = new GpsCommand(this);
        new GpsBrigadierRegistrar(this, gpsCommand).register();
        CompassCommand compassCommand = new CompassCommand(this, command);
        new CompassBrigadierRegistrar(this, compassCommand).register();
    }

    @Override
    public void onDisable() {
        if (navigationService != null) {
            navigationService.stop();
        }
        stopWalking();
    }

    public void reloadDirectionsData() {
        ConfigLoader loader = new ConfigLoader(this);
        this.loadedData = loader.loadAndValidate();
        this.lang.reload();
        this.transitGraph = new TransitGraph(
                loadedData.stopsById().values(),
                loadedData.lines(),
                loadedData.transferPenalty(),
                loadedData.maxWalkingDistance(),
                loadedData.walkingTransferPolicy()
        );
        restartWalking();
        getLogger().info("Loaded " + loadedData.stopsById().size() + " stops and " + loadedData.lines().size() + " lines.");
    }

    private void restartWalking() {
        stopWalking();
        if (loadedData.walk().enabled()) {
            walkService = new WalkSearchService(this, loadedData.walk());
            walkService.start();
        }
    }

    private void stopWalking() {
        if (walkService != null) {
            walkService.shutdown();
            walkService = null;
        }
    }

    public ConfigLoader.LoadedData getLoadedData() {
        return loadedData;
    }

    public TransitGraph getTransitGraph() {
        return transitGraph;
    }

    public NavigationService getNavigationService() {
        return navigationService;
    }

    /** Null when {@code walking-paths.enabled} is false. */
    public @Nullable WalkSearchService getWalkService() {
        return walkService;
    }

    public LangService getLang() {
        return lang;
    }
}
```

- [ ] **Step 5: The debug command**

In `command/DirectionsCommand.java`, add imports `net.kyori.adventure.text.Component` and `com.minecraftcitiesnetwork.directions.walk.bukkit.WalkSearchService`. Then add after `reload(...)`:

```java
    public void debugWalk(CommandSender sender) {
        WalkSearchService service = plugin.getWalkService();
        if (service == null) {
            lang.send(sender, "walk.debug-disabled", lang.placeholderRaw("prefix", lang.raw("prefix")));
            return;
        }
        for (String line : service.stats().describe(service.cachedChunks())) {
            sender.sendMessage(Component.text(line));
        }
    }
```

In `command/DirectionsBrigadierRegistrar.java`, add after the `reload` literal:

```java
                    .then(Commands.literal("debug")
                            .requires(source -> source.getSender().hasPermission("directions.debug"))
                            .then(Commands.literal("walk")
                                    .executes(this::debugWalk)))
```

and the handler next to `reload(...)`:

```java
    private int debugWalk(CommandContext<CommandSourceStack> context) {
        command.debugWalk(context.getSource().getSender());
        return Command.SINGLE_SUCCESS;
    }
```

In `src/main/resources/paper-plugin.yml`, under `permissions:`, add:

```yaml
  directions.debug:
    description: Use /directions debug walk
    default: op
```

In `src/main/resources/lang.yml`, add at the end:

```yaml
walk:
  debug-disabled: "<prefix><yellow>Walking paths are disabled (walking-paths.enabled: false)."
```

- [ ] **Step 6: Verify**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew build`
Expected: BUILD SUCCESSFUL.

Manual check on the dev server. Delete `run/plugins/Directions/config.yml` and `run/plugins/Directions/lang.yml` so the new defaults are written. Restore the test lines from Task 9 into the regenerated config. Then:
1. `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew runServer`. Expected in the log: no warnings from `walking-paths`, and no errors.
2. Run `/directions debug walk` as op. Expected: five lines. `Chunk cache: 0 chunks`, and `Worst capture tick` near 0.
3. Set `walking-paths.window-radius: 5000` and run `/directions reload`. Expected: the console warns `walking-paths.window-radius must be between 16 and 160; using 80`. Set it back.
4. Set `walking-paths.enabled: false` and run `/directions reload`, then `/directions debug walk`. Expected: the "disabled" message. Set it back and reload.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/minecraftcitiesnetwork/directions src/main/resources
git commit -m "feat(walk): chunk capture, the walking search service and /directions debug walk"
```

---

### Task 12: Guides, the trail and navigation

**Files:**
- Create: `src/main/java/com/minecraftcitiesnetwork/directions/walk/bukkit/WalkGoals.java`
- Create: `src/main/java/com/minecraftcitiesnetwork/directions/walk/bukkit/WalkGuide.java`
- Create: `src/main/java/com/minecraftcitiesnetwork/directions/walk/bukkit/ParticleStyle.java`
- Create: `src/main/java/com/minecraftcitiesnetwork/directions/walk/bukkit/TrailStyle.java`
- Create: `src/main/java/com/minecraftcitiesnetwork/directions/walk/bukkit/TrailRenderer.java`
- Modify: `src/main/java/com/minecraftcitiesnetwork/directions/navigation/Session.java`
- Modify: `src/main/java/com/minecraftcitiesnetwork/directions/navigation/NavigationService.java`
- Modify: `src/main/java/com/minecraftcitiesnetwork/directions/DirectionsPlugin.java`
- Modify: `src/main/java/com/minecraftcitiesnetwork/directions/config/ConfigLoader.java` (boss bar defaults)
- Modify: `src/main/resources/config.yml`, `src/main/resources/lang.yml`

**Interfaces:**
- Consumes: `WalkSearchService` (Task 11); `GuidePolicy` (Task 7); goals (Task 4); `Route` (Task 5); `WalkSettings`, `ParticleSpec` (Task 10); `Leg`, `LegKind` (Task 9).
- Produces:
  - `WalkGoals.forWaypoint(Waypoint, RegionManager, ConfigLoader.LoadedData) → @Nullable Goal`
  - `WalkGuide(WalkSearchService, World, Goal)` with `tick(Player)`, `@Nullable Route visibleRoute()`, `int cursor()`, `Location arrowTarget(Player)`, `double remaining(Player)`, `boolean approximate()`, `boolean takeNoRouteMessage()`
  - `TrailStyle.from(WalkSettings, Logger)`; `TrailRenderer(TrailStyle, WalkSettings).render(Player, WalkGuide, long tick)`
  - `NavigationService.dropGuides()`
  - Boss bar placeholder `<approx>`: `~` while the route is partial, empty otherwise.

- [ ] **Step 1: Goals from waypoints**

`WalkGoals.java`:

```java
package com.minecraftcitiesnetwork.directions.walk.bukkit;

import com.minecraftcitiesnetwork.directions.config.ConfigLoader;
import com.minecraftcitiesnetwork.directions.navigation.Waypoint;
import com.minecraftcitiesnetwork.directions.walk.BoxGoal;
import com.minecraftcitiesnetwork.directions.walk.Goal;
import com.minecraftcitiesnetwork.directions.walk.PointGoal;
import com.minecraftcitiesnetwork.directions.walk.PolygonGoal;
import com.minecraftcitiesnetwork.directions.walk.StopAreaGoal;
import com.sk89q.worldedit.math.BlockVector2;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.ProtectedPolygonalRegion;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * The goal for a walking leg, built to match {@code ArrivalPolicy} so the trail ends where the leg
 * counts as done: configured stops include the arrival buffer, other regions are their exact shape,
 * coordinates use the coordinate arrival radius. Main thread (reads WorldGuard).
 */
public final class WalkGoals {
    private WalkGoals() {
    }

    public static @Nullable Goal forWaypoint(Waypoint waypoint, RegionManager regions, ConfigLoader.LoadedData data) {
        return switch (waypoint) {
            case Waypoint.Coordinates c -> new PointGoal(c.x(), c.y(), c.z(), data.coordinateArrivalRadius(), c.includeY());
            case Waypoint.Region r -> forRegion(r.id(), regions, data);
        };
    }

    private static @Nullable Goal forRegion(String id, RegionManager regions, ConfigLoader.LoadedData data) {
        ProtectedRegion region = regions.getRegion(id);
        if (region == null) {
            return null;
        }
        BlockVector3 min = region.getMinimumPoint();
        BlockVector3 max = region.getMaximumPoint();
        if (data.stopsById().containsKey(id)) {
            return new StopAreaGoal(min.x(), min.z(), max.x(), max.z(), data.stopArrivalBuffer());
        }
        if (region instanceof ProtectedPolygonalRegion polygon) {
            List<BlockVector2> points = polygon.getPoints();
            int[] xs = new int[points.size()];
            int[] zs = new int[points.size()];
            for (int i = 0; i < points.size(); i++) {
                xs[i] = points.get(i).x();
                zs[i] = points.get(i).z();
            }
            return new PolygonGoal(xs, zs, min.y(), max.y());
        }
        return new BoxGoal(min.x(), min.y(), min.z(), max.x(), max.y(), max.z());
    }
}
```

- [ ] **Step 2: The guide**

`WalkGuide.java`:

```java
package com.minecraftcitiesnetwork.directions.walk.bukkit;

import com.minecraftcitiesnetwork.directions.walk.GuidePolicy;
import com.minecraftcitiesnetwork.directions.walk.Goal;
import com.minecraftcitiesnetwork.directions.walk.Route;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/** Follows one walking leg for one player: feeds {@link GuidePolicy} and runs the searches it asks for. Main thread. */
public final class WalkGuide {
    private final WalkSearchService service;
    private final UUID worldId;
    private final Goal goal;
    private final GuidePolicy policy;
    private boolean paused;

    public WalkGuide(WalkSearchService service, World world, Goal goal) {
        this.service = service;
        this.worldId = world.getUID();
        this.goal = goal;
        this.policy = new GuidePolicy(service.settings().tuning());
    }

    public void tick(Player player) {
        World world = player.getWorld();
        if (!world.getUID().equals(worldId)) {
            if (!paused) {
                policy.reset();
                paused = true;
            }
            return;
        }
        paused = false;
        Location at = player.getLocation();
        Route route = policy.route();
        boolean blocked = route != null
                && route.firstMissing(service.terrain(world), policy.cursor(), service.settings().trailLength()) >= 0;
        boolean onFoot = !player.isFlying() && !player.isGliding() && !player.isInsideVehicle();
        GuidePolicy.SearchOrder order = policy.tick(new GuidePolicy.Observation(
                System.currentTimeMillis(), at.getX(), at.getY(), at.getZ(), onFoot, blocked));
        if (order != null) {
            service.plan(world, at.getX(), at.getY(), at.getZ(), goal, order.spentExits())
                    .thenAccept(result -> policy.onResult(order.generation(), result, System.currentTimeMillis()));
        }
    }

    /** The route to draw and follow, or null while there is none (searching, failed, other world). */
    public @Nullable Route visibleRoute() {
        return paused ? null : policy.route();
    }

    public int cursor() {
        return policy.cursor();
    }

    /** Where the arrow should point. Only valid while {@link #visibleRoute()} is non-null. */
    public Location arrowTarget(Player player) {
        Route route = policy.route();
        int i = route.indexAhead(policy.cursor(), service.settings().arrowLookAhead());
        return new Location(player.getWorld(), route.x(i) + 0.5, route.y(i) + 0.1, route.z(i) + 0.5);
    }

    /** Remaining walking distance, with a straight-line estimate beyond a partial route's end. */
    public double remaining(Player player) {
        Route route = policy.route();
        Location at = player.getLocation();
        return route.remainingFrom(policy.cursor(), at.getX(), at.getY(), at.getZ()) + route.endEstimate();
    }

    public boolean approximate() {
        Route route = policy.route();
        return route != null && !route.complete();
    }

    public boolean takeNoRouteMessage() {
        return policy.takeNoRouteMessage();
    }
}
```

- [ ] **Step 3: Particles and the renderer**

`ParticleStyle.java`:

```java
package com.minecraftcitiesnetwork.directions.walk.bukkit;

import com.minecraftcitiesnetwork.directions.walk.ParticleSpec;
import org.bukkit.Color;
import org.bukkit.Particle;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.logging.Logger;

/** A resolved particle: its type and, for dust, its colour and size. */
public record ParticleStyle(Particle particle, @Nullable Object data) {

    /** Shown to this player only. */
    public void spawn(Player player, double x, double y, double z, int count) {
        player.spawnParticle(particle, x, y, z, count, 0, 0, 0, 0, data);
    }

    static ParticleStyle parse(String name, ParticleSpec spec, ParticleSpec fallback, Logger log) {
        try {
            return of(spec);
        } catch (IllegalArgumentException e) {
            log.warning("walking-paths.particles." + name + ": " + e.getMessage() + "; using the default");
            return of(fallback);
        }
    }

    static ParticleStyle of(ParticleSpec spec) {
        Particle particle = Particle.valueOf(spec.type().trim().toUpperCase(Locale.ROOT));
        Class<?> dataType = particle.getDataType();
        if (dataType == Void.class) {
            return new ParticleStyle(particle, null);
        }
        if (dataType == Particle.DustOptions.class) {
            String hex = spec.color().trim().replace("#", "");
            if (hex.length() != 6) {
                throw new IllegalArgumentException("color must be #RRGGBB, got '" + spec.color() + "'");
            }
            if (spec.size() <= 0) {
                throw new IllegalArgumentException("size must be positive");
            }
            return new ParticleStyle(particle, new Particle.DustOptions(Color.fromRGB(Integer.parseInt(hex, 16)), (float) spec.size()));
        }
        throw new IllegalArgumentException(particle + " needs data this plugin cannot supply");
    }
}
```

`TrailStyle.java`:

```java
package com.minecraftcitiesnetwork.directions.walk.bukkit;

import com.minecraftcitiesnetwork.directions.walk.WalkSettings;

import java.util.logging.Logger;

/** The three particles of a trail, resolved once per config load. */
public record TrailStyle(ParticleStyle trail, ParticleStyle pulse, ParticleStyle end) {
    public static TrailStyle from(WalkSettings settings, Logger log) {
        WalkSettings d = WalkSettings.DEFAULTS;
        return new TrailStyle(
                ParticleStyle.parse("trail", settings.trailParticle(), d.trailParticle(), log),
                ParticleStyle.parse("pulse", settings.pulseParticle(), d.pulseParticle(), log),
                ParticleStyle.parse("end", settings.endParticle(), d.endParticle(), log));
    }
}
```

`TrailRenderer.java`:

```java
package com.minecraftcitiesnetwork.directions.walk.bukkit;

import com.minecraftcitiesnetwork.directions.walk.Route;
import com.minecraftcitiesnetwork.directions.walk.WalkSettings;
import org.bukkit.entity.Player;

/**
 * Draws the next stretch of a guide's route for its player only: one particle per cell, a brighter
 * pulse travelling toward the goal, and a marker at the end of a complete route.
 */
public final class TrailRenderer {
    private static final double HEIGHT = 0.2;
    private static final int END_COUNT = 6;

    private final TrailStyle style;
    private final WalkSettings settings;

    public TrailRenderer(TrailStyle style, WalkSettings settings) {
        this.style = style;
        this.settings = settings;
    }

    /** Call every tick; draws every {@code trail-refresh-ticks}. */
    public void render(Player player, WalkGuide guide, long tick) {
        if (tick % settings.trailRefreshTicks() != 0) {
            return;
        }
        Route route = guide.visibleRoute();
        if (route == null) {
            return;
        }
        int from = guide.cursor();
        int to = Math.min(route.size(), from + settings.trailLength());
        int count = to - from;
        if (count <= 0) {
            return;
        }
        long frame = tick / settings.trailRefreshTicks();
        int pulse = from + (int) ((frame * settings.pulseStep()) % count);
        for (int i = from; i < to; i++) {
            ParticleStyle particle = i == pulse ? style.pulse() : style.trail();
            particle.spawn(player, route.x(i) + 0.5, route.y(i) + HEIGHT, route.z(i) + 0.5, 1);
        }
        int last = route.size() - 1;
        if (route.complete() && last < to) {
            style.end().spawn(player, route.x(last) + 0.5, route.y(last) + 0.5, route.z(last) + 0.5, END_COUNT);
        }
    }
}
```

- [ ] **Step 4: Session holds the guide**

In `navigation/Session.java`, add the import `com.minecraftcitiesnetwork.directions.walk.bukkit.WalkGuide`. Add the fields:

```java
    private @Nullable WalkGuide guide;
    private int guideLeg = -1;
```

and the methods (after `advance()`):

```java
    @Nullable WalkGuide guide() {
        return guide;
    }

    int guideLeg() {
        return guideLeg;
    }

    void setGuide(WalkGuide guide, int leg) {
        this.guide = guide;
        this.guideLeg = leg;
    }

    void clearGuide() {
        this.guide = null;
        this.guideLeg = -1;
    }
```

- [ ] **Step 5: Navigation uses the guide**

In `navigation/NavigationService.java`:

1. Add imports:

```java
import com.minecraftcitiesnetwork.directions.walk.Goal;
import com.minecraftcitiesnetwork.directions.walk.bukkit.TrailRenderer;
import com.minecraftcitiesnetwork.directions.walk.bukkit.TrailStyle;
import com.minecraftcitiesnetwork.directions.walk.bukkit.WalkGoals;
import com.minecraftcitiesnetwork.directions.walk.bukkit.WalkGuide;
import com.minecraftcitiesnetwork.directions.walk.bukkit.WalkSearchService;
import org.jetbrains.annotations.Nullable;
```

2. Add fields after `private BukkitTask task;`:

```java
    private long tickCounter;
    private TrailRenderer trailRenderer;
    private WalkSearchService trailRendererService;
```

3. At the top of `tick()`, before the `for` loop, add `tickCounter++;`.

4. Replace:

```java
            Location targetLoc = targetLocation(player, regionManager, currentTarget);
            if (targetLoc == null) {
                continue;
            }
            maybeSendUpcomingStopHint(player, session, regionManager, currentTarget, targetLoc);
            updateArrow(player, session, targetLoc);
            updateBossbar(session, currentTarget, player.getLocation(), targetLoc);
```

with:

```java
            Location targetLoc = targetLocation(player, regionManager, currentTarget);
            if (targetLoc == null) {
                continue;
            }
            WalkGuide guide = updateWalkGuide(session, player, regionManager);
            Location arrowTarget = targetLoc;
            double walkRemaining = -1;
            boolean approximate = false;
            if (guide != null && guide.visibleRoute() != null) {
                arrowTarget = guide.arrowTarget(player);
                walkRemaining = guide.remaining(player);
                approximate = guide.approximate();
            }
            maybeSendUpcomingStopHint(player, session, regionManager, currentTarget, targetLoc);
            updateArrow(player, session, arrowTarget);
            updateBossbar(session, currentTarget, player.getLocation(), targetLoc, walkRemaining, approximate);
```

5. Add these methods after `tick()`:

```java
    /** Keeps a walking guide on WALK legs (and none on RIDE legs), ticks it, and draws its trail. */
    private @Nullable WalkGuide updateWalkGuide(Session session, Player player, RegionManager regionManager) {
        WalkSearchService service = plugin.getWalkService();
        Leg leg = session.currentLeg();
        if (service == null || leg == null || leg.kind() != LegKind.WALK) {
            session.clearGuide();
            return null;
        }
        if (session.guide() == null || session.guideLeg() != session.index()) {
            Goal goal = WalkGoals.forWaypoint(leg.target(), regionManager, plugin.getLoadedData());
            if (goal == null) {
                session.clearGuide();
                return null;
            }
            session.setGuide(new WalkGuide(service, player.getWorld(), goal), session.index());
        }
        WalkGuide guide = session.guide();
        guide.tick(player);
        if (guide.takeNoRouteMessage()) {
            lang.send(player, "walk.no-route", lang.placeholderRaw("prefix", lang.raw("prefix")));
        }
        trailRenderer(service).render(player, guide, tickCounter);
        return guide;
    }

    private TrailRenderer trailRenderer(WalkSearchService service) {
        if (trailRendererService != service) {
            trailRenderer = new TrailRenderer(TrailStyle.from(service.settings(), plugin.getLogger()), service.settings());
            trailRendererService = service;
        }
        return trailRenderer;
    }

    /** Forgets every walking guide; used when the search service is replaced by a reload. */
    public void dropGuides() {
        for (Session session : sessions.values()) {
            session.clearGuide();
        }
    }
```

6. Change `updateBossbar`. Its signature becomes:

```java
    private void updateBossbar(Session session, Waypoint waypoint, Location from, Location target,
                               double walkRemaining, boolean approximate) {
```

Its first line becomes `double remaining = walkRemaining >= 0 ? walkRemaining : switch (waypoint) {`, and the title call becomes:

```java
        session.bossBar().setTitle(LegacyComponentSerializer.legacySection().serialize(lang.deserialize(format,
                lang.waypointLabel("stop", session.plugin().getLoadedData(), waypoint),
                Placeholder.unparsed("approx", approximate ? "~" : ""),
                Placeholder.unparsed("remaining", String.valueOf(Math.round(remaining))))));
```

- [ ] **Step 6: Reload drops guides; defaults show the approximation**

In `DirectionsPlugin.reloadDirectionsData()`, after `restartWalking();`, add:

```java
        if (navigationService != null) {
            navigationService.dropGuides();
        }
```

Change the two boss bar defaults to include `<approx>`, in all three places they appear:
- `config.yml`: `bossbar-format: "Next: <stop>  <approx><remaining>m"` and `gps-bossbar-format: "Destination: <stop>  <approx><remaining>m"`
- `ConfigLoader.MainConfig` field initialisers: the same two strings
- `ConfigLoader.loadAndValidate()` blank fallbacks: the same two strings

In `lang.yml`, under `walk:`, add:

```yaml
  no-route: "<prefix><yellow>No walking route found nearby, follow the arrow."
```

- [ ] **Step 7: Build**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew build`
Expected: BUILD SUCCESSFUL, all tests pass.

- [ ] **Step 8: Manual check: trails on walking legs only**

Delete `run/plugins/Directions/config.yml` and `lang.yml` again, then restore the test lines. In the dev world, build a short wall between `stop-a` and where you stand. `./gradlew runServer`, then:
1. `/directions stop-c`. Expected: a light-blue trail starts within about a second and leads around the wall to `stop-a`, with a white pulse travelling along it. The arrow follows the trail's direction. The boss bar distance is roughly the trail length.
2. Reach `stop-a` and teleport (`/tp`) into `stop-b`, as a ride would. Expected: no trail on the ride leg. After arriving at `stop-b`, a trail appears toward `stop-c` within about 2 s.
3. `/gps` to a region 1,000+ blocks away. Expected: the trail extends ahead as you walk, without flicker. The boss bar shows `~` until the destination is inside the window, then drops it.
4. Break a block of the wall in front of you so that the trail's next stretch is blocked, by placing blocks on it. Expected: the trail changes within half a second.
5. `/compass` to coordinates inside a sealed 5×5 stone box. Expected: one yellow "No walking route found nearby" message, the straight arrow, and no console errors.

- [ ] **Step 9: Manual check: reload and disable mid-navigation (Review Focus 5)**

While following a trail, run `/directions reload`. Expected: the trail disappears for at most about 2 s, then returns, with no console errors. Set `walking-paths.enabled: false`, then `/directions reload`. Expected: no trail, and the straight arrow and plain boss bar exactly as on `main`. Set it back to `true` and reload. Expected: the trail returns.

- [ ] **Step 10: Commit**

```bash
git add src/main/java/com/minecraftcitiesnetwork/directions src/main/resources
git commit -m "feat(navigation): walking trails on every walking leg"
```

---

### Task 13: Documentation, version and acceptance

**Files:**
- Modify: `README.md`
- Modify: `build.gradle.kts` (version)

- [ ] **Step 1: README**

In `README.md`, add `- /directions debug walk - walking-search statistics (requires directions.debug)` to `## Commands`. Then add this section before `## Building`:

```markdown
## Walking trails
Every walking part of a trip gets a particle trail along the real walking route: from where you
start to the first stop, between stops when a transfer is on foot, from the last stop to your
destination, and the whole way for `/gps` and `/compass`. Rides between stops get no trail.

- The trail is visible only to the navigating player and covers the next 40 blocks ahead; a
  brighter pulse travels along it toward the goal. The arrow points along the trail.
- Routes are computed from terrain that is already loaded around you, in a window of about 160 ×
  160 blocks, and extended as you walk, so a trip can span the whole world. Chunks are never
  loaded for this.
- The walker steps up one block, drops up to three, swims, climbs ladders and vines, and opens
  doors and gates. It never crosses fences, walls, panes, lava, fire, cactus or magma.
- While a route is only partly known, the boss bar shows `~` before the distance (add `<approx>`
  to a custom `bossbar-format` to get this).
- If no route can be found nearby, you get one message and the straight arrow, and it retries as
  you move.

Configure it under `walking-paths:` in `config.yml`; `enabled: false` restores the plain arrow.
Existing installations need two new lang keys, `walk.no-route` and `walk.debug-disabled`; delete
`lang.yml` to regenerate it, or copy them from the bundled file.
```

Under `## Testing`, add: `- ./gradlew runServer - a Paper 1.21.8 dev server in run/ with WorldGuard and WorldEdit.`

- [ ] **Step 2: Version**

In `build.gradle.kts`, change `version = "2.1.0"` to `version = "2.2.0"`.

- [ ] **Step 3: Full verification**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew clean build`
Expected: BUILD SUCCESSFUL. Every test class from Tasks 1–10 runs and passes.

Then repeat the manual checklist on the dev server. This is spec §10, and the results get recorded in the PR description:
1. Bus trip with a walking transfer: trails on the first walk, the transfer and the final walk; none on ride legs.
2. Teleport arrival at a stop far from spawn: the trail appears within about 2 s.
3. `/gps` to a point 1,000+ blocks away: the trail keeps extending ahead without flicker. The boss bar shows `~` while partial.
4. Breaking a wall or removing a door on the active route: the route changes immediately.
5. `/directions` to an unreachable island: the "no route" message once, the straight arrow, no errors.
6. `walking-paths.enabled: false`: behaviour identical to `main`.
7. `/directions debug walk` with several players navigating (use two clients, or the same trip repeated): search p95 under 50 ms. The worst capture tick is under 2 ms.

- [ ] **Step 4: Commit**

```bash
git add README.md build.gradle.kts
git commit -m "docs: walking trails; version 2.2.0"
```

Then use superpowers:finishing-a-development-branch.
