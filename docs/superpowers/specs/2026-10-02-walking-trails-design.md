# Walking Trails — Design

| | |
|---|---|
| Date | 2026-10-02 |
| Branch | `feature/walking-trails`, cut from `main` |
| Replaces | `feature/pathetic` (abandoned; nothing carried over) |
| Target | Paper 1.21.8, Java 21 |

## 1. Problem and goals

Today the only guidance a navigating player gets is a floating arrow that points in a straight line at the
next waypoint. It points through walls, canals and buildings and says nothing about how to actually get
there on foot.

**Goal:** during every *walking* part of a trip, draw a trail on the ground that the player can follow.

Requirements, as agreed:

1. **Walking legs only.** A trip is made of legs. Walking legs get a trail: start → first stop, walking
   transfers between stops, last stop → destination. Ride legs (stop to stop on a line, travelled by
   teleport) get no trail and no search.
2. **All navigation modes.** `/directions start …` (transit and walk-only results), `/gps` and
   `/compass` all get trails.
3. **Any distance in one world.** The world is about 7,500 blocks in radius. A trip may start at one edge
   and end at the other, and a `/gps` walk may be thousands of blocks long. Cross-world trips are out of
   scope.
4. **Computed on arrival.** A walking leg's route is computed when the player reaches the start of that
   leg, from live terrain around them. Nothing is computed for distant legs up front. Route *choice*
   (which stops to use) is unchanged from `main` and uses straight-line distances.
5. **Physical routing.** The trail takes the cheapest physically walkable route. No preference for roads,
   no awareness of plots or regions. Cost reflects only what slows a player: distance, water, climbing,
   doors.
6. **Load.** 20–30 players navigating at once must have no noticeable effect on tick time.
7. **Never worse than `main`.** Whenever a trail is unavailable, behaviour is exactly today's: straight
   arrow, same messages, same boss bar. With the feature disabled, the plugin behaves identically to
   `main`.

Non-goals: road or region preferences, cross-world trips, precomputing paths, reading chunks from disk,
loading chunks, turn-by-turn text instructions.

## 2. Approach

**A rolling local search over loaded terrain.** Each search covers a bounded window around the player
(default 160 × 160 blocks, never beyond loaded chunks). If the leg's goal is inside the window, the route
reaches it exactly. If not, the route ends at the best exit on the window's edge toward the goal, and is
extended by later searches as the player walks and the window moves with them.

Considered and rejected for now:

- *Coarse whole-world map plus local detail* (hierarchical). Better for very long walks with large
  obstacles (a river whose only bridge is far to the side), but needs reading region files, a persistent
  ~200k-chunk cache kept in sync with edits, and a second algorithm. Roughly 2–3× the work. The design
  keeps a slot for it (§11).
- *Each whole leg computed up front from region files.* Contradicts requirement 4; stale data and slow
  cold starts.

**Engine: a hand-written A\*.** A bounded-window search is a few hundred lines that can be tested
exhaustively. No external pathfinding library is used; there is nothing a library adds that this
problem needs, and wrapping one previously required workarounds for time budgets, relocation and
thread-local state.

## 3. Trip model: legs

`Waypoint` (existing sealed interface: `Region`, `Coordinates`) stays. Navigation gains a leg list:

```java
public enum LegKind { WALK, RIDE }
public record Leg(Waypoint target, LegKind kind) {}
```

`Session` holds `List<Leg>` instead of `List<Waypoint>`; `currentWaypoint()` and friends keep working by
reading `leg.target()`. `ArrivalPolicy` is unchanged and still decides when a leg is complete.

**Classification** happens in a new pure function, `LegPlanner.plan(List<String> pathNodes, Waypoint
finalWaypoint, BiPredicate<String,String> isTransitHop)`, replacing `DirectionsCommand.buildWaypoints`.
For each consecutive pair of route nodes `(a, b)`:

