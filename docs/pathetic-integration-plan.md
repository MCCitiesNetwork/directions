# Pathetic Integration Plan

End-to-end plan for integrating the Pathetic A\* engine into Directions, with the test harnesses that make each layer verifiable. Companion to [pathetic-feasibility-study.md](pathetic-feasibility-study.md).

| | |
|---|---|
| Date | 2026-09-10 |
| Target | Directions 2.1.0 → 3.0.0, Paper 1.21.8, Java 21, Gradle 8.10.2 |
| Engine | `de.bsommerfeld.pathetic:engine:5.5.2` (Maven Central, MIT) |
| World adapter | **In-house.** pathetic-bukkit is used only as a reference during the spike. |
| Total effort | 6 phases, roughly 19–27 working days |

## 0. Decisions made up front

These are settled so the rest of the plan can be concrete. Each has a one-line reason; the study has the long form.

1. **Engine from Maven Central, provider written in-house.** A tight integration means Directions owns every world-semantics decision: doors, liquids, drops, climbs. pathetic-bukkit would put those behind a JitPack-only, telemetry-bearing, statically-stateful dependency compiled against the 26.x API. Its two useful processors are ~150 lines and MIT; port them with attribution.
2. **Pathetic is wrapped once.** One class (`WalkPathfinder`) touches the Pathetic API. Everything else in Directions talks to Directions types. Upstream breaking releases become a one-file change.
3. **Pure-Java core, thin Bukkit shell.** The provider, validators, costs, path type, budget logic, and even the main-thread hop are written against small Directions interfaces (`BlockGrid`, `MainThread`) so 90% of the new code runs under plain JUnit with no server.
4. **Straight-line behaviour is the permanent fallback.** Every code path that asks for a walking path must work when the answer is "none" or "too slow". The flag defaults to off in 3.0.0 and flips on after a release cycle.
5. **Real terrain is captured, not mocked, and never committed.** The owner provides the live city world, its WorldGuard regions, and live configs in a git-ignored `sample-data/` folder. Captures of the five reference routes are generated from it and stay local. Real-terrain tests run on developer machines and skip in CI; CI covers the same walker rules on a synthetic fixture world.
6. **The cost model is physical only.** No region or plot awareness and no surface preference. If a player can physically get through, the walker may go there, and the cheapest route is the shortest walkable one. Doors, water, and climbs carry small multipliers because they slow a player down, not because of who owns the ground.
7. **Relocation is mandatory.** `de.bsommerfeld.pathetic` → `com.minecraftcitiesnetwork.directions.libs.pathetic`.

## 1. Target architecture

```
com.minecraftcitiesnetwork.directions
├── pathfinding                     (pure Java, no Bukkit imports)
│   ├── BlockGrid                   interface: what the walker needs to know about a block
│   ├── BlockFlags                  bit flags: SOLID, PASSABLE, LIQUID, OPENABLE, CLIMBABLE, HAZARD, UNLOADED
│   ├── WalkerProfile               height, stepUp, maxDrop, canSwim, transition multipliers
│   ├── WalkPath                    immutable node list + cumulative distance + helpers
│   ├── WalkRequest / WalkResult    request (world id, from, to, limits) / result (path | reason)
│   ├── WalkPathfinder              THE ONLY class that imports Pathetic
│   ├── engine/
│   │   ├── GridNavigationProvider  NavigationPointProvider over BlockGrid
│   │   ├── GridEnvironment         EnvironmentContext carrying the BlockGrid + profile
│   │   ├── WalkerNeighbors         10-vector INeighborStrategy
│   │   ├── WalkableValidator       ground below, headroom, single step-up (ported, MIT)
│   │   ├── CornerCutValidator      no squeezing through diagonal walls (ported, MIT)
│   │   ├── DropValidator           max fall height
│   │   └── TransitionCost          multipliers for OPENABLE, LIQUID, CLIMBABLE moves
│   ├── MainThread                  interface: run(Runnable); test impl is synchronous
│   ├── SearchBudget                wall-clock budget + abort wiring
│   └── grid/
│       ├── ArrayBlockGrid          dense in-memory grid (fixtures, captures)
│       ├── GridCodec               .grid.gz read/write (bounding box + flags only, no materials)
│       └── AsciiGrid               tiny multi-layer ASCII DSL for unit tests
├── pathfinding.bukkit              (Bukkit shell)
│   ├── ChunkFlagCache              per-world cache of decoded BlockFlags per chunk (not snapshots), block-event invalidation
│   ├── SnapshotPump                main-thread task: snapshots ≤ N loaded chunks per tick from a queue
│   ├── RegionFileReader            off-thread, read-only Anvil reader for generated-but-unloaded chunks
│   ├── ChunkFlagDecoder            ChunkSnapshot or NBT section → BlockFlags, runs on the search executor
│   ├── CachedBlockGrid             BlockGrid over the cache; bounded waits on misses; UNLOADED for ungenerated
│   ├── BukkitMainThread            MainThread via Bukkit scheduler
│   ├── WalkPathService             façade the rest of the plugin uses; owns executor, metrics
│   ├── WalkingDistanceCache        stop-pair walking distances computed at reload
│   ├── WorldCapture                writes a live region to .grid.gz (debug/capture command)
│   ├── PathParticles               debug rendering of a WalkPath
│   └── PathfindingMetrics          counters + latency histogram for /directions debug stats
├── graph.TransitGraph              accepts precomputed walking distances
├── command.DirectionsCommand       two-stage route (resolve → search → route)
├── command.DebugCommand            /directions debug path|capture|stats|selftest
└── navigation.Session/Service      path-following arrow, replanning, path-based boss bar
```

