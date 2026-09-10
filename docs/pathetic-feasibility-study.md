# Feasibility Study: Integrating Pathetic into Directions

| | |
|---|---|
| Date | 2026-09-10 |
| Subject | [bsommerfeld/pathetic](https://github.com/bsommerfeld/pathetic) 5.5.2 and [bsommerfeld/pathetic-bukkit](https://github.com/bsommerfeld/pathetic-bukkit) 5.5.2 |
| Target | Directions 2.1.0, Paper 1.21.8, Java 21 |
| Verdict | **Feasible for one job: block-level walking guidance.** Not a replacement for the transit graph. Proceed with a 1–2 day runtime spike before committing. |

## 1. Summary

Pathetic is a pure-Java grid A\* engine. It has no Bukkit dependency, ships on Maven Central under MIT, and in a local proof of concept it compiled on the Directions Java 21 toolchain, relocated cleanly under the shadow plugin, and found a 1,500-block synthetic path in about 17 ms.

Directions does not have a pathfinding problem at the transit layer. Its transit graph has tens of nodes and its Dijkstra and A\* already finish in well under a millisecond. Where Directions is weak is everything *between* the graph nodes: the walk from the player to the first stop, walking transfers between stops, and the walk from the last stop to the destination. All of those are straight-line 2D estimates today, and the guidance arrow points straight at the waypoint regardless of walls, rivers, or buildings.

That gap is exactly what Pathetic covers. The recommendation is a phased integration behind a config flag with the current straight-line behaviour as the fallback, gated on a short spike against the real city world, because the Paper world adapter (pathetic-bukkit) is the least mature part of the stack and has not been verified on Paper 1.21.8.

## 2. The two sides

### 2.1 Directions today

Routing lives in [TransitGraph.java](../src/main/java/com/minecraftcitiesnetwork/directions/graph/TransitGraph.java). Stops are WorldGuard regions reduced to a 2D centre point. Edges are built at reload: transit hops between stops on the same line (cost = 2D distance + transfer penalty, or a flat penalty for `fixed` lines) and walking edges between any two stops within `max-walking-distance` that share a transit kind. Per request, [DirectionsCommand.routeAndNavigate](../src/main/java/com/minecraftcitiesnetwork/directions/command/DirectionsCommand.java) attaches a synthetic start node and destination node using straight-line distances from [PlayerPositionResolver](../src/main/java/com/minecraftcitiesnetwork/directions/resolver/PlayerPositionResolver.java), then runs Dijkstra (when any line is `fixed`) or A\*.

Guidance lives in [NavigationService.java](../src/main/java/com/minecraftcitiesnetwork/directions/navigation/NavigationService.java). Every tick it builds a floating arrow out of marker armor stands six blocks ahead of the player and aims it at the current waypoint's centre. The boss bar shows straight-line distance to the waypoint.

Consequences visible to players:

- "Walk to Market (140 blocks NE)" may cross a river, a wall, or a private build.
- Route choice can be wrong when geometry lies. A stop 120 blocks away across a canal is preferred over one 130 blocks away by road.
- The arrow points *at* the goal, not *along* the way to it. In a dense city this is often the wrong direction to walk.
- `/gps` and `/compass` are the same straight-line arrow with no transit step.

Baseline measured in this study: 2,852 lines of Java, 6 unit tests (all passing), 896 KB shadow jar.

### 2.2 Pathetic 5.5.2 (the engine)

| Property | Finding |
|---|---|
| What it is | Grid A\* over `PathPosition` (x, y, z doubles). Neighbours come from an `INeighborStrategy` list of offset vectors. Not a weighted-graph library. |
| Coupling | Zero references to `org.bukkit`, `net.minecraft`, or `io.papermc` in either module. The Bukkit adapter was split out in 5.0.0. |
| Artifacts | `de.bsommerfeld.pathetic:api` (54 KB) and `:engine` (621 KB, of which ~350 classes are a shaded fastutil subset). Maven Central. |
| Bytecode | Java 8 class files (major version 52). Runs on 21. |
| License | MIT. |
| Release cadence | 5.4.0 Oct 2025 → 5.5.2 June 2026. Ten releases in eight months; several breaking API changes along the way (5.4.1, 5.4.5, 5.5.0). Last non-bot commit 2026-06-16. |
| Maintainers | One primary author (~1,200 of ~1,580 commits), one secondary (~235). 390 stars, 27 forks. |
| Open issues | None. The five open items are all Dependabot PRs. |
| Docs | GitHub wiki (16 pages) and hosted Javadoc at docs.pathetic.site. |
| Extension points | `NavigationPointProvider` (is this block passable?), `ValidationProcessor` (hard veto), `CostProcessor` (soft penalty), `INeighborStrategy`, `IHeuristicStrategy` + weights, `PathfinderHook`, `EnvironmentContext` (per-search payload). |
| Safety limits | `maxIterations` (default 5,000), `maxLength` (0 = unlimited), `fallback` (return best partial path to the nearest reachable node), cooperative `abort()`. |
| Async model | `findPath` returns a `PathfindingSearch` wrapping a `CompletableFuture`. With `async(true)` the search runs on the configured `ExecutorService`, defaulting to a JVM-wide work-stealing pool sized cores/2. |
| Callback thread | `ifPresent` / `orElse` are `thenAccept` without an executor: they fire on the **pathfinding thread**, or on the caller if the search already finished. Nothing hops back to the Bukkit main thread. |
| Tests | JUnit suites in api and engine, JMH benchmarks, and an end-to-end integration-test module. |

### 2.3 pathetic-bukkit 5.5.2 (the Paper world adapter)

| Property | Finding |
|---|---|
| What it is | The piece that turns Bukkit world data into `NavigationPoint`s. Chunk snapshot cache with heat-based eviction, an off-thread Anvil region-file reader for generated-but-unloaded chunks, corridor preloading, block-change invalidation listener, and ready-made processors. |
| Distribution | **JitPack only** (`com.github.bsommerfeld.pathetic-bukkit:core:5.5.2`). Not on Maven Central. The 5.5.2 build exists and the jar is 843 KB; it shades the engine, api, and world-reader into `core`, so Directions would depend on `core` alone. |
| Compiled against | `spigot-api 26.1-R0.1-SNAPSHOT` and `paper-api 26.2`. Minecraft moved to calendar versions after 1.21; Directions runs 1.21.8. Bytecode is Java 8 so it loads, and the API calls it makes (`ChunkSnapshot.getBlockData`, `getChunkAtAsyncUrgently`, `isChunkLoaded`) all exist on 1.21.8. `getChunkAtIfLoadedImmediately` is resolved reflectively for exactly this reason. **Unverified at runtime on 1.21.8.** |
| Version detection | Parses `Bukkit.getVersion()` and handles both `1.x.y` and `YY.x.y`. On Paper it always picks `PaperChunkDataProvider`; the per-version NMS modules are only for Spigot. |
| Lifecycle | Requires `PatheticBukkit.initialize(plugin)` in `onEnable` and `PatheticBukkit.shutdown()` in `onDisable`. Holds static, JVM-lifetime state (chunk cache, prefetch pool, sweeper thread). |
| Telemetry | Registers a bStats metrics client (plugin id 29080) unconditionally in `initialize`. Honours the server-wide bStats opt-out; there is no library-level switch. |
| Walkability model | `isTraversable() = !material.isSolid()`. `WalkableProcessor(height, allowStepUp)` requires solid ground below and clear headroom, and permits one-block step-ups so stairs work with axis-aligned moves. `DiagonalAccessProcessor` stops corner-cutting through diagonal walls. |
| Health | 20 stars, last commit 2026-08-12 (Dependabot), no open issues. Same single maintainer. |

## 3. Where Pathetic fits, and where it does not

| Concern in Directions | Today | With Pathetic | Value |
|---|---|---|---|
| Transit search (stop to stop along lines) | Dijkstra / A\* over ≤ ~100 nodes, sub-millisecond | **No change.** Pathetic searches a grid, not a weighted graph with named edges. | None |
| Stop-to-stop walking edge weights | 2D Euclidean if ≤ `max-walking-distance` | Real walking distance, precomputed once per reload | Medium, cheap |
| Player → first stop, last stop → destination | 2D Euclidean per candidate stop | Real walking distance per candidate, bounded search | Medium: fixes wrong-stop choices |
| Guidance arrow on walking legs | Points straight at waypoint centre | Points along the path (look-ahead node) and re-plans if the player strays | **High: the visible win** |
| `/gps`, `/compass` | Straight line | Guided path with fallback to straight line | High |
| Boss bar remaining distance | Straight line | Remaining path length | Medium |
| Step-by-step text ("140 blocks NE") | Straight line | Path length and initial bearing | Low |
| Riding a bus or train between stops | Not simulated | Not applicable; Pathetic would not be invoked on transit hops | None |

## 4. What was verified

A throwaway Gradle project in the session scratchpad depended on `de.bsommerfeld.pathetic:engine:5.5.2` with the same Java 21 toolchain and shadow plugin version as Directions, relocated `de.bsommerfeld.pathetic` to `com.minecraftcitiesnetwork.directions.libs.pathetic`, and ran against a synthetic provider (flat ground with wall stripes every 40 blocks, each with a 3-block gap).

| Check | Result |
|---|---|
| Maven Central resolution, compile, run on JDK 21.0.10 | OK |
| Shadow relocation of engine + api | OK, no split-package conflicts |
| Jar growth | +708 KB relocated (engine 621 KB + api 54 KB + wrapper). Directions would go from 896 KB to roughly 1.6 MB, or roughly 1.75 MB with pathetic-bukkit `core`. |
| 50-block path, 8-direction flat strategy, sync | 67 nodes, 2.0 ms |
| 150-block path (Directions' default `max-walking-distance`) | 153 nodes, 3.0 ms |
| 500-block path | 512 nodes, 10.5 ms |
| 1,500-block path | 1,519 nodes, 17.3 ms |
| Unreachable target with `maxIterations(20_000)` | `MAX_ITERATIONS_REACHED` after 14.8 ms, fallback path of length 1 returned |
| Async search on the shared pool | Completed; callback delivered |
| Directions baseline | `./gradlew test`: 3 classes, 6 tests, 0 failures |

These timings exclude world access. Pathetic's own README puts a 20,000-position search at ~43 ms inside a real Paper server versus ~16 ms raw, and attributes the difference to chunk access. Expect Directions' real numbers to be dominated by chunk snapshotting on cold chunks, not by the search.

**Not verified, and the reason for the Phase 0 spike below:** pathetic-bukkit loading and running on a live Paper 1.21.8 server, and the quality of the paths it produces in the actual city world (doors, stairs, slabs, canals, private builds).

## 5. Recommended integration design

Keep `TransitGraph` and the command flow as they are. Add one new package and touch three existing classes.

### 5.1 New: `pathfinding` package

- **`WalkPathService`** owns a single `Pathfinder`, a dedicated `ExecutorService` (2 threads is plenty for a transit plugin; do not share the prefetch pool), and the configuration. It exposes `CompletableFuture<Optional<WalkPath>> find(World, Location from, Location to, int maxLength)` and always completes the future **on the main thread** via `Bukkit.getScheduler().runTask`, because Pathetic's callbacks fire on its own pool. It also imposes a wall-clock budget (for example 250 ms) and aborts searches that exceed it.
- **`WalkPath`** is an immutable list of block centres plus cumulative distances, with helpers: `remainingFrom(Location)`, `lookAheadNode(Location, double blocks)`, and `distanceOffPath(Location)`.
- **Provider.** Two options, decided at the end of Phase 0:
  1. pathetic-bukkit's `LoadingNavigationPointProvider`. Least code; brings the chunk cache, disk reader, and invalidation listener for free. Costs a JitPack dependency, static global state, bStats telemetry, and the 26.x-API compilation risk.
  2. An in-house provider (~150–250 lines): `ChunkSnapshot` per loaded chunk, cached per search via `EnvironmentContext`, taken on the main thread or via `getChunkAtAsync`, treating unloaded chunks as impassable. Directions only ever paths near online players, so loaded-chunk-only is acceptable. Zero extra dependency beyond the engine, no telemetry, no static state.
- **Neighbour strategy.** A custom 10-vector list: the four cardinals, four flat diagonals, and ±Y. `DIAGONAL_3D` (26 neighbours) wastes work for a walker; `VERTICAL_AND_HORIZONTAL` forbids diagonals and produces staircase zig-zags.
- **Validators.** `WalkableProcessor(height = 2, allowStepUp = true)` and `DiagonalAccessProcessor(2)` from pathetic-bukkit (or their ~120-line equivalents in-house), plus Directions-specific ones:
  - `OpenableValidator`: doors, fence gates, and trapdoors are `isSolid()` regardless of state, so without this no path can enter a building. Treat `Openable` blocks as passable.
  - Optional `WorldGuardEntryValidator`: veto regions with `entry: deny` for the player. Cheap to add later.
- **Cost processors.** `SurfacePreferenceCost`: configurable list of road materials that cost 1.0 while everything else costs, say, 1.6. This is what makes paths follow streets instead of cutting across lawns. `WaterCost` to discourage swimming without forbidding it.
- **Falling.** Walking downhill more than three blocks should be penalised or vetoed by a small validator; the stock processors allow any drop.

### 5.2 Changes to existing classes

- **`TransitGraph.buildWalkingEdges`**: accept an optional `Map<pair, Double>` of precomputed walking distances and use it in place of `distance2D` when present. The map is produced asynchronously at reload by `WalkPathService` for every stop pair within `max-walking-distance × 1.5` and cached until the next reload. Until it is ready, the Euclidean graph is used, so reload stays instant.
- **`DirectionsCommand.routeAndNavigate`**: becomes two-stage. Stage one (main thread) resolves candidates exactly as today. Stage two fires bounded searches player → each candidate start stop and each candidate destination stop → destination, then routes on the main thread when they complete or time out, substituting Euclidean for any leg that failed. The player sees the "Here is how to get to…" header immediately and the steps ~100–300 ms later.
- **`Session` / `NavigationService.tick`**: `Session` gains a nullable `WalkPath` for the current leg. When present, the arrow targets `lookAheadNode(player, 6)` instead of the waypoint centre, and the boss bar uses `remainingFrom(player)`. If `distanceOffPath > replan-distance` (default 8 blocks) and the last re-plan was more than 2 s ago, request a new path; keep pointing at the stale path until it arrives. When the leg's waypoint changes, drop the path and request a new one. Arrival detection is unchanged and still uses WorldGuard regions.

### 5.3 Configuration

```yaml
pathfinding:
  enabled: false              # straight-line behaviour when false or when a search fails
  max-search-distance: 400    # blocks; searches beyond this fall back to straight line
  time-budget-ms: 250
  replan-distance: 8
  look-ahead: 6
  threads: 2
  road-materials: [stone_bricks, smooth_stone, gray_concrete, andesite, cobblestone, ...]
```

### 5.4 Build

```kotlin
// engine only (option 2 provider)
implementation("de.bsommerfeld.pathetic:engine:5.5.2")

// or with the Paper adapter (option 1 provider)
repositories { maven("https://jitpack.io") }
implementation("com.github.bsommerfeld.pathetic-bukkit:core:5.5.2")

shadowJar { relocate("de.bsommerfeld.pathetic", "com.minecraftcitiesnetwork.directions.libs.pathetic") }
```

Relocation is mandatory: another plugin on the same server may ship its own Pathetic, and the library's static state must not be shared.

## 6. Phased plan

| Phase | Scope | Exit criterion | Effort |
|---|---|---|---|
| **0. Spike** | Add pathetic-bukkit to a branch. Temporary `/directions debugpath <x> <z>` draws the path with particles. Run on a Paper 1.21.8 test server with a copy of the city world. Time five representative routes cold and warm. | No `NoSuchMethodError` / `NoClassDefFoundError`; ≥ 4 of 5 routes visibly sane; warm 150-block search ≤ 20 ms. Decide provider option 1 vs 2. | 1–2 days |
| **1. Better routes** | `WalkPathService`, `WalkPath`, precomputed stop-to-stop walking edges, two-stage `routeAndNavigate`, config flag, unit tests with synthetic providers. | Route choice uses real walking distances; straight-line fallback proven by a test that forces a search failure. | 3–5 days |
| **2. Guided arrow** | `Session` path state, look-ahead arrow, path-based boss bar, re-planning, `/gps` and `/compass` guidance. | Players following the arrow reach the waypoint without hitting a wall on the five spike routes. | 5–8 days |

Total: roughly two to three weeks of part-time work, with a clear stop point after Phase 0.

## 7. Risks and mitigations

| # | Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|---|
| 1 | pathetic-bukkit compiled against 26.x API; a signature used somewhere fails on 1.21.8 at runtime | Medium | High (plugin fails to enable) | Phase 0 spike on real server. Fallback: provider option 2 (engine only), which removes the adapter entirely. |
| 2 | Single-maintainer libraries; rapid breaking releases (three in eight months) | Medium | Medium | Pin exact versions. Keep Pathetic behind `WalkPathService` so only one class touches its API. MIT allows vendoring the ~30 adapter classes if the repo goes quiet. |
| 3 | JitPack-only distribution for pathetic-bukkit; builds can be evicted or fail | Medium | Medium (CI break) | Prefer option 2, or vendor, or mirror the jar in a private repo. |
| 4 | Callbacks fire off the main thread; WorldGuard, sessions, entities are main-thread only | Certain | High if missed | `WalkPathService` is the only caller and always re-dispatches via the scheduler. Code-review rule: no Bukkit API in any Pathetic callback. |
| 5 | Path realism: doors/gates read as solid; water is passable air; drops are unlimited; ladders unusable | High | Medium (silly paths) | `OpenableValidator`, `WaterCost`, drop validator; road-material cost processor. Iterate on the five spike routes. |
| 6 | Cold-chunk cost. Far destinations in unloaded chunks stall a search on chunk I/O | Medium | Medium (latency) | Time budget + `abort()`; `maxLength`; straight-line fallback; pathetic-bukkit's disk reader if option 1. |
| 7 | Memory: adapter's chunk cache is heap-scaled and static | Low | Medium | `ChunkCacheConfiguration.maxCachedChunks` small (city is compact); `PatheticBukkit.shutdown()` in `onDisable`. |
| 8 | Region-file reads from the adapter's disk reader race the server's own writes | Low | Low (decode fails, falls through to server load) | Accept; or option 2 avoids it entirely. |
| 9 | bStats telemetry from a library inside Directions | Certain with option 1 | Low (policy) | Server-wide bStats opt-out, or option 2 / vendoring without `BStatsUtil`. |
| 10 | Player movement modes (sprint-jumping, horses, boats, elytra) do not match a 1-block-step walker | Certain | Low | Guidance is advisory; re-planning absorbs shortcuts. |
| 11 | Two plugins ship Pathetic unrelocated | Low | High (class clash) | Relocate always. |

## 8. Alternatives considered

- **Status quo (straight lines).** Zero cost, and adequate on open maps. Fails in dense cities, which is Directions' target.
- **Paper's entity `Pathfinder` API** (`com.destroystokyo.paper.entity.Pathfinder`). Designed for mobs within follow range on the main thread; cannot path a player 1,000 blocks. Not applicable.
- **In-house A\* over `ChunkSnapshot`.** The engine is the hard, easily-wrong part (heap, closed set, reopening, allocation pressure). Pathetic's engine is 30 classes, benchmarked and tested. Do not rewrite it. The *provider* side is the small part and is a legitimate in-house candidate (option 2).
- **Hand-authored road graph in config** (waypoint nodes along streets). No dependency, fully controllable, but every street change needs a config edit. Could complement Pathetic later as preferred corridors; not a substitute.
- **Vendoring pathetic-bukkit's core classes** under MIT. Removes the JitPack and telemetry concerns at the cost of tracking upstream fixes by hand. Reasonable if Phase 0 shows option 1's provider is clearly better than an in-house one.

## 9. Decision

Proceed with Phase 0. It is one to two days and answers the only two open questions: does the Paper adapter run on 1.21.8, and are the paths good enough in this city to be worth showing players. If both are yes, Phase 1 and 2 are well-scoped and low-risk because everything sits behind `WalkPathService` and a config flag with the existing behaviour as the fallback.

## Appendix A. Sources

- Pathetic repository, README, wiki, releases: https://github.com/bsommerfeld/pathetic
- Pathetic 5.0.0 release notes (Bukkit module split): https://github.com/bsommerfeld/pathetic/releases/tag/5.0.0
- pathetic-bukkit repository and README: https://github.com/bsommerfeld/pathetic-bukkit
- Maven Central artifacts: https://central.sonatype.com/search?q=de.bsommerfeld.pathetic
- Hosted Javadoc: https://docs.pathetic.site/
- Proof-of-concept and cloned sources: session scratchpad (`poc/`, `pathetic/`, `pathetic-bukkit/`), not committed.