| Pair | Leg target | Kind |
|---|---|---|
| `START → stop` | stop | `WALK` |
| `stop → stop`, `isTransitHop` true | second stop | `RIDE` |
| `stop → stop`, `isTransitHop` false (walking transfer) | second stop | `WALK` |
| `stop → DEST` | final waypoint | `WALK` |

If the player starts inside their first stop, the first `WALK` leg completes on the first tick through
the existing arrival logic, as it does today. Walk-only results, `/gps` and `/compass` produce a single
`WALK` leg to the final waypoint.

## 4. Package layout and units

```
com.minecraftcitiesnetwork.directions
├── walk                         pure Java, no org.bukkit imports (enforced by a test)
│   ├── Cell                     record(int x, int y, int z); y is the feet position
│   ├── Footing                  enum GROUND, WATER, CLIMB
│   ├── Surface                  one walkable spot in a column: y, footing, headroom, flags (door)
│   ├── ColumnChunk              16×16 columns of Surface[] for one chunk; immutable
│   ├── Terrain                  interface: surfaces(x, z) → Surface[] or UNKNOWN; isKnown(x, z)
│   ├── BlockKind                enum of what a block is to a walker (SOLID, OPEN, DOOR, WATER, CLIMB, HAZARD, FENCE_LIKE, …)
│   ├── ColumnDecoder            (chunk-local BlockKind source) → ColumnChunk
│   ├── WalkRules                legal moves between surfaces of neighbouring columns, and their cost
│   ├── Goal                     interface: contains(Cell), heuristic(Cell); RegionGoal, PointGoal
│   ├── WindowSearch             A* inside a window; returns SearchResult
│   ├── SearchResult             COMPLETE(route) | PARTIAL(route, exit) | NO_ROUTE | BUDGET | INVALID_START
│   ├── Route                    immutable cells + cumulative distance; nearest, remaining, lookAhead
│   └── GuidePolicy              the WalkGuide's decisions as a pure state machine (clock and searches injected)
├── walk.bukkit
│   ├── MaterialKinds            Material → BlockKind, memoised by ordinal
│   ├── ChunkColumnCache         per-world shared cache of ColumnChunk; snapshot queue; invalidation
│   ├── CacheTerrain             Terrain over ChunkColumnCache for one search
│   ├── WalkSearchService        two-thread search pool; completes futures on the main thread
│   ├── WalkGuide                one per active WALK leg; drives GuidePolicy, owns the current Route
│   ├── TrailRenderer            per-player particles along the Route
│   └── WalkStats                counters and timings for /directions debug walk
└── navigation                   existing; Session holds legs, NavigationService attaches WalkGuides
```

Each unit's contract:

- **`Terrain`**: read-only, thread-safe snapshot view. `surfaces(x, z)` returns the column's walkable
  surfaces sorted by y, or a sentinel `UNKNOWN` when the chunk is not captured. Unknown columns are
  impassable.
- **`WalkRules`**: given a surface in column A and a neighbouring column B (8 directions, plus vertical
  moves within a climbable column), lists the reachable surfaces in B with their move cost. Has no state.
- **`WindowSearch`**: `search(Terrain, Cell start, Goal goal, Window window, Budget budget) →
  SearchResult`. Deterministic for identical inputs.
- **`GuidePolicy`**: inputs are events (tick with player position, search result, chunk-capture
  readiness, terrain change on route, teleport); outputs are actions (request search, replace route,
  show/hide trail, send the "no route" message). No Bukkit, no threads.
- **`WalkGuide`**: the thin Bukkit adapter around `GuidePolicy`.

## 5. Terrain

### 5.1 Columns of surfaces

Each x,z column stores only the heights where a player can be, as `Surface` entries:

| Footing | Condition at feet height `y` |
|---|---|
| `GROUND` | block at `y−1` supports standing (full solid, top slab, stair, farmland, path, etc.); blocks `y` and `y+1` are passable |
| `WATER` | block at `y` is water and block at `y+1` is passable: swimming at the top of a water column (underwater routes are not modelled) |
| `CLIMB` | block at `y` is a ladder, vine or scaffolding and `y+1` is passable or climbable |

`headroom` is the number of passable blocks above the feet, capped at 4; a step up needs headroom ≥ 3
at the origin. Columns are full world height, so basements, metro platforms and rooftops are all
represented with no height band. A typical column has one to three surfaces.

Block classification (`BlockKind`):

- **Passable:** air, carpets, pressure plates, buttons, torches, signs, flowers, grass, snow layers ≤ 1,
  rails, open-or-closed doors, fence gates and trapdoors (doors are marked so the cost multiplier
  applies).
- **Blocking:** full solids, fences, walls, panes, iron bars (collision taller than one block; no
  jumping over them).
- **Hazards, never entered or stood on:** lava, fire, soul fire, cactus, magma block, sweet berry bush,
  powder snow, campfires, wither rose.

### 5.2 Capture and caching

`ChunkColumnCache`, one per world, shared by all players:

- **Loaded chunks only.** A request for a chunk that is loaded enqueues it. A main-thread task takes at
  most `snapshots-per-tick` `ChunkSnapshot`s per tick (`getChunkSnapshot(false, false, false)`). Decoding
  to `ColumnChunk` runs on the search pool; the snapshot is then discarded.
- **Never loads.** Unloaded chunks are never loaded, never read from disk, never ticketed. They are
  `UNKNOWN` (impassable).
- **Invalidation.** Block place, break, physics, explosion, piston and fluid-flow events mark the chunk
  dirty and re-queue it (coalesced to once per chunk per tick). `ChunkUnloadEvent` evicts.
- **Bounds.** At most `cache-max-chunks` entries (default 4,096); least-recently-used eviction. A
  `ColumnChunk` is a few KB, so the cache stays in the tens of MB at most.
- **Readiness.** `request(window)` returns a future completing when every *loaded* chunk in the window
  is decoded, or after `capture-wait-ms` (default 1,500 ms), whichever is first. The search runs on
  whatever has been captured by then.

## 6. Search

### 6.1 Moves and costs

From a surface at column (x, z) to the 8 neighbouring columns, and vertically within a climbable column:

- **Level or down:** any surface in the neighbour from `y` down to `y − max-drop` (default 3), provided
  the body fits through the gap (the neighbour column is passable from the target surface's feet up to
  `y + 1`).
- **Step up 1:** to a neighbour surface at `y + 1` if the origin's headroom is ≥ 3.
- **Climb:** within a `CLIMB` column, up or down one block; off the top onto an adjacent `GROUND`
  surface.
- **Swim:** `WATER` to adjacent `WATER` or `GROUND` within ±1.
- **Diagonal:** level moves only, and only if both orthogonal side columns have a surface at the same
  height (no corner-cutting past wall corners or fence posts).

Cost = Euclidean move length × multiplier: water `2.5`, climbing `2.0`, entering a door `1.5`, step up
`+0.5` flat. Every multiplier is ≥ 1, so straight-line distance stays an admissible heuristic.

### 6.2 Window search

`WindowSearch` is A\* over surfaces inside a square window centred on the start, `window-radius` blocks
each way (default 80, giving 160 × 160), clipped to captured chunks.

- **Goals.** Each goal matches `ArrivalPolicy`, so the trail ends exactly where the leg counts as done.
  A configured stop accepts any cell inside its footprint or within `stop-arrival-buffer` of its
  bounding box horizontally (`StopAreaGoal`). Any other region accepts its exact shape (`BoxGoal`
  for cuboids, `PolygonGoal` for polygons), with the heuristic being the distance to the region's
  bounding box. `PointGoal` (coordinates)
  accepts any cell within `coordinate-arrival-radius` of the point (horizontal only when no y was
  given). Using a goal *set* means the route reaches the nearest entrance, not the region's centre.