### 1.1 Threading contract

- **Main thread only:** WorldGuard reads, `Session` mutation, entity teleports, boss bars, `ChunkSnapshot` creation, config reads.
- **Search threads (dedicated `ExecutorService`, 2 threads):** the A\* loop, `BlockGrid` reads from snapshots already in the cache, path post-processing.
- **The hop:** `WalkPathService.find(...)` returns a `CompletableFuture<WalkResult>` that is **always completed on the main thread** through `MainThread.run`. Callers may use `thenAccept` freely.
- **Cache misses:** `CachedBlockGrid` asks `ChunkFlagCache` for the chunk and waits on a future with the remaining budget. The cache resolves it through the tiers in 1.2. A miss costs the search one to a few ticks, never the main thread more than its per-tick slice.
- **Pre-warm:** before dispatching, `WalkPathService` enqueues the chunks along the straight-line corridor (start→target, 2-chunk margin, capped at 64 chunks) so most searches never miss.
- **Directions never force-loads a chunk.** No chunk tickets, no `getChunkAt` on unloaded chunks, no `getChunkAtAsync`. Terrain that is not loaded and not on disk is simply impassable to the walker.
- **Rule enforced in review and by an ArchUnit-style test:** nothing under `pathfinding` (non-`bukkit`) imports `org.bukkit`, and nothing under `pathfinding.engine` is called from a Bukkit callback.

### 1.2 Chunk I/O and the main thread

**What the engine does about chunk loading: nothing.** Pathetic calls `NavigationPointProvider.getNavigationPoint` synchronously from its search thread for every neighbour it expands. It has no concept of a chunk, a world, or a main thread. Whatever the provider does on that call is the integrator's problem. This is by design; the engine is world-agnostic.

**What pathetic-bukkit does** (for reference, since we are not depending on it):

1. Loaded chunk: from the search thread, `getChunkAtIfLoadedImmediately` (reflective) then `Chunk.getChunkSnapshot()` **off the main thread**. Fast, no main-thread cost, but technically racy: the main thread may be mutating the chunk's sections while the snapshot copies them. It works in practice and Paper does not throw on it, but Paper also does not promise it.
2. Generated but unloaded chunk: reads the world's Anvil region file directly, off-thread, read-only, and decodes the block palette itself. No chunk load, no ticket, no main-thread cost. Data can be stale by up to the autosave interval and a read can tear against a concurrent server write, in which case decode fails and it falls through.
3. Never-generated chunk: `getChunkAtAsyncUrgently(...).thenApply(Chunk::getChunkSnapshot).join()`. The search thread blocks; the server loads (or generates) the chunk through its own async chunk system; the snapshot is taken on the thread that completes the load, which is the main thread. "Urgently" jumps the player chunk queue, and the chunk stays loaded on a ticket for a while afterwards.

