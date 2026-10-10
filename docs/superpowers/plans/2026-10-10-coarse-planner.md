# Coarse Planner Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give every walking-trail window search a goal chosen from a whole-world graph of walkable chunk components, so trails head for bridges and gaps the window cannot see.

**Architecture:** A pure package `walk/coarse/` computes each chunk's walkable components, keeps them in a per-world graph with symmetric edges, runs a coarse A* over it, and turns the result into a cell-set goal for the existing `WindowSearch`. Server-side classes load and save the graph per world, build it chunk by chunk through the Paper API, and update it on every chunk capture. `GuidePolicy`, the trail, arrow and boss bar are untouched.

**Tech Stack:** Java 21, Paper API 1.21.8, Configurate YAML, JUnit 5, Gradle 8.10.2 on a JDK 21 daemon.

**Spec:** `docs/superpowers/specs/2026-10-10-coarse-planner-design.md`

## Global Constraints

- `walk/` and `walk/coarse/` contain no `org.bukkit`, `io.papermc` or `com.sk89q` imports (`WalkPackagePurityTest`, extended in Task 1 to subpackages).
- Searches run on the two-thread pool; snapshots, command handling and chunk callbacks on the main thread; the worst capture tick stays under 2 ms.
- Search budgets unchanged: window search `time-budget-ms` 50 and `max-expansions` 200,000; coarse search `coarse.max-expansions` default 50,000, range 1,000 to 1,000,000.
- `coarse.build-chunks-per-tick` default 4, range 1 to 32.
- Graph file: `plugins/Directions/walk/<world UUID>.coarse`, gzip, magic `DCG1`, version 1, written to a temporary file then renamed. Big-endian (`DataOutputStream`); the spec is amended to say so in Task 7.
- Representative cell of a component: the walkable cell nearest the chunk's horizontal centre (`chunkX * 16 + 8`, `chunkZ * 16 + 8`, measured from cell centres), lowest `Cell.key` on ties.
- Edge cost: 3D straight-line distance between representative cells. Heuristic: `Goal.estimate` at the representative cell.
- All commits authored as Technofied, ending with `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>`. No pull request; everything lands on `feature/walking-trails`.
- Nothing under `sample-data/`, `run/` or `run-smoke/` is ever staged.

## Review Focus

1. A chunk whose only connection to its neighbour is a diagonal step across a chunk corner: the graph has no edge, the coarse path goes round, and the window search still walks the diagonal locally. Pinned in Task 3 (`cornerOnlyCrossingGivesNoEdge`).
2. A goal in a chunk the build has not reached: `goalNodes` is empty, the plan is null, the search falls back to the exit search with the penalty and the stats count a fallback. Pinned in Task 5 (`unbuiltGoalAreaGivesNoPlan`) and Task 10.
3. The graph says two chunks connect but a player has since walled the border and the chunk was re-captured: `replace` on a recomputed side drops the edge both ways. Pinned in Task 2 (`replacingAChunkRewritesNeighbourEdgesBothWays`).
4. A truncated or foreign graph file on disk: `read` throws `IOException`, the world runs without a graph, one warning is logged. Pinned in Task 7 (`truncatedStreamFailsCleanly`) and Task 10.
5. A region file whose name does not match `r.<x>.<z>.mca`, or a chunk that fails to load during a build: skipped, counted, build continues. Pinned in Task 8 (`unrelatedFileNamesAreIgnored`) and Task 11.

---

### Task 1: Chunk components

**Files:**
- Create: `src/main/java/com/minecraftcitiesnetwork/directions/walk/coarse/Side.java`
- Create: `src/main/java/com/minecraftcitiesnetwork/directions/walk/coarse/ComponentId.java`
- Create: `src/main/java/com/minecraftcitiesnetwork/directions/walk/coarse/Component.java`
- Create: `src/main/java/com/minecraftcitiesnetwork/directions/walk/coarse/ChunkComponents.java`
- Modify: `src/test/java/com/minecraftcitiesnetwork/directions/walk/WalkPackagePurityTest.java` (walk `walk/` recursively with `Files.walk`)
- Test: `src/test/java/com/minecraftcitiesnetwork/directions/walk/coarse/ChunkComponentsTest.java`

**Interfaces:**
- Consumes: `Terrain`, `WalkRules.neighbours`, `Surfaces`, `Cell.key` from `walk/`. The test helpers `BlockWorld` and `Kinds` are package-private in the `walk` test package; make them and their factory methods `public` so tests under `walk/coarse` can build worlds with them.
- Produces:
  - `enum Side { WEST(-1, 0), EAST(1, 0), NORTH(0, -1), SOUTH(0, 1); int dx(); int dz(); Side opposite(); }`
  - `record ComponentId(int chunkX, int chunkZ, int x, int y, int z)` with `Cell cell()`.
  - `record Component(ComponentId id, int minY, int maxY, Map<Side, long[]> borderCells)`: cell keys on each edge row of the chunk, sorted ascending; a side with no cells maps to an empty array.
  - `final class ChunkComponents`:
    - `static List<Component> of(Terrain terrain, int chunkX, int chunkZ, WalkRules rules)`: components in ascending order of representative key; empty list when the chunk has no surfaces; throws `IllegalStateException` when a column of the chunk is unknown.
    - `static ComponentId componentOf(Terrain terrain, int chunkX, int chunkZ, int x, int y, int z, WalkRules rules)`: null when `(x, y, z)` is not a surface cell of that chunk.
    - `static Set<Long> cellsOf(Terrain terrain, ComponentId id, WalkRules rules)`: every cell key of the component.