- **Goal inside the window:** standard A\*; the first goal cell popped ends the search →
  `COMPLETE(route)`.
- **Goal outside the window:** edge cells are terminal. An edge cell is a surface inside the window with a
  neighbouring column that is outside the window or `UNKNOWN` (not captured). A\* pops in order of
  `f = g + h`, where `h` is the straight-line distance to the goal. With an admissible heuristic, the
  first edge cell popped minimises cost-so-far plus straight-line-remaining among all ways out. That cell
  ends the search → `PARTIAL(route, exit)`.
- **Turn-back penalty.** A request may carry the end of the route the player is already following.
  An edge cell then costs extra in proportion to how far behind the player it lies: three window radii
  times the cosine of the angle between "start → edge cell" and "start → route end", when that angle
  is obtuse; nothing when the edge cell is ahead or beside. The first search of a leg carries no route
  end and pays no penalty. A penalised edge cell is still expanded, so the search finds a way on if
  one exists and falls back to the turn only when nothing cheaper is left. Three radii is chosen so
  that two opposite exits, whose straight-line remainders differ by at most two radii, never trade
  places on that difference alone: the window cannot see whether the way ahead continues, so the
  player is led on unless turning back saves more than a window radius beyond that.
- **The start cell is never an exit**: a one-cell route leads nowhere.
- **Goal inside the window but unreachable within it:** search again with exits allowed only next to
  `UNKNOWN` columns, because the goal may be behind terrain not captured yet. When everything around
  is known (a sealed building), the result stays `NO_ROUTE`, and that is what triggers the "no route"
  message.
- **Exhausted** without a goal or edge cell → `NO_ROUTE`.
- **Start.** If the player's feet cell is not a surface, snap to the nearest surface within 3 blocks
  (horizontal distance first, then vertical). None → `INVALID_START`.
- **Budget.** At most `max-expansions` (default 200,000) node expansions and `time-budget-ms` (default
  50) wall time; either one exceeded → `BUDGET`.
- Data structures: binary heap of node indices, `long`-keyed open/closed maps sized to the window.
  Deterministic tie-breaking (lower `h`, then cell order) so identical inputs give identical routes.

### 6.3 Threading

- **Main thread:** WorldGuard reads, `Session` and `WalkGuide` state, chunk snapshots, particles, boss
  bar.
- **Search pool:** two daemon threads, shared by all players; runs decode and search.
- **Results:** every future from `WalkSearchService` completes on the main thread.
- **Concurrency per player:** at most one search in flight per player.
- **Load estimate:** 30 players each re-planning every 2–5 s is ~6–15 searches/s of ~5 ms each; a
  fraction of one core.

## 7. Guide behaviour (`GuidePolicy`)

One `WalkGuide` exists per player while their current leg is `WALK`. It is created when the leg begins
and discarded when the leg ends, the player leaves navigation, or quits.

States: `WAITING_FOR_TERRAIN → SEARCHING → FOLLOWING ⇄ SEARCHING`, plus `NO_ROUTE` (retry pending) and
`PAUSED` (wrong world).

- **Leg start.** Request capture of the window around the player; search once ready (bounded by
  `capture-wait-ms`). This covers arriving at a far stop by teleport: the trail appears once the
  surrounding chunks have loaded and been captured, typically within one to two seconds.
- **Re-plan triggers** (each subject to `replan-cooldown-ms`, default 2,000, unless noted; any two
  searches for one player are at least 250 ms apart):
  - the route is `PARTIAL` and the player is within `extend-distance` (default 48) blocks of its end;
  - the player is more than `off-route-distance` (default 6) blocks from the route;
  - a cell within the next `trail-length` cells became impassable (bypasses the cooldown).
- **Replacing a route.** A found route always replaces the old one. If an extension or off-route search
  fails, the guide keeps following the current route.