Plus a decoded per-chunk material cache, corridor preloading via `onPathfindingStart`, neighbour prefetch, and a block-event invalidation listener. Tier 3 is the part that can cause lag: a long search into ungenerated or far-away terrain can pull in dozens of urgent chunk loads and keep them resident.

**What Directions will do.** Same three-tier shape, with the risky edges removed:

| Tier | Condition | Where the work runs | Main-thread cost | Result |
|---|---|---|---|---|
| 1 | Chunk loaded | `SnapshotPump` takes the `ChunkSnapshot` **on the main thread**, ≤ `snapshots-per-tick` per tick (default 8). Decode to flags happens on the search executor; the snapshot is discarded. | ~0.1–0.5 ms per chunk, so 1–4 ms per tick worst case while filling, 0 when warm | Flags cached |
| 2 | Not loaded, region file has it | `RegionFileReader` on the search executor: read-only `FileChannel`, decode 1.18+ section format (`block_states` palette + packed longs) straight to flags | 0 | Flags cached, may be up to one autosave stale |
| 3 | Not on disk | Nothing. Chunk marked `UNLOADED` in the cache for 60 s. | 0 | Impassable; search returns FALLBACK partial or NONE, caller falls back to straight line |

Why not snapshot off-thread like the adapter: the pump costs at most a few milliseconds per tick and only while the cache is cold, and it removes the only data race in the design. Why not `getChunkAtAsync` for tier 3: it makes pathfinding a chunk-loading trigger with tickets, entity loads, and memory that outlive the search, and a city server's interesting terrain is always generated anyway.

**Memory.** A raw `ChunkSnapshot` for a 384-block-tall chunk is on the order of 100–200 KB, so caching 2,048 snapshots would cost hundreds of megabytes. The cache therefore stores one byte of `BlockFlags` per block over a Y band around the surface (heightmap min−16 to max+16, capped at `flag-band-height` = 64) and discards the snapshot after decoding. That is about 16 KB per chunk, roughly 32 MB at the 2,048-chunk ceiling, and the same on-disk shape as the L1 capture files, so the test fixtures and the runtime cache are literally the same format.

**Budget per request.** A 150-block leg touches at most ~20 chunks including margin. Cold, that is 2–3 ticks of pump time at 8 per tick plus a few milliseconds of decode off-thread; warm, it is zero main-thread time. A 600-block `/gps` corridor with a 2-chunk margin is up to ~64 chunks, or 8 ticks of pump time cold. All of it is bounded by `snapshots-per-tick` regardless of how many searches are in flight, because the pump is a single queue.

**Invalidation.** `BlockPlaceEvent`, `BlockBreakEvent`, `BlockPhysicsEvent` (coalesced per chunk per tick), explosions, and `ChunkUnloadEvent` patch the single block's flags when the chunk is cached, or drop the chunk when the change is multi-block. Idle chunks are evicted after `chunk-cache-idle-seconds`.

### 1.3 Walker semantics (what "walkable" means for a player)

| Situation | Flag source | Behaviour |
|---|---|---|
| Standing position | PASSABLE at feet and head, SOLID or top-slab below | Allowed |
| Step up 1 block | previous node on ground, riser adjacent | Allowed (stairs, slabs, kerbs) |
| Drop | feet column | Allowed up to `max-drop` (default 3), else vetoed |
| Doors, fence gates, trapdoors | OPENABLE → PASSABLE | Allowed regardless of open state; 1.5× cost |
| Fences, walls, panes, bars | SOLID and > 1 block tall for collision | Blocked (no jump-over) |
| Water | LIQUID | Passable at 2.5× cost if `can-swim`, else blocked; never counts as ground |
| Lava, fire, cactus, magma, sweet berry bush, powder snow | HAZARD | Blocked |
| Ladders, vines, scaffolding | CLIMBABLE | Vertical move allowed at 2× cost |
| Carpets, pressure plates, snow layers, flowers, grass | PASSABLE | Walk through |
| Unloaded chunk | UNLOADED | Blocked; search may return FALLBACK partial path |