Flooding stays inside the chunk: a neighbour offered by `rules.neighbours` is kept only when `x >> 4 == chunkX && z >> 4 == chunkZ`.

- [ ] **Step 1: Extend the purity test to subpackages**

Change `Files.list(dir)` to `Files.walk(dir)` so `walk/coarse` is covered. Run `./gradlew test --tests '*WalkPackagePurityTest'`; expected PASS (nothing in `coarse` yet).

- [ ] **Step 2: Write the failing tests**

```java
class ChunkComponentsTest {
    // flat 16x16 chunk at feet y 65 (floor y 64), chunk (0, 0)
    @Test void flatChunkIsOneComponentWithCentreRepresentative()   // size 1; id == (0,0, 8,65,8); minY == maxY == 65; borderCells.get(WEST).length == 16
    @Test void riverThroughTheChunkGivesTwoComponents()             // columns x 7..8 are water-free air gaps (no block at y 64) → two components; representatives (6,65,8) and (9,65,8)
    @Test void bridgeOverARoadGivesTwoComponents()                  // floor at y 64 everywhere plus solid at y 68 for z 6..9 → components at y 65 and y 69
    @Test void stairsJoinTwoLevelsIntoOne()                         // floor y 64 for x 0..7, floor y 65 for x 8..15 (one-block step) → one component spanning minY 65 maxY 66
    @Test void emptyChunkHasNoComponents()                          // no blocks → empty list
    @Test void unknownColumnThrows()                                // terrain returning null for (3, 3) → IllegalStateException
    @Test void componentOfFindsTheComponentOfAnyCell()             // river chunk: componentOf(…, 12, 65, 2) == (9,65,8) id; componentOf(…, 7, 65, 2) == null
    @Test void cellsOfListsEveryCell()                               // river chunk: cellsOf(west id).size() == 7 * 16
    @Test void representativeIsDeterministicOnTies()                // flat chunk with centre column (8,8) removed: representative is the lowest key among the four cells at distance 1
}
```

- [ ] **Step 3: Run the tests to verify they fail**

Run: `./gradlew test --tests '*ChunkComponentsTest'`. Expected: compilation failure, `ChunkComponents` does not exist.

- [ ] **Step 4: Implement `Side`, `ComponentId`, `Component`, `ChunkComponents`**

Flood with an `ArrayDeque<Long>` of cell keys and a `HashMap<Long, Integer>` of cell key to packed surface built once from the chunk's 256 columns. Pick representatives after flooding each component by scanning its cells.

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew test --tests '*ChunkComponentsTest' --tests '*WalkPackagePurityTest'`. Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/minecraftcitiesnetwork/directions/walk/coarse src/test/java/com/minecraftcitiesnetwork/directions/walk
git commit -m "feat(coarse): walkable components of a chunk"
```

---

### Task 2: Coarse graph

**Files:**
- Create: `src/main/java/com/minecraftcitiesnetwork/directions/walk/coarse/CoarseNode.java`
- Create: `src/main/java/com/minecraftcitiesnetwork/directions/walk/coarse/CoarseGraph.java`
- Test: `src/test/java/com/minecraftcitiesnetwork/directions/walk/coarse/CoarseGraphTest.java`

**Interfaces:**
- Consumes: `ComponentId`, `Side` (Task 1).
- Produces:
  - `record CoarseNode(ComponentId id, int minY, int maxY, List<ComponentId> edges)`; `edges` sorted by `(chunkX, chunkZ, x, y, z)`; `Side sideOf(ComponentId other)` helper on `CoarseNode` returning the side the other chunk lies on, or null when not a 4-neighbour.
  - `final class CoarseGraph`:
    - `List<CoarseNode> nodesOf(int chunkX, int chunkZ)` (empty when unknown)
    - `CoarseNode node(ComponentId id)` (null when unknown)
    - `boolean contains(int chunkX, int chunkZ)`
    - `int size()` node count; `int chunkCount()`
    - `synchronized void replace(int chunkX, int chunkZ, List<CoarseNode> nodes, Set<Side> recomputedSides)`
    - `synchronized Map<Long, List<CoarseNode>> snapshot()` keyed by `ChunkKeys.key(chunkX, chunkZ)`; add `final class ChunkKeys { static long key(int, int); static int x(long); static int z(long); }` in `walk/coarse`.
    - `static CoarseGraph of(Map<Long, List<CoarseNode>>)` for the codec.

`replace` semantics: for a side in `recomputedSides`, the new nodes' edges on that side stand, and the neighbour chunk's nodes get their edges into `(chunkX, chunkZ)` rewritten to the reverse of them. For any other side, a new node whose id matches an old node keeps the old node's edges on that side, minus edges to ids no longer in the graph; a neighbour's edges to ids that disappeared are removed.

- [ ] **Step 1: Write the failing tests**

```java
class CoarseGraphTest {
    @Test void replacingAChunkRewritesNeighbourEdgesBothWays()   // A(0,0) edge→B(1,0) on EAST; replace(1,0,[B'],{WEST}) with B' edge→A: node(A).edges contains B'; replace(1,0,[B''],{WEST}) without the edge: node(A).edges empty
    @Test void edgesStaySymmetricAfterAnySequenceOfReplacements() // 20 seeded random replacements on a 3x3 chunk grid: for every node and every edge, the target's edges contain the source
    @Test void unrecomputedSidesKeepOldEdges()                   // A has edges E on EAST and N on NORTH; replace(0,0,[A],{EAST}) with no edges: node(A).edges == [N]
    @Test void edgesToVanishedNodesAreDropped()                  // replace(1,0, [], {WEST}) removes B; node(A).edges no longer contains B
    @Test void snapshotRoundTripsThroughOf()                      // graph.of(graph.snapshot()).size() == graph.size()
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew test --tests '*CoarseGraphTest'`. Expected: compilation failure.