- **Keeping direction (anti flip-flop).** Every search after the first carries the current route's
  end, and the search's turn-back penalty (§6.2) makes exits behind the player dear. Without this, a
  local search can lead the player toward exit A, turn back toward B on discovering a dead end, then
  forget the dead end once it leaves the window and choose A again; and along an obstacle longer than
  the window, the straight-line heuristic bounces the player from side to side. Two earlier drafts
  were rejected: comparing routes with a 10% rule rejects legitimate extensions, because each search
  starts from a different position; and recording "spent" exits that later searches may not end near
  turned the player around whenever the next window's border fell within that distance of the old
  exit, which happens on every extension with a small window. The penalty is a bound, not a cure: it
  commits the player to the side of an obstacle the first search picked, up to three window radii
  of straight-line disadvantage. Obstacles longer than that need the coarse layer of §11.
- **No stitching needed.** Every route starts at the player, and the trail is drawn only from the
  player's nearest point forward, so it never jumps behind them.
- **Teleport detection.** The player is more than 64 blocks from the route, or their world changed → reset
  to `WAITING_FOR_TERRAIN` (or `PAUSED` for another world).
- **Not on foot.** While the player is flying, gliding or in a vehicle, keep the current route and
  suppress re-plans except the blocked-route trigger.
- **Failure.** `NO_ROUTE`, `BUDGET` or `INVALID_START` → hide the trail, use the straight arrow, send
  `walk.no-route` once per leg, retry after the player has moved 16 blocks or 10 s have passed.
- **Stale results.** Each search carries the guide's generation counter, incremented on every reset and
  on leg change; mismatched results are dropped.

## 8. What the player sees

### 8.1 Trail (`TrailRenderer`)

- Drawn with `Player.spawnParticle`, so only the navigating player sees it.
- From the player's nearest route index forward `trail-length` cells (default 40), one particle per
  cell, `0.2` blocks above the feet cell's floor.
- Redrawn every `trail-refresh-ticks` (default 10).
- **Direction pulse.** One brighter particle travels along the drawn section, advancing
  `pulse-step` cells (default 4) per refresh, so the direction of travel is clear even where the trail
  doubles back.
- **Leg-end marker.** When the route is `COMPLETE` and its end is within the drawn section, a distinct
  particle cluster marks it.
- Configurable: particle type and dust colour for trail, pulse and end marker; trail length; refresh
  interval.
- Shown only during `WALK` legs, only when the guide is `FOLLOWING`.

### 8.2 Arrow

The existing armour-stand arrow is unchanged in appearance. Its target:

- with a route: the cell `arrow-look-ahead` (default 6) blocks along the route from the player's
  nearest point, so it turns at corners;
- otherwise (ride legs, no route yet, failure): the straight-line target, exactly as on `main`.

### 8.3 Boss bar

- `COMPLETE` route: remaining distance along the route.
- `PARTIAL` route: remaining route distance + straight-line distance from the route's end to the goal,
  rendered with a `~` prefix through a new `<approx>` placeholder in `bossbar-format` and
  `gps-bossbar-format` (empty when exact).
- Otherwise: as on `main`.

## 9. Configuration

New section in `config.yml`; all keys optional with these defaults:

```yaml
walking-paths:
  enabled: true
  # Search window: blocks each way from the player (window is twice this across).
  window-radius: 80
  time-budget-ms: 50
  max-expansions: 200000
  # Chunk capture
  snapshots-per-tick: 6
  capture-wait-ms: 1500
  cache-max-chunks: 4096
  # Walker
  max-drop: 3          # 0-5
  water-cost: 2.5
  climb-cost: 2.0
  door-cost: 1.5
  # Guide
  extend-distance: 48
  off-route-distance: 6
  replan-cooldown-ms: 2000
  # Trail
  trail-length: 40
  trail-refresh-ticks: 10
  pulse-step: 4
  arrow-look-ahead: 6
  particles:
    trail: { type: DUST, color: "#5BC0EB", size: 1.0 }
    pulse: { type: DUST, color: "#FFFFFF", size: 1.4 }
    end:   { type: HAPPY_VILLAGER }
```