## 2. Test harness design

Six layers. Each phase below states which layers it adds to or must keep green.

### L0 Unit (JUnit 5, pure Java, milliseconds)

- **AsciiGrid DSL.** Multi-layer text maps for readable fixtures:
  ```
  y=64: ####.####      # solid   . air   ~ water   D door   L ladder
        #S..R..T#      S start   T target
  y=65: #.......#
  ```
  Produces an `ArrayBlockGrid`; the test asserts on the path.
- **Path invariants** (`PathAssertions`): consecutive nodes differ by ≤1 on each axis; every node is PASSABLE with headroom; no node is inside SOLID; step-ups ≤1; drops ≤ `max-drop`; path starts and ends at the requested nodes (or at the FALLBACK node, flagged).
- **Validator and cost tests** in isolation with hand-built `EvaluationContext` doubles.
- **Budget tests**: a `BlockGrid` that sleeps per read proves the search is aborted at the budget and the result reports `TIMEOUT`.
- **Main-thread hop**: `SynchronousMainThread` records that completion ran through `MainThread.run`.
- **WalkPath helpers**: remaining distance, look-ahead node, distance off path, all with numeric fixtures.
- **Reference-algorithm check**: on 4-connected uniform-cost grids with diagonals disabled, the path length must equal BFS shortest path (independent implementation in the test). Catches admissibility and neighbour-strategy mistakes.

### L1 Captured-world regression (JUnit, tagged `captured`)

- `WorldCapture` writes `<name>.grid.gz`: bounding box (translated to a local origin) and one `BlockFlags` byte per block over the surface Y band. No materials, names, or world identity. A 200×64×200 capture is ~50–150 KB compressed.
- Real-city captures and their `scenarios.yml` live in `sample-data/captures/` (git-ignored). Each scenario names a start/target pair, expected outcome (FOUND / FALLBACK / NONE), golden length, and forbidden coordinates (e.g. "must not enter the canal"). Synthetic captures from the L5 fixture world live in `src/test/resources/grids/` and are committed.
- L1 is `@EnabledIf` the sample-data folder is present; in CI and on other machines it skips with a visible "sample data not available" message.
- Tests: outcome matches; length within ±10% of golden; forbidden cells untouched; invariants hold; duration ≤ 5× the recorded golden time (loose in CI).
- `./gradlew updateGoldens` rewrites golden lengths/times after an intentional behaviour change; the diff is reviewed like code.

### L2 Property and fuzz (JUnit, tagged `fuzz`, seeded)

- Random mazes and random cave-like grids at several densities; random start/target; for reachable pairs, length equals the BFS reference; for unreachable pairs, the search ends by `MAX_ITERATIONS` or `NONE` within budget; invariants always hold. 200 seeds per run in CI, 5,000 locally on demand.

### L3 Performance (JUnit, tagged `perf`)

- On captured grids: p50/p95 over 50 runs for 50/150/400-block routes; asserts p95 under configurable thresholds (CI: generous; local: tight). Prints a table to the report.
- Concurrency: 16 simultaneous searches on the 2-thread executor finish within 4× single-search p95 and produce identical paths to sequential runs (determinism check).

### L4 Bukkit wiring (MockBukkit, tagged `bukkit`)

- MockBukkit for Paper 1.21 gives a fake server, worlds, players, and a controllable scheduler. Used for: `WalkPathService` completing on the main thread; `Session` state machine (path attach, look-ahead, replan debounce, path drop on waypoint advance); `ChunkFlagCache` invalidation on block events; `SnapshotPump` per-tick slice limit; config parsing of the `pathfinding` section.
- `RegionFileReader` is tested in L0 against region files the test writes itself with a minimal NBT encoder (so no `.mca` is ever committed), plus a deliberately truncated copy for the torn-read path.
- Not used for real chunk data; block reads in MockBukkit are set programmatically.
- Pin the MockBukkit artifact for 1.21 at the start of Phase 1; if it lags Paper 1.21.8 API, restrict L4 to the scheduler and session tests and move cache tests to L5.

### L5 Server smoke (run-paper, headless)