- [ ] **Step 3: Implement `CoarseNode`, `ChunkKeys`, `CoarseGraph`**

Backing store `ConcurrentHashMap<Long, List<CoarseNode>>` with immutable lists; `replace` builds new lists and `put`s them, never mutating a published list.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew test --tests '*CoarseGraphTest'`. Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/minecraftcitiesnetwork/directions/walk/coarse src/test/java/com/minecraftcitiesnetwork/directions/walk/coarse
git commit -m "feat(coarse): per-world graph of chunk components with symmetric edges"
```

---

### Task 3: Edges between neighbouring chunks

**Files:**
- Create: `src/main/java/com/minecraftcitiesnetwork/directions/walk/coarse/ChunkEdges.java`
- Test: `src/test/java/com/minecraftcitiesnetwork/directions/walk/coarse/ChunkEdgesTest.java`

**Interfaces:**
- Consumes: `ChunkComponents.of`, `Component.borderCells`, `WalkRules.neighbours`, `Terrain`.
- Produces: `final class ChunkEdges` with `record Linked(List<CoarseNode> nodes, Set<Side> sides)` and
  `static Linked compute(Terrain terrain, WalkRules rules, int chunkX, int chunkZ, List<Component> components)`: for each side whose neighbour chunk is fully captured (every column non-null), floods the neighbour's components and adds an edge wherever `rules.neighbours` from a border cell of this chunk reaches a cell in the neighbour's opposite-side border list. `sides` is the set of sides that were captured. Diagonal moves that land outside the four side neighbours are ignored.

- [ ] **Step 1: Write the failing tests**

```java
class ChunkEdgesTest {
    @Test void flatNeighboursConnectOnEverySide()          // 3x3 flat chunks around (1,1): the centre's single node has 4 edges; sides == all four
    @Test void unknownNeighbourSideIsNotReported()         // east neighbour unknown: sides == {WEST, NORTH, SOUTH}; 3 edges
    @Test void wallAlongTheBorderGivesNoEdge()             // solid wall two high at x 15 of chunk (0,0): no edge to (1,0)
    @Test void cornerOnlyCrossingGivesNoEdge()             // only the cell (15, 65, 15) of chunk (0,0) and (16, 65, 16) of chunk (1,1) are walkable: no edge between them
    @Test void stepUpAcrossTheBorderConnects()             // chunk (0,0) floor y 64, chunk (1,0) floor y 65: edge exists; floor y 66 (two-block step): no edge
    @Test void twoComponentsEachConnectToTheirOwnSide()    // river chunk (Task 1 layout) with flat neighbours: west component has edge to (−1,0) only, east to (1,0) only
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew test --tests '*ChunkEdgesTest'`. Expected: compilation failure.

- [ ] **Step 3: Implement `ChunkEdges.compute`**

Build a `Map<Long, ComponentId>` from the neighbour's opposite-side border cells to their ids once per side.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew test --tests '*ChunkEdgesTest'`. Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/minecraftcitiesnetwork/directions/walk/coarse src/test/java/com/minecraftcitiesnetwork/directions/walk/coarse
git commit -m "feat(coarse): edges between the components of neighbouring chunks"
```

---

### Task 4: Coarse search

**Files:**
- Create: `src/main/java/com/minecraftcitiesnetwork/directions/walk/coarse/CoarseSearch.java`
- Test: `src/test/java/com/minecraftcitiesnetwork/directions/walk/coarse/CoarseSearchTest.java`

**Interfaces:**
- Consumes: `CoarseGraph`, `CoarseNode`, `ComponentId`, `Goal.estimate`.
- Produces: `final class CoarseSearch` with
  - `sealed interface Result permits Found, Failure`; `record Found(List<ComponentId> path, double length) implements Result` (path from start to a goal node inclusive; `length` the sum of edge costs); `enum Failure implements Result { NO_PATH, BUDGET }`.
  - `static Result search(CoarseGraph graph, ComponentId start, Set<ComponentId> goals, Goal goal, int maxExpansions)`: A* with `g + goal.estimate(rep)`, ties broken on lower estimate then lower `(chunkX, chunkZ, x, y, z)`; a `start` in `goals` returns `Found([start], 0)`; a start not in the graph returns `NO_PATH`.

- [ ] **Step 1: Write the failing tests**

```java
class CoarseSearchTest {
    // helper: line(n) builds n flat chunks in a row (0,0)…(n-1,0) with edges between neighbours; grid(w,h) likewise
    @Test void findsTheShortestPathOnAGrid()      // grid 5x5 from (0,0) to (4,4) with the middle column (2, 1..4) removed: path length == expected via (2,0); path.size() == 9
    @Test void startInGoalsIsFoundAtOnce()        // Found with path [start] and length 0
    @Test void noPathAcrossAGap()                 // line(4) with the edge (1,0)-(2,0) removed: NO_PATH
    @Test void budgetStopsTheSearch()             // grid 10x10, maxExpansions 3: BUDGET
    @Test void unknownStartIsNoPath()
    @Test void resultIsDeterministic()            // two equal-cost routes on a 3x3 grid: the same path on 10 runs
}
```

Goal for the tests: `new PointGoal(x, y, z, 0.5, false)` at the target chunk's representative; goals set = that node's id.

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew test --tests '*CoarseSearchTest'`. Expected: compilation failure.