Invalid values are reported at load, and the key falls back to its default. `enabled: false` disables
guides, the cache, the search pool and the event listeners entirely.

New lang keys: `walk.no-route`. New command: `/directions debug walk` (permission `directions.debug`)
prints searches per second, p50/p95 search time, result counts by status, cache size and hit rate, and the
worst main-thread capture time per tick over the last minute.

## 10. Testing

**Unit tests (JUnit 5, pure Java, `./gradlew test`):**

- `LegPlanner`: start walk, ride legs, walking transfer between lines, end walk, walk-only result, `/gps`
  and `/compass` single leg, starting inside the first stop.
- `ColumnDecoder` with a fake block source: slabs and stairs, doors, fences and walls, water, ladders,
  low ceilings, hazards, a basement and a rooftop surface in the same column.
- `WalkRules` on small grids written as text (a test-only `TextTerrain`): step up with and without
  headroom, drops at and beyond the limit, no corner cutting, swimming, climbing on and off ladders,
  fence vs door, hazards never entered.
- `WindowSearch`:
  - on a few hundred seeded random grids, route cost equals an independent Dijkstra over the same move
    rules;
  - the chosen exit minimises cost so far plus straight-line remainder, checked against brute force
    on random worlds and on a wall across the window; an exit behind the route being followed is
    passed over for one beside or ahead, and still taken when it is the only way out; the start is
    never an exit; unknown terrain acts as an edge;
  - region goals reach the nearest cell of the region;
  - `BUDGET` returned at the expansion cap and the time limit;
  - `INVALID_START` when nothing is within snap range.
- `Route`: nearest index, remaining distance, look-ahead, stitching.
- `GuidePolicy`, with a fake clock and fake searches: each re-plan trigger, cooldown, the route end
  carried on every search after the first, stale-result drop, teleport reset, terrain wait, failure
  retry, not-on-foot suppression.
- Whole walks (`GuideSimulationTest`): a simulated player follows `GuidePolicy` and `WindowSearch`
  across synthetic cities at the full and the smallest window: open field, road grid, a single road,
  a river bank with the bridge outside the window, and a dead end whose way around lies behind. The
  trail never turns the player back except once at the real dead end, and extension never fails.
- Architecture: no class under `walk` (excluding `walk.bukkit`) imports `org.bukkit`.

**Manual acceptance on a dev server (`./gradlew runServer`), recorded in the PR:**

1. Bus trip with a walking transfer: trails on the first walk, the transfer and the final walk; none on
   ride legs.
2. Teleport arrival at a stop far from spawn: the trail appears within ~2 s.
3. `/gps` to a point 1,000+ blocks away: the trail keeps extending ahead without flicker; the boss bar
   shows `~` while partial and drops it once the goal is in the window.
4. Break a wall or remove a door on the active route: the route changes immediately.
5. `/directions start` to an unreachable island: "no route" message once, straight arrow, no errors.
6. `walking-paths.enabled: false`: behaviour identical to `main`.
7. `/directions debug walk` with several players navigating: search p95 under the budget; worst
   main-thread capture time per tick under 2 ms.

## 11. Future extension (not in scope)

A coarse whole-world layer can be added without changing the units above: a `CoarseGuide` supplies an
intermediate goal for each window (the next coarse waypoint) in place of the final goal's straight-line
heuristic. `WindowSearch`, `Terrain`, `GuidePolicy` and the trail are unaffected. Trigger for doing it:
manual testing or player reports show long walks being led into large dead ends. Observed during
acceptance in October 2026 (a trail that turned the player around after an extension) and
reproduced by `GuideSimulationTest`: the turn-back penalty bounds the damage, and this layer is the
next piece of work.