- `xyz.jpenilla.run-paper` 3.1.0 adds `runServer` for Paper 1.21.8 with the built jar. When `sample-data/` is present, `runServer` links the real city world, its `regions.yml`, and the live configs into the git-ignored `run/` directory; otherwise it uses the synthetic fixture world. Phase 0 and all manual checks use the real world locally; CI only ever sees the synthetic one.
- **Fixture world**: a small committed world (`src/serverTest/world.zip`, target ≤ 3 MB) containing a purpose-built course: street grid, a canal with one bridge, a building with doors, stairs and slabs, a fenced yard, a ladder tower, a WorldGuard region file with three stops and a destination.
- **Self-test mode**: `-Ddirections.selftest=scenarios.yml`. On enable the plugin runs each scenario via `WalkPathService`, prints one JSON line per result, writes `selftest-report.json`, and calls `Bukkit.shutdown()` with exit code 0/1. A Gradle task `serverSmoke` runs `runServer --nogui` with a 5-minute timeout and fails on non-zero exit.
- Proves: plugin enables on a real Paper 1.21.8, snapshots and cache work off-thread without console errors, paths match expectations on real block data, `onDisable` leaves no threads (checked by thread dump before shutdown).

### L6 Manual QA (checklist, per release)

- Five real city routes (chosen in Phase 0) walked by a tester with guidance on, screenshots of the arrow at three points each.
- Kill switch: `/directions reload` with `enabled: false` stops guidance within one tick and leaves no armor stands or boss bars behind.
- Spark profiler snapshot during 10 concurrent `/gps` uses shows main-thread time attributable to Directions ≤ 1 ms/tick.

### CI (GitHub Actions, new)

| Job | Trigger | Runs |
|---|---|---|
| `test` | every PR and push | L0, L1, L2 (200 seeds), L4; uploads jar |
| `perf` | nightly + label `perf` | L3 with CI thresholds, posts table to job summary |
| `server-smoke` | nightly + label `server` + release tags | L5 |
| `release` | tag `v*` | build, attach jar, changelog |

## 3. Configuration

```yaml
pathfinding:
  enabled: false                 # master switch; false = today's straight-line behaviour everywhere
  worlds: []                     # empty = all worlds; otherwise allowlist
  routing:
    enabled: true                # use walking distances for start/dest legs and stop-to-stop edges
    max-leg-distance: 300        # blocks; legs longer than this stay Euclidean
    time-budget-ms: 250          # per request, all candidate legs together
    precompute-stop-edges: true  # at reload, in the background
  guidance:
    enabled: true                # arrow follows the path
    look-ahead: 6                # blocks along the path the arrow points to
    replan-distance: 8           # off-path distance that triggers a new search
    replan-cooldown-ms: 2000
    max-search-distance: 600     # beyond this the arrow falls back to straight line
    time-budget-ms: 400
  walker:
    height: 2
    step-up: true
    max-drop: 3
    can-swim: true
    door-cost: 1.5
    water-cost: 2.5
    climb-cost: 2.0
  engine:
    threads: 2
    max-iterations: 200000
  chunks:
    snapshots-per-tick: 8          # tier 1: main-thread snapshot slice
    disk-reader: true              # tier 2: read unloaded chunks from region files off-thread
    flag-band-height: 64           # blocks of Y kept per chunk around the surface
    cache-max-chunks: 2048         # ≈ 32 MB at the default band
    cache-idle-seconds: 90
    unloaded-retry-seconds: 60     # how long a tier-3 miss is remembered
  debug:
    particles: false             # /directions debug path draws particles
    log-searches: false
```

`ConfigLoader` gains a `PathfindingSection` bound with Configurate `@Setting`s, validated like the existing keys (multipliers below 1.0 are rejected because they would break heuristic admissibility; negative numbers are clamped with a warning).

## 4. Phases

### Phase 0 · Spike (2 days)

Goal: prove the in-house provider works on a real Paper 1.21.8 server and that paths in the actual city are worth showing.