- [ ] **Step 3: Implement `CoarseSearch.search`**

A `PriorityQueue` of entries `(f, h, id, g)`, a `HashMap<ComponentId, Double>` of best g, a `HashMap<ComponentId, ComponentId>` of parents; count an expansion when an entry is popped and not stale.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew test --tests '*CoarseSearchTest'`. Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/minecraftcitiesnetwork/directions/walk/coarse src/test/java/com/minecraftcitiesnetwork/directions/walk/coarse
git commit -m "feat(coarse): A* over the chunk-component graph"
```

---

### Task 5: Waypoint, cell-set goal, coarse plan

**Files:**
- Modify: `src/main/java/com/minecraftcitiesnetwork/directions/walk/Goal.java` (add `Bounds bounds()`)
- Create: `src/main/java/com/minecraftcitiesnetwork/directions/walk/Bounds.java`
- Modify: `BoxGoal.java`, `StopAreaGoal.java`, `PolygonGoal.java`, `PointGoal.java` (implement `bounds()`)
- Create: `src/main/java/com/minecraftcitiesnetwork/directions/walk/CellSetGoal.java`
- Create: `src/main/java/com/minecraftcitiesnetwork/directions/walk/coarse/Waypoints.java`
- Create: `src/main/java/com/minecraftcitiesnetwork/directions/walk/coarse/CoarsePlanner.java`
- Test: `src/test/java/com/minecraftcitiesnetwork/directions/walk/GoalsTest.java` (add bounds cases), `src/test/java/com/minecraftcitiesnetwork/directions/walk/CellSetGoalTest.java`, `src/test/java/com/minecraftcitiesnetwork/directions/walk/coarse/WaypointsTest.java`, `src/test/java/com/minecraftcitiesnetwork/directions/walk/coarse/CoarsePlannerTest.java`

**Interfaces:**
- Consumes: `CoarseGraph`, `CoarseSearch`, `ChunkComponents.componentOf/cellsOf`, `WindowSearch.Start`, `Window`.
- Produces:
  - `record Bounds(int minX, int minY, int minZ, int maxX, int maxY, int maxZ)`; `Goal.bounds()` is a box containing every goal cell. `PointGoal`: `floor(x − radius)`…`floor(x + radius)` horizontally, same vertically when `includeY` else `Short.MIN_VALUE`…`Short.MAX_VALUE`. `StopAreaGoal`: box widened by `ceil(margin) + 1`, full height. `PolygonGoal`: its bounding box. `BoxGoal`: itself.
  - `final class CellSetGoal implements Goal` with `CellSetGoal(Set<Long> cellKeys)`: `contains` is membership; `estimate` is `BoxGoal.estimate` of the cells' bounding box; `mayOverlap` and `bounds` from that box. Throws on an empty set.
  - `final class Waypoints` with `static ComponentId pick(List<ComponentId> path, Window window, Terrain terrain)`: the last id on the path whose chunk's 16 × 16 columns all lie inside the window and whose representative column is captured; null when that is `path.get(0)` or nothing qualifies.
  - `final class CoarsePlanner` with `record Plan(ComponentId waypoint, CellSetGoal goal, double remaining)` and
    `static Plan plan(CoarseGraph graph, Terrain terrain, WalkRules rules, WindowSearch.Start start, Goal goal, Window window, int maxExpansions)`: §4 steps 2a to 2d; `remaining` = coarse length from the waypoint to the path's end plus `goal.estimate` at the last node's representative; null for every fallback case. `static Set<ComponentId> goalNodes(CoarseGraph graph, Goal goal)`: every node of every chunk overlapping `goal.bounds()` whose `[minY, maxY]` overlaps the bounds' height range.

- [ ] **Step 1: Write the failing tests**

```java
// GoalsTest additions
@Test void pointGoalBoundsCoverTheRadius()            // PointGoal(10.5, 65, 10.5, 3, false).bounds() == (7, Short.MIN, 7, 13, Short.MAX, 13)
@Test void stopAreaBoundsIncludeTheMargin()           // margin 2.5 → widened by 4

class CellSetGoalTest {
    @Test void containsOnlyItsCells()
    @Test void estimateIsDistanceToTheBoundingBox()   // cells (10,65,10),(12,65,10): estimate(0,65,10) == 10
    @Test void emptySetIsRejected()                   // IllegalArgumentException
}

class WaypointsTest {
    // path of chunk ids (0,0)…(6,0); window x −8..72 (chunks 0..3 fully inside, chunk 4 cut at x 72)
    @Test void picksTheLastFullyInsideCapturedChunk()  // terrain all captured → id of chunk (3,0)
    @Test void skipsUncapturedChunks()                 // chunk (3,0) uncaptured → id of chunk (2,0)
    @Test void nullWhenOnlyTheStartQualifies()         // window x −8..24 → null
}

class CoarsePlannerTest {
    // synthetic 20x3-chunk flat world with a wall at chunk column 10 except a gap at chunk row 2; graph built with ChunkComponents + ChunkEdges; terrain captured within 128 blocks of the start
    @Test void planLeadsTowardTheGap()                // start chunk (0,1), goal in chunk (19,1), window radius 80: waypoint chunk is (4, 2) or (4, 1) with row-2 route ahead, i.e. waypoint.chunkZ() == 2 when the path bends before the window edge — assert the path passes through (10, 2) and the waypoint lies on that path inside the window
    @Test void remainingIsTheCoarseLengthBeyondTheWaypoint()  // remaining > straight-line distance from waypoint to goal; equals CoarseSearch length minus the prefix up to the waypoint plus goal.estimate(last)
    @Test void unbuiltGoalAreaGivesNoPlan()           // graph without chunks x >= 15 → null
    @Test void startOutsideTheGraphGivesNoPlan()
    @Test void budgetGivesNoPlan()                    // maxExpansions 1 → null
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew test --tests '*GoalsTest' --tests '*CellSetGoalTest' --tests '*WaypointsTest' --tests '*CoarsePlannerTest'`. Expected: compilation failure.

- [ ] **Step 3: Implement `Bounds`, `Goal.bounds`, `CellSetGoal`, `Waypoints`, `CoarsePlanner`**

- [ ] **Step 4: Run the whole suite**

Run: `./gradlew test`. Expected: PASS (existing goal tests still green).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/minecraftcitiesnetwork/directions/walk src/test/java/com/minecraftcitiesnetwork/directions/walk
git commit -m "feat(coarse): waypoint choice and the coarse plan for one window"
```

---

### Task 6: Guided search and the simulation proof

**Files:**
- Modify: `src/main/java/com/minecraftcitiesnetwork/directions/walk/SearchResult.java` (add `boolean coarse`; keep a 5-argument constructor defaulting to false)
- Modify: `src/main/java/com/minecraftcitiesnetwork/directions/walk/Route.java` (add `Route asPartial(double endEstimate)`)
- Create: `src/main/java/com/minecraftcitiesnetwork/directions/walk/coarse/GuidedSearch.java`
- Modify: `src/test/java/com/minecraftcitiesnetwork/directions/walk/WalkSimulation.java` (coarse mode)
- Modify: `src/test/java/com/minecraftcitiesnetwork/directions/walk/GuideSimulationTest.java`
- Test: `src/test/java/com/minecraftcitiesnetwork/directions/walk/coarse/GuidedSearchTest.java`, `RouteTest` (asPartial)

**Interfaces:**
- Consumes: `WindowSearch`, `CoarsePlanner`, `CoarseGraph`.
- Produces: `final class GuidedSearch` with `GuidedSearch(WindowSearch window, WalkRules rules)` and
  `SearchResult search(Terrain terrain, SearchRequest request, CoarseGraph graph, int coarseMaxExpansions)` implementing §4 steps 1 to 3 exactly; `graph` may be null. A result produced through a waypoint has `coarse == true`, status `PARTIAL`, and `route.endEstimate() == plan.remaining()`.
  `WalkSimulation.walk(City city, int windowRadius, double startX, double startZ, Goal goal, double seconds, boolean coarse)`: when `coarse`, the graph is built for the city over x −200..1200, z −900..900 with `ChunkComponents` and `ChunkEdges` before the walk, and every search goes through `GuidedSearch`. `Report` gains `int guidedRoutes`, `double minZ` and `double maxX` (the walker's extremes over the whole walk).

- [ ] **Step 1: Write the failing tests**

```java
class GuidedSearchTest {
    @Test void goalInsideTheWindowIsReachedDirectly()            // coarse flag false, COMPLETE
    @Test void waypointRouteIsReportedPartialWithCoarseRemaining() // PARTIAL, coarse true, endEstimate == plan.remaining
    @Test void unreachableWaypointFallsBackToExitSearch()         // graph edge that terrain contradicts: coarse false, PARTIAL from the exit search
    @Test void nullGraphBehavesAsBefore()                         // identical status and end cell to WindowSearch alone
}
// RouteTest addition
@Test void asPartialKeepsCellsAndSetsTheEstimate()
// GuideSimulationTest changes: every existing test also runs with coarse = true (same expectations), plus
@Test void withTheGraphTheRiverWalkerHeadsForTheBridge()        // RIVER_WITH_BRIDGE, FULL_WINDOW, coarse: arrived, reversals 0, report.minZ() >= −20
@Test void withTheGraphTheDeadEndIsNeverEntered()                // DEAD_END_WITH_BRANCH, coarse: reversals 0, report.maxX() < 150, report.z() >= 200
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew test --tests '*GuidedSearchTest' --tests '*GuideSimulationTest' --tests '*RouteTest'`. Expected: compilation failure.

- [ ] **Step 3: Implement `SearchResult.coarse`, `Route.asPartial`, `GuidedSearch`, the simulation's coarse mode**

- [ ] **Step 4: Run the whole suite**

Run: `./gradlew test`. Expected: PASS, including the four new simulation expectations.

- [ ] **Step 5: Commit**

```bash
git add src/main/java src/test/java
git commit -m "feat(coarse): guided window search, proven on the simulated river and dead end"
```

---

### Task 7: Graph file format

**Files:**
- Create: `src/main/java/com/minecraftcitiesnetwork/directions/walk/coarse/BuildProgress.java`
- Create: `src/main/java/com/minecraftcitiesnetwork/directions/walk/coarse/CoarseGraphCodec.java`
- Modify: `docs/superpowers/specs/2026-10-10-coarse-planner-design.md` §7 ("little-endian" → "big-endian, Java `DataOutput`")
- Test: `src/test/java/com/minecraftcitiesnetwork/directions/walk/coarse/CoarseGraphCodecTest.java`

**Interfaces:**
- Produces:
  - `record BuildProgress(int regionX, int regionZ, int row, boolean done)` with `static final BuildProgress NONE = new BuildProgress(-1, -1, -1, false)`.
  - `final class CoarseGraphCodec` with `record Loaded(UUID world, CoarseGraph graph, BuildProgress progress)`,
    `static void write(UUID world, CoarseGraph graph, BuildProgress progress, OutputStream out) throws IOException` and
    `static Loaded read(InputStream in) throws IOException`. Layout as spec §7 over `GZIPOutputStream` + `DataOutputStream`. A bad magic, unsupported version or short read throws `IOException` with a message naming which.

- [ ] **Step 1: Write the failing tests**

```java
class CoarseGraphCodecTest {
    @Test void roundTripsNodesEdgesAndProgress()   // 3-chunk graph with edges, progress (2, −3, 17, false): read(write(…)) equal node lists per chunk and equal progress and world
    @Test void emptyGraphRoundTrips()
    @Test void truncatedStreamFailsCleanly()       // first half of the bytes → IOException
    @Test void wrongMagicIsRejected()              // "DCGX" → IOException mentioning "magic"
    @Test void newerVersionIsRejected()            // version 2 → IOException mentioning "version"
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew test --tests '*CoarseGraphCodecTest'`. Expected: compilation failure.

- [ ] **Step 3: Implement `BuildProgress`, `CoarseGraphCodec`; amend the spec's §7**

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew test --tests '*CoarseGraphCodecTest'`. Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/minecraftcitiesnetwork/directions/walk/coarse src/test/java/com/minecraftcitiesnetwork/directions/walk/coarse docs/superpowers/specs/2026-10-10-coarse-planner-design.md
git commit -m "feat(coarse): graph file format"
```

---

### Task 8: Region headers

**Files:**
- Create: `src/main/java/com/minecraftcitiesnetwork/directions/walk/coarse/ChunkPos.java`
- Create: `src/main/java/com/minecraftcitiesnetwork/directions/walk/coarse/RegionHeaders.java`
- Test: `src/test/java/com/minecraftcitiesnetwork/directions/walk/coarse/RegionHeadersTest.java`

**Interfaces:**
- Produces:
  - `record ChunkPos(int x, int z)`.
  - `final class RegionHeaders` with `static ChunkPos regionOf(String fileName)` (null unless the name is `r.<int>.<int>.mca`) and
    `static List<ChunkPos> chunksIn(Path regionFile) throws IOException`: reads the first 4,096 bytes; entry `i` (`x = i & 31`, `z = i >> 5`) is a big-endian int whose top 24 bits are the sector offset; non-zero means the chunk exists; absolute chunk `(regionX * 32 + x, regionZ * 32 + z)`; returned in row order (z then x). A file shorter than 4,096 bytes throws `IOException`.

- [ ] **Step 1: Write the failing tests**

```java
class RegionHeadersTest {
    @Test void parsesRegionCoordinatesFromTheFileName()   // "r.-3.7.mca" → (−3, 7)
    @Test void unrelatedFileNamesAreIgnored()             // "r.1.mca", "level.dat", "r.a.b.mca" → null
    @Test void listsExactlyTheChunksWithOffsets()         // temp file: 8 KB of zeros with entries for (x 5, z 0) and (x 31, z 31) set to offset 2 → [(5 + 32*rx, 0 + 32*rz), (31 + 32*rx, 31 + 32*rz)] for rx −1, rz 2
    @Test void shortFileIsAnError()                       // 100-byte file → IOException
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew test --tests '*RegionHeadersTest'`. Expected: compilation failure.

- [ ] **Step 3: Implement `ChunkPos`, `RegionHeaders`**

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew test --tests '*RegionHeadersTest'`. Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/minecraftcitiesnetwork/directions/walk/coarse src/test/java/com/minecraftcitiesnetwork/directions/walk/coarse
git commit -m "feat(coarse): list existing chunks from region file headers"
```

---

### Task 9: Configuration and messages

**Files:**
- Modify: `src/main/java/com/minecraftcitiesnetwork/directions/walk/WalkSettings.java` (add `CoarseSettings coarse` as the last component; `record CoarseSettings(boolean enabled, int buildChunksPerTick, int maxExpansions)` nested, with `DEFAULTS` `(true, 4, 50_000)`)
- Modify: `src/main/java/com/minecraftcitiesnetwork/directions/config/ConfigLoader.java` (`CoarseSection` with `@Setting("coarse")` on `WalkingPathsSection`: `enabled`, `build-chunks-per-tick`, `max-expansions`)
- Modify: `src/main/java/com/minecraftcitiesnetwork/directions/config/WalkSettingsParser.java` (ranges 1..32 and 1,000..1,000,000, warn-and-default)
- Modify: `src/main/resources/config.yml` (the `coarse:` block from spec §8, placed after `arrow-look-ahead`)
- Modify: `src/main/resources/lang.yml` (`walk.` keys: `build-started`, `build-resumed`, `build-stopped`, `build-done`, `build-running`, `build-no-world`, `build-disabled`, `build-none`; placeholders `<world>` and `<chunks>`)
- Modify: `README.md` (config table and the lang-keys note for existing installations)
- Test: `src/test/java/com/minecraftcitiesnetwork/directions/config/WalkSettingsParserTest.java`

Message texts:
- `build-started`: `<prefix><green>Building the walking graph of <world> (<chunks> chunks). Progress: /directions debug walk`
- `build-resumed`: `<prefix><green>Resuming the walking graph of <world> (<chunks> chunks left).`
- `build-stopped`: `<prefix><yellow>Walking graph build stopped; progress saved.`
- `build-done`: `<prefix><green>Walking graph of <world> complete (<chunks> chunks).`
- `build-running`: `<prefix><yellow>A walking graph build is already running; use /directions walk build stop first.`
- `build-no-world`: `<prefix><red>No world named <world>.`
- `build-disabled`: `<prefix><yellow>Whole-world routing is disabled (walking-paths.coarse.enabled: false).`
- `build-none`: `<prefix><yellow>No walking graph build is running.`

- [ ] **Step 1: Write the failing tests**

```java
// WalkSettingsParserTest additions
@Test void coarseDefaultsApply()                     // parsed section with defaults → coarse() == CoarseSettings.DEFAULTS
@Test void coarseBuildRateOutOfRangeFallsBack()     // build-chunks-per-tick 0 → warning mentions "coarse.build-chunks-per-tick", value 4
@Test void coarseExpansionsOutOfRangeFallsBack()    // max-expansions 10 → 50_000
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew test --tests '*WalkSettingsParserTest'`. Expected: compilation failure.

- [ ] **Step 3: Implement settings, section, parser, config.yml, lang.yml, README table**

Warn key prefix: `walking-paths.coarse.`.

- [ ] **Step 4: Run the whole suite**

Run: `./gradlew test`. Expected: PASS (`WalkSettings.DEFAULTS` callers updated).

- [ ] **Step 5: Commit**

```bash
git add src/main src/test README.md
git commit -m "feat(coarse): configuration and messages"
```

---

### Task 10: Graph registry and the guided search in the service

**Files:**
- Create: `src/main/java/com/minecraftcitiesnetwork/directions/walk/bukkit/CoarseGraphs.java`
- Modify: `src/main/java/com/minecraftcitiesnetwork/directions/walk/bukkit/WalkSearchService.java`
- Modify: `src/main/java/com/minecraftcitiesnetwork/directions/walk/bukkit/ChunkColumnCache.java` (a `CapturedListener` hook)
- Modify: `src/main/java/com/minecraftcitiesnetwork/directions/walk/WalkStats.java`
- Modify: `src/main/java/com/minecraftcitiesnetwork/directions/DirectionsPlugin.java`
- Test: `src/test/java/com/minecraftcitiesnetwork/directions/walk/WalkStatsTest.java`

**Interfaces:**
- Produces:
  - `final class CoarseGraphs` (main thread unless noted):
    - `CoarseGraphs(JavaPlugin plugin)`; directory `new File(plugin.getDataFolder(), "walk")`.
    - `void start()`: loads every currently loaded world asynchronously and registers a `WorldLoadEvent` listener that does the same; starts a 10-minute save timer.
    - `void stop()`: saves every dirty world synchronously, unregisters, shuts the save executor down.
    - `@Nullable CoarseGraph graph(UUID world)` (any thread): null until loaded or when no file exists and nothing was built.
    - `CoarseGraph graphOrCreate(UUID world)`.
    - `BuildProgress progress(UUID world)`; `void setProgress(UUID world, BuildProgress progress)`; `void markDirty(UUID world)`.
    - `void saveAsync(UUID world)`: snapshot under the graph lock, write on a single-thread executor to `<uuid>.coarse.tmp`, then `Files.move(…, ATOMIC_MOVE, REPLACE_EXISTING)`.
    - `List<String> describe()`: one line per world: `Coarse graph <name>: <nodes> nodes in <chunks> chunks, build <done|row r of region x,z|not started>`.
    - A load failure logs one warning naming the file and leaves the world without a graph.
  - `ChunkColumnCache`: constructor gains `CapturedListener onCaptured` (`interface CapturedListener { void captured(UUID world, int chunkX, int chunkZ); }`), called on the main thread right after `store.put`.
  - `WalkSearchService(JavaPlugin plugin, WalkSettings settings, CoarseGraphs graphs)`: `plan` runs `GuidedSearch.search(terrain, request, settings.coarse().enabled() ? graphs.graph(worldId) : null, settings.coarse().maxExpansions())`. The captured listener submits to the pool: `ChunkComponents.of` + `ChunkEdges.compute` with `store.terrain(world)` and `graphs.graphOrCreate(world).replace(…)`, then `graphs.markDirty(world)`; a chunk with an unknown column (evicted meanwhile) is skipped.
  - `WalkStats`: `record(SearchResult)` also counts `guided` when `result.coarse()`; new `recordFallback()`; `describe` gains the line `Coarse: <guided> guided, <fallback> fell back`. `WalkSearchService` calls `recordFallback()` when a graph existed, the goal could not overlap the window, and the result is not coarse.
  - `DirectionsPlugin`: owns `CoarseGraphs`, created and started in `onEnable` before `reloadDirectionsData`, passed to every `WalkSearchService`, stopped last in `onDisable`.

- [ ] **Step 1: Write the failing test**

```java
// WalkStatsTest additions
@Test void coarseResultsAreCountedAsGuided()      // record(result with coarse true) → describe contains "Coarse: 1 guided, 0 fell back"
@Test void fallbacksAreCounted()
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew test --tests '*WalkStatsTest'`. Expected: FAIL (no such line).

- [ ] **Step 3: Implement `WalkStats` changes, `CoarseGraphs`, the cache hook, the service wiring, plugin ownership**

- [ ] **Step 4: Build**

Run: `./gradlew build`. Expected: BUILD SUCCESSFUL, all tests pass.

- [ ] **Step 5: Smoke-test on the dev server**

Run: `./gradlew runServer` in the background, wait for "Done", confirm the log shows the plugin enabled with no warnings about `walk/`, then stop the server with `stop` on its console. Record the result in the commit message if anything was unexpected.

- [ ] **Step 6: Commit**

```bash
git add src/main src/test
git commit -m "feat(coarse): per-world graph registry, live updates, guided searches"
```

---

### Task 11: Background build and commands

**Files:**
- Create: `src/main/java/com/minecraftcitiesnetwork/directions/walk/bukkit/CoarseBuilder.java`
- Modify: `src/main/java/com/minecraftcitiesnetwork/directions/command/DirectionsCommand.java` (`walkBuild(CommandSender, String world)`, `walkBuildStop(CommandSender)`, `debugWalk` prints `graphs.describe()` and `builder.describe()` after the stats)
- Modify: `src/main/java/com/minecraftcitiesnetwork/directions/command/DirectionsBrigadierRegistrar.java` (`walk build stop` literal before `walk build <world>` argument, both under `directions.debug`; suggestions: loaded world names)
- Modify: `src/main/java/com/minecraftcitiesnetwork/directions/DirectionsPlugin.java` (owns `CoarseBuilder`; `reloadDirectionsData` calls `builder.configure(loadedData.walk())`; `onDisable` calls `builder.shutdown()` before `graphs.stop()`)
- Modify: `src/main/resources/paper-plugin.yml` or `plugin.yml` only if the permission list needs no change (it does not: `directions.debug` exists)
- Test: `src/test/java/com/minecraftcitiesnetwork/directions/walk/coarse/BuildOrderTest.java` for the pure ordering helper

**Interfaces:**
- Produces:
  - Pure helper in `walk/coarse`: `final class BuildOrder` with `static List<ChunkPos> after(List<ChunkPos> chunksOfRegion, BuildProgress progress, int regionX, int regionZ)`: the chunks of a region still to do, given saved progress (all of them for a region before the saved one is already done; rows after `progress.row` for the saved region; everything for later regions). Regions are ordered by `(regionX, regionZ)`.
  - `final class CoarseBuilder` (main thread):
    - `CoarseBuilder(JavaPlugin plugin, CoarseGraphs graphs)`; `void configure(WalkSettings settings)` sets chunks per tick, `WalkRules`, and `enabled`.
    - `enum StartResult { STARTED, RESUMED, ALREADY_RUNNING, NO_WORLD, DISABLED }`; `StartResult start(World world, CommandSender requester)`; `boolean stop()`; `boolean running()`; `List<String> describe()`; `void shutdown()`.
    - Region folder by `world.getEnvironment()`: `NORMAL` → `region`, `NETHER` → `DIM-1/region`, `THE_END` → `DIM1/region`, custom → `region`.
    - Per tick (`runTaskTimer` every tick while running): while in-flight < `chunksPerTick * 4` and requested this tick < `chunksPerTick`: next chunk → `world.getChunkAtAsync(x, z, false)`; on completion on the main thread: null (not generated) or exception → skipped count, log once per build; otherwise `getChunkSnapshot(false, false, false)` and submit to a builder-owned single-thread executor: decode, `ChunkComponents.of`, `ChunkEdges.compute` against a build `ColumnStore` of capacity 4,096 that holds decoded chunks, `graph.replace`, then `graphs.markDirty`. Progress updated after each completed row; `graphs.setProgress` + `saveAsync` every 2,000 chunks; on the last chunk `BuildProgress.done = true`, save, `build-done` to the requester if still online, and an info log line.
    - `describe()`: `Build <world>: <done>/<total> chunks, <rate>/s, about <minutes> min left, <skipped> skipped` or `Build: none running`.
    - `shutdown()`: stops the timer, saves progress, shuts the executor down.

- [ ] **Step 1: Write the failing test**

```java
class BuildOrderTest {
    @Test void everythingBeforeAnyProgress()          // NONE → all chunks
    @Test void resumesAfterTheSavedRow()              // progress (0,0,row 5), region (0,0): only chunks with z row > 5
    @Test void laterRegionsAreUntouched()             // progress (0,0,5), region (1,0): all chunks
    @Test void earlierRegionsAreDone()                // progress (1,0,5), region (0,0): empty
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew test --tests '*BuildOrderTest'`. Expected: compilation failure.

- [ ] **Step 3: Implement `BuildOrder`, `CoarseBuilder`, the commands and plugin wiring**

- [ ] **Step 4: Build, then test the build on the dev server**

Run: `./gradlew build`; expected BUILD SUCCESSFUL. Then `./gradlew runServer` in the background, on the console run `directions walk build Reveille`, wait two minutes, run `directions debug walk`, confirm the build line shows progress and the worst capture tick stays under 2 ms, run `directions walk build stop`, confirm `plugins/Directions/walk/<uuid>.coarse` exists, then `stop`. Note the measured chunks per second in the commit message.

- [ ] **Step 5: Commit**

```bash
git add src/main src/test
git commit -m "feat(coarse): background world build with resume, and the walk build commands"
```

---

### Task 12: Documentation

**Files:**
- Modify: `README.md` (walking-trails section: a "Whole-world routing" paragraph: what the build does, the command, how long Reveille takes, the config keys, what happens without a graph)
- Modify: `docs/superpowers/specs/2026-10-02-walking-trails-design.md` §11 (now implemented; point to the new spec)
- Modify: `docs/superpowers/specs/2026-10-10-coarse-planner-design.md` §11 (the simulation uses `GuidedSearch` itself, not a mirror)

- [ ] **Step 1: Write the documentation changes**

- [ ] **Step 2: Build**

Run: `./gradlew build`. Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add README.md docs
git commit -m "docs: whole-world routing for walking trails"
```