- Add the engine dependency and relocation. Build passes.
- Minimal `SnapshotBlockGrid` (main-thread snapshots only, no cache), `GridNavigationProvider`, `WalkerNeighbors`, `WalkableValidator`, `WalkPathfinder`.
- Temporary `/directions debug path <x> <z>` computes and renders a path with particles from the player's position.
- Optionally run the same five routes through pathetic-bukkit's provider for a quality comparison; record but do not depend on it.
- Pick the **five reference routes** with the server owner (short street walk, canal crossing, building interior with doors, stair-heavy route, long cross-city). Capture their areas with a first `WorldCapture` prototype.

Exit: no runtime errors on 1.21.8; ≥4 of 5 routes visibly sane; warm 150-block search ≤ 20 ms; captures load back into `ArrayBlockGrid` and reproduce the same path offline.

### Phase 1 · Core and harness (4–5 days)

Goal: the pure-Java `pathfinding` package complete and tested; no user-visible change.

- `BlockGrid`, `BlockFlags`, `WalkerProfile`, `WalkPath`, `WalkRequest`/`WalkResult`, `SearchBudget`, `MainThread`.
- All validators and costs; `WalkerNeighbors`; `WalkPathfinder` with budget/abort and result mapping (FOUND, FALLBACK, NONE, TIMEOUT, TOO_FAR, UNLOADED).
- `ArrayBlockGrid`, `GridCodec`, `AsciiGrid`, `PathAssertions`.
- Harness: L0 complete (target ≥ 60 tests), L1 with the five captures and `scenarios.yml`, L2 with 200 seeds, L3 with thresholds recorded from Phase 0 hardware.
- GitHub Actions `test` and `perf` jobs.
- Architecture test: no `org.bukkit` import under `pathfinding` except `pathfinding.bukkit`.

Exit: CI green; fuzz suite finds no invariant violations over 5,000 local seeds; L3 p95 recorded as baseline.

### Phase 2 · Bukkit shell (4–5 days)

Goal: production-grade world access and service façade; still behind the flag.

- `ChunkFlagCache` with block-event invalidation (see 1.2), idle eviction, and the `flag-band-height` memory cap; `ChunkFlagDecoder` from `ChunkSnapshot`.
- `SnapshotPump` with per-tick slice (tier 1); `CachedBlockGrid` with bounded waits; corridor pre-warm.
- `RegionFileReader` (tier 2): read-only region-file access, 1.18+ section decoding to flags, torn-read detection by NBT length/compression checks, fall-through to `UNLOADED`. Either port pathetic-bukkit's `world-reader` module (5 classes, MIT, no dependencies) with attribution or write it fresh; decide by size after reading it closely.
- Tick-time guard: a test asserts the pump never exceeds its slice, and `/directions debug stats` reports pump time per tick, disk reads, torn reads, and UNLOADED hits.
- `BukkitMainThread`, `WalkPathService` (executor lifecycle in `onEnable`/`onDisable`/reload), `PathfindingMetrics`.
- `WorldCapture` productised: `/directions debug capture <name> <radius>` writes to `plugins/Directions/captures/`.
- `/directions debug stats`, `/directions debug path`, self-test mode.
- Harness: L4 tests for cache, pump, service hop, config; L5 fixture world and `serverSmoke` task; `server-smoke` CI job.

Exit: `serverSmoke` passes on Paper 1.21.8 in CI; thread dump after `onDisable` shows no Directions threads; Spark shows pump ≤ 4 ms/tick under a 16-search burst; an L5 scenario whose target sits in an unloaded chunk resolves via the disk reader with zero chunk loads (asserted by counting `ChunkLoadEvent`s during the search).

### Phase 3 · Routing integration (3–4 days)

Goal: better route choice using real walking distances.

- `WalkingDistanceCache`: at reload, enqueue all stop pairs within `max-walking-distance × 1.5`; results replace Euclidean weights in `TransitGraph` via a new constructor parameter; graph is rebuilt when the batch completes (log the count and duration).
- `DirectionsCommand.routeAndNavigate` split: `resolveCandidates` (main), `searchLegs` (async, all candidate legs under one budget), `routeAndPresent` (main). Header message is sent immediately; steps follow when ready. Any leg that failed or timed out uses Euclidean and the step text gets a subtle "(approx.)" marker via a new lang key.
- Step text shows path length and initial bearing along the path instead of straight-line distance and bearing.
- Harness: L0 tests for the leg-merging logic with synthetic results (mixed FOUND/TIMEOUT); L1 scenario that flips route choice versus Euclidean (the canal case); L4 test that the header precedes steps and both run on the main thread.

Exit: canal scenario picks the bridge-side stop; a forced-timeout test proves fallback; no change in behaviour when `pathfinding.enabled: false` (golden transcript test of command output).

### Phase 4 · Guided navigation (5–6 days)

Goal: the arrow and boss bar follow the path; `/gps` and `/compass` gain guidance.

- `Session` gains `WalkPath currentPath`, `long lastReplanAt`, `int pathCursor`. `NavigationService.tick` uses `lookAheadNode` for the arrow and `remainingFrom` for the boss bar when a path exists.
- Replanning: off-path > `replan-distance` and cooldown elapsed → new search from the player's current block; keep the old path until the new one lands; on `NONE`/`TOO_FAR` fall back to straight line for that leg and log once.
- Leg change (waypoint advance) drops the path and requests a new one; arrival logic is untouched.
- Region destinations target the nearest PASSABLE block on the region's footprint edge rather than the centroid, computed on the main thread from the WorldGuard region and the snapshot cache.
- `/gps <region>` and `/compass <x> <z>` request a path with `guidance.max-search-distance`; beyond it, straight line as today.
- Harness: L4 session state-machine tests (attach, advance, replan debounce, fallback, stop cleanup); L1 scenario asserting the look-ahead node sequence for a captured route; L5 self-test scenario that simulates a player moving along a path (teleport-step) and asserts arrow targets; L6 checklist executed on the five routes.

Exit: five routes walkable by a tester with no wall hits; kill switch verified; main-thread cost ≤ 1 ms/tick with 10 active guided sessions.

### Phase 5 · Hardening and rollout (2–3 days)

- Metrics exposed in `/directions debug stats` and, optionally, as bStats custom charts (Directions' own, opt-in).
- Memory ceiling test: 2,048 cached chunks ≈ 40–60 MB worst case; document and expose in stats.
- README and `config.yml` documentation; CHANGELOG; bump to 3.0.0.
- Rollout: 3.0.0 ships with `enabled: false`; enable in **shadow mode** first (compute and log, do not display) for one week via `debug.log-searches: true`; then `routing.enabled`; then `guidance.enabled`. Each step is a config reload, not a deploy.

## 5. Work breakdown by file

New (pure Java): `BlockGrid`, `BlockFlags`, `WalkerProfile`, `WalkPath`, `WalkRequest`, `WalkResult`, `WalkPathfinder`, `MainThread`, `SearchBudget`, `engine/*` (7 classes), `grid/*` (3 classes). ~1,400 lines.

New (Bukkit): `ChunkFlagCache`, `ChunkFlagDecoder`, `SnapshotPump`, `RegionFileReader` (~350 lines on its own), `CachedBlockGrid`, `BukkitMainThread`, `WalkPathService`, `WalkingDistanceCache`, `WorldCapture`, `PathParticles`, `PathfindingMetrics`, `DebugCommand`, `DebugBrigadierRegistrar`, `SelfTestRunner`. ~1,700 lines.

Modified: `TransitGraph` (constructor + `buildWalkingEdges`), `DirectionsCommand` (three-stage route, step text), `GpsCommand`, `CompassCommand`, `Session`, `NavigationService` (tick, arrow target, boss bar), `ConfigLoader` (+`PathfindingSection`), `DirectionsPlugin` (service lifecycle, self-test hook), `lang.yml` (+6 keys), `config.yml`, `build.gradle.kts` (dependency, relocation, run-paper, test tags, `updateGoldens`, `serverSmoke`), `.gitignore` (+`bin/`, `run/`).

Tests: ~50 L0, ~12 L1 scenarios, 3 L2 generators, 4 L3 benchmarks, ~15 L4, 8 L5 scenarios. ~2,000 lines plus fixtures.

## 6. Build changes

```kotlin
plugins {
    java
    id("com.gradleup.shadow") version "8.3.5"
    id("xyz.jpenilla.run-paper") version "3.1.0"
}

dependencies {
    implementation("de.bsommerfeld.pathetic:engine:5.5.2")
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
    testImplementation("org.mockbukkit.mockbukkit:mockbukkit-v1.21:<pin in Phase 1>")
}

tasks {
    shadowJar {
        relocate("de.bsommerfeld.pathetic", "com.minecraftcitiesnetwork.directions.libs.pathetic")
    }
    test { useJUnitPlatform { excludeTags("perf", "fuzz-long") } }
    register<Test>("perfTest") { useJUnitPlatform { includeTags("perf") } }
    register<Test>("fuzzLong") { useJUnitPlatform { includeTags("fuzz-long") } }
    register<JavaExec>("updateGoldens") { /* rewrites scenarios.yml goldens from ArrayBlockGrid runs */ }
    runServer {
        minecraftVersion("1.21.8")
        downloadPlugins { /* WorldGuard + WorldEdit for 1.21.8 */ }
    }
    register("serverSmoke") { dependsOn(shadowJar) /* runServer --nogui with selftest system property and timeout */ }
}
```

## 7. Risk register (delta from the study)

| Risk | Mitigation in this plan |
|---|---|
| Snapshot creation on the main thread spikes tick time | `SnapshotPump` slice limit, corridor pre-warm, cache; measured by L3 concurrency test and Spark in L6 |
| A search waits forever on a chunk that never loads | Bounded wait tied to `SearchBudget`; result `UNLOADED`; straight-line fallback; Directions never requests a chunk load |
| Disk reader returns stale terrain (edited but not yet autosaved) | Acceptable for guidance; loaded chunks always win over disk; invalidation drops a disk-sourced chunk when it later loads |
| Disk reader tears against a concurrent server write | Length and compression header checks; decode failure marks the chunk `UNLOADED` for 60 s instead of caching garbage |
| Flag cache memory grows with a large view distance or many players | Y-band flags at ~16 KB per chunk, hard ceiling `cache-max-chunks`, idle eviction, size in `debug stats` |
| The live world or its captures reach the public repository | Everything real lives in git-ignored `sample-data/`; `.gitignore` also blocks `run/`, `*.mca`, `*.grid.gz`, `level.dat`; captures hold flags only at a local origin so even a leak carries no materials or names |
| MockBukkit lags Paper 1.21.8 | L4 restricted to scheduler/session tests; cache tests move to L5 |
| Golden tests flake on timing | Timing assertions only in L3 with CI-specific thresholds; L1 asserts outcome, length, forbidden cells |
| Pathetic breaking release | Version pinned; one wrapper class; L0/L1 catch semantic drift on upgrade |
| Executor leak across `/reload` | Owned executor shut down in `onDisable`; L5 thread-dump assertion |

## 8. Definition of done (3.0.0)

- All six harness layers exist and run in CI as specified.
- `pathfinding.enabled: false` produces byte-identical command output to 2.1.0 (golden transcript).
- With the flag on: five reference routes pass L1 and L6; `serverSmoke` green on Paper 1.21.8; L3 p95 for a 150-block warm search ≤ 25 ms on CI hardware.
- Docs: README section, config reference, this plan updated with actuals, CHANGELOG.

## 9. Open items for the project owner

1. Confirm the five reference routes at the start of Phase 0.
2. Decide whether Directions should report its own opt-in bStats charts in Phase 5.

## 10. Local sample data

Provided by the owner, never committed. Expected layout, read by Gradle from `sample-data/` at the repo root or from `DIRECTIONS_SAMPLE_DATA` if set:

```
sample-data/
├── world/<world-name>/        region/, level.dat, entities/ (playerdata, stats, advancements not needed)
├── worldguard/regions.yml      plugins/WorldGuard/worlds/<world-name>/regions.yml from the live server
├── config/config.yml           live Directions config (lines and stops)
├── config/names.yml            live display names
├── captures/*.grid.gz          generated locally by /directions debug capture
└── captures/scenarios.yml      real-route scenarios and goldens
```

Guard in place today: `.gitignore` covers `sample-data/`, `run/`, `*.grid.gz`, `*.mca`, `level.dat`, `session.lock`, and `bin/`.
