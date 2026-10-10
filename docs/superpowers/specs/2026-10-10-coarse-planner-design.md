# Coarse planner for walking trails

**Status:** approved design, 2026-10-10. Extends the walking-trails design
(`2026-10-02-walking-trails-design.md`, §11), which remains binding for everything not changed here.

## 1. Problem and goals

The walking trail is computed by a search that sees only the window around the player, about
160 × 160 blocks. Any obstacle longer than that, or any dead end whose way around lies further back,
is invisible to it. The turn-back penalty added on 2026-10-10 keeps the player from being bounced
from side to side, but it does so by committing them to whichever side the first search picked. Along
a river, that can mean following the bank for hundreds of blocks before the bridge enters the window.

**Goal.** Give every window search a target that already accounts for obstacles anywhere in the world,
so the trail heads for the bridge from the start.

**Success criteria.**
- In `GuideSimulationTest`, the river and dead-end scenarios reach the goal with no reversal and
  without the detour along the bank once the coarse layer has a graph of the synthetic city.
- In-game, a `/gps` across Reveille's river heads for the bridge from the first route.
- The server tick stays within the existing budgets while a world is being built.
- With no finished graph for a world, behaviour is exactly that of the turn-back penalty alone.

**Non-goals.** Costing roads over other ground (walking cost stays physical), routing through other
worlds, and any change to the trail, arrow or boss bar.

## 2. Approach

A whole-world **coarse graph** per world, built once through the server from every existing chunk and
kept current by the chunk captures the trail already does. Each node is one connected walkable
component of one chunk. A coarse A* over that graph gives the chain of components from the player to
the goal; the last of them that lies inside the current search window and is captured becomes the
window search's goal. The window search is unchanged otherwise. When the graph has no answer, the
search falls back to today's exit search with the turn-back penalty.

Rejected: learning connectivity only from chunks players walk through (a first walk is blind, which is
exactly when the problem bites); an offline region-file scan (needs an Anvil and NBT reader to
maintain and goes stale between runs); whole chunks or 64-block cells as nodes (a river chunk would
look crossable).

## 3. Package layout and units

```
walk/
├── CellSetGoal                  (walk/, not coarse/) a goal that is an explicit set of cells, the waypoint goal for the window search
├── coarse/                      pure Java, no Bukkit imports (covered by WalkPackagePurityTest)
│   ├── ChunkComponents          components of one chunk from its surfaces and the walking rules
│   ├── ComponentId              a node's identity: chunk and representative cell
│   ├── CoarseNode               one component: representative cell, height range, edges
│   ├── CoarseGraph              all nodes of one world; concurrent reads, serialised writes
│   ├── CoarseSearch             A* over a CoarseGraph; returns a node path or a failure
│   ├── CoarseGraphCodec         the on-disk format, read and write
│   ├── RegionHeaders            which chunks exist in a region file, from its 4 KB header table
│   ├── Waypoints                picks the window search's goal from a node path and a window
│   ├── GuidedSearch             composes the coarse plan with the window search; used by the service and the simulation
│   ├── ComponentCache           an LRU of recently linked chunks' components, so neighbours are not flooded again
│   ├── BuildOrder               pure build helper: which chunks of a region are still to do, in build order
│   └── BuildRows                pure build helper: which rows of a build are complete, so saved progress skips no gap
├── bukkit/
│   ├── CoarseGraphs             graphs per world, loading, saving, owned by the plugin
│   ├── CoarseBuilder            the background build of one world
│   └── (changed) ChunkColumnCache, WalkSearchService
```

- **`ChunkComponents`**: `of(Terrain, chunkX, chunkZ, WalkRules) → List<Component>`. Floods the
  chunk's surface cells with the real walking rules restricted to the chunk's own columns. Each
  component records its representative cell (the walkable cell nearest the chunk centre, lowest cell
  key on ties), its height range, and its border cells by side. Also `componentOf(Terrain, chunkX,
  chunkZ, cell, WalkRules) → ComponentId or null`: the component a cell belongs to, by flooding
  from that cell until the representative is found.
- **`CoarseNode`**: `id`, `minY`, `maxY`, and `edges`: the ids of connected nodes in the four
  neighbouring chunks. Immutable; a changed chunk replaces its nodes.
- **`CoarseGraph`**: `nodesOf(chunkX, chunkZ)`, `node(ComponentId)`, `replace(chunkX, chunkZ,
  List<CoarseNode>)`, `size()`. Replacing a chunk's nodes also rewrites the matching edges of its
  neighbours, so edges are always symmetric. A `ConcurrentHashMap` keyed by chunk; `replace` is
  synchronised so two decodes cannot interleave.
- **`CoarseSearch`**: `search(graph, ComponentId start, Set<ComponentId> goals, Goal goal, int
  maxExpansions) → Result`, where `Result` is `FOUND(List<ComponentId> path, double length)`,
  `NO_PATH`, or `BUDGET`. Edge cost is the straight-line distance between representative cells; the
  heuristic is `goal.estimate` at the representative cell, which is admissible because every cell of
  the goal lies at least that far away. Deterministic tie-breaking as in `WindowSearch`.
- **`Waypoints`**: `pick(path, window, Terrain) → ComponentId or null`: the last node of the path's
  *leading run* of nodes whose chunk lies entirely inside the window and whose representative column
  is captured. The run starts at the start node, which always counts even when its chunk straddles
  the window's edge, and ends at the first node that is outside the window or not captured. A path
  that leaves the window and comes back in (to a bridge just outside it) therefore never yields a
  waypoint on the far side of the obstacle. Null when the run is the start node alone, in which case
  the coarse layer has nothing to add.
- **`RegionHeaders`**: `chunksIn(Path regionFile) → List<ChunkPos>`: parses the file name for the
  region position and the first 4 KB for non-zero offsets. No NBT, no decompression.
- **`CoarseGraphCodec`**: `write(graph, progress, OutputStream)` and `read(InputStream)`. See §7.

## 4. Searching with the graph

`WalkSearchService.plan` on the pool thread, after the window's capture completes:

1. If the goal may overlap the window, run today's direct search first (exits NONE). A `COMPLETE`
   result is returned as is.
2. Otherwise, or when the direct search found nothing, and a graph with at least one node exists for
   the world and its build has finished (§6):
   a. Start node: `componentOf` the player's snapped start cell. Null (chunk not in the graph) →
      step 3.
   b. Goal nodes: for every chunk that the goal's bounding box overlaps, every node of that chunk whose
      height range overlaps the goal's. Region goals use their region's box; `PointGoal` uses its
      radius; a goal without a height uses the world's full range. Empty (goal area not built) → step 3.
   c. `CoarseSearch` with `coarse.max-expansions`. `NO_PATH` or `BUDGET` → step 3.
   d. `Waypoints.pick`: the last node of the path's leading run of in-window, captured nodes. Null → step 3.
   e. Flood the waypoint chunk from the waypoint's representative cell to collect the component's
      cells, and run the window search with a `CellSetGoal` of those cells, exits NONE. `COMPLETE` →
      return it as `PARTIAL`, with `endEstimate` = the coarse path length from the waypoint to the
      goal plus the goal's estimate from the last node. Anything else (the graph says the chunk connects
      but the captured terrain does not, or the budget ran out) → step 3.
3. Fallback: today's exit search toward the final goal with the turn-back penalty.

`SearchResult` gains a `boolean coarse` flag for the debug output and the stats; the policy does not
look at it. `WalkStats` counts searches guided by the graph, fallbacks, and the coarse search time.

The coarse search runs on every plan, not once per leg: it costs a few milliseconds for a thousand
blocks, and recomputing keeps it correct after chunk changes without any invalidation logic.

**Budget.** A coarse search that has to explore a whole connected region (an unreachable island) can
touch every node. `coarse.max-expansions` (default 50,000) bounds it, and so does time: the coarse
search reads the clock every 256 expansions. A `BUDGET` falls back and is counted.

`GuidedSearch` takes the time budget (`time-budget-millis`) as a duration, not a deadline, and gives
each phase its own deadline of that budget from when the phase starts: the direct search, the coarse
search, the search to the waypoint, and the fallback. A slow phase (the coarse search measured 35–45 ms
at the 50,000 cap, 80 ms cold) therefore never leaves the next one without time. The fallback does not
repeat the direct phase: when step 1 ran, it runs only the exit phase (`WindowSearch.searchOnward`:
exits next to uncaptured terrain when the goal may overlap the window, any exit otherwise). A plan can
take a few budgets in the worst case; the trail keeps its previous route meanwhile.

`SearchResult.coarseNanos` reports the time spent planning over the graph (0 when it was not
consulted), and `/directions debug walk` shows its p50 and p95 as `Coarse search: p50 x ms, p95 y ms
(last n)`. A fallback is counted whenever the graph was usable and the result was neither guided by it
nor the goal reached directly.

## 5. Building a world

`/directions walk build <world>` (permission `directions.debug`) starts the build of one world, or
resumes it if progress was saved. `/directions walk build stop` stops the running build after the
chunks in flight finish. Only one build runs at a time.

1. List region files under the world's region folder (`<world folder>/region`, or `DIM1/region`
   and `DIM-1/region` for end and nether worlds). `RegionHeaders` gives the existing chunks. Chunks
   that do not exist are never generated.
2. Order: region by region, and within a region row by row, so a chunk's west and north neighbours
   are already decoded when it is processed. A build-only `ColumnStore` keeps the current region plus
   a one-chunk margin from the previous row and region; `cache-max-chunks` does not apply to it.
3. Each tick, up to `coarse.build-chunks-per-tick` (default 4) chunks are requested with
   `World.getChunkAtAsync(x, z, false)`. When one arrives, on the main thread, take its snapshot and
   hand it to the decode pool, as `ChunkColumnCache` does. A chunk that is already loaded is
   snapshotted directly. A chunk that fails to load is logged once and skipped. Loaded chunks are
   released to the server's own unloading; nothing is held.
4. On the pool: decode, compute components, compute edges to the neighbours that are in the build
   store or already in the graph, `replace` into the graph.
5. Progress (the last fully processed region and row) is saved with the graph every 2,000 chunks and
   when the build stops or the plugin disables. A chunk is counted once; a stopped build resumes from
   the saved row.
6. Status (`/directions debug walk`): world, chunks done of total, chunks per second, estimated time
   left, and the graph's node count. Start, resume, stop, done and "no such world" are reported to
   the command sender through lang keys.

A reload keeps the build running; it only re-reads `build-chunks-per-tick`. Disabling the plugin
saves and stops it.

Reveille (measured): 259,968 existing chunks in 521 region files, about an hour at 4 chunks per tick.

## 6. Keeping it current

`ChunkColumnCache.decode`, after storing a captured chunk, computes that chunk's components and
edges to neighbours that are in the live `ColumnStore`, and replaces the chunk's nodes in the graph.
Only a graph that already exists (`CoarseGraphs.graph(world)` non-null) is updated; live captures
never create one, so a world that was never built gets no partial graph of walked corridors. Only the
builder creates graphs (`graphOrCreate`).

Searches use a world's graph only once a build of it has finished
(`CoarseGraphs.graphForPlanning(world)`, null unless the saved progress is `done`): before that,
unbuilt chunks would read as impassable and a coarse search would spend its whole budget proving
there is no path. A world being rebuilt is routed without its graph until the rebuild finishes.
Edges on a side whose neighbour is not captured keep whatever the graph already has for that side
(the neighbour's own edges to this chunk are rewritten only on the sides recomputed). So:

- a WorldEdit wall is reflected within the existing five-minute stale window, once a player's
  window re-captures the chunk;
- a chunk unloading does not remove its nodes; the graph is the memory of what was last seen.

Components and edges are computed on a dedicated single link thread (`Directions-CoarseLink`, minimum
priority, owned by `WalkSearchService`), never on the two search threads, so a first window of a
hundred-odd city chunks does not queue ahead of the player's first search; `replace` is quick and
synchronised. A per-world `ComponentCache` (pure, in `walk/coarse`: an LRU of 512 chunks' components,
`invalidate(cx, cz)` called for every re-captured chunk) lets linking a chunk reuse its neighbours'
components instead of flooding each neighbour again; `ChunkEdges.compute` takes the neighbour
components from a supplier for this. The builder uses its own cache the same way.

## 7. Persistence

One file per world: `plugins/Directions/walk/<world UUID>.coarse`, gzip-compressed, big-endian (Java `DataOutput`):

```
magic "DCG1", version 1
world UUID (two longs)
progress: region x, region z, row (ints; −1, −1, −1 when no build has started), done flag
node count (int)
per node: chunk x, chunk z (ints); representative x, y, z (ints); minY, maxY (shorts);
          edge count (byte); per edge: chunk x, chunk z, representative x, y, z (ints)
empty-chunk count (int); per empty chunk: chunk x, chunk z (ints)
```

About 60 bytes per node before compression; Reveille's million-odd nodes come to roughly 60 MB raw
and 15 MB compressed. Written to a temporary file and renamed into place, never partially. Loaded
asynchronously on plugin enable; searches run without the graph until it is in memory. A file that
fails to read (bad magic, truncated, newer version) logs one warning naming the file, and the world
runs without a graph until rebuilt.

Saved: on build progress, on `/directions walk build stop`, on plugin disable, and otherwise every ten
minutes if the graph changed.

## 8. Configuration

Under `walking-paths:`:

```yaml
  coarse:
    # Whole-world routing from a graph built with /directions walk build. Off: windows only.
    enabled: true
    # Chunks loaded per tick while building a world. 1-32.
    build-chunks-per-tick: 4
    # Coarse search expansions before falling back to the window search alone. 1000-1000000.
    max-expansions: 50000
```

Parsed by `WalkSettingsParser` with the same warn-and-default rule. `enabled: false` skips §4 step 2
entirely and refuses the build command with a message.

Lang keys (`walk.` section): `build-started`, `build-resumed`, `build-stopped`, `build-done`,
`build-running` (another build is in progress), `build-no-world`, `build-disabled`.

## 9. Error handling

| Situation | Behaviour |
|---|---|
| No graph for the world, its build not finished, or `coarse.enabled: false` | Window search with the turn-back penalty, as today |
| Player's chunk not in the graph | Fallback for this search |
| Goal area not built | Fallback for this search |
| Coarse search over budget | Fallback, counted in stats |
| Graph says the way is open, terrain says it is not | Fallback for this search; the next capture of that chunk corrects the graph |
| Graph file unreadable | One warning, world runs without a graph |
| Chunk fails to load during build | Logged once, skipped |
| Build command while a build runs | `build-running` message |
| Plugin disabled mid-build | Progress saved; resumes on the next build command |

## 10. Threading

- Main thread: command handling, snapshots, `getChunkAtAsync` callbacks, saving triggers.
- Decode pool: decoding. Live captures then hand components, edges and `replace` to the link thread;
  the builder does all of it on its own builder thread.
- Any thread: `CoarseGraph` reads (searches on the pool).
- Saving: a copy of the node map is taken under the replace lock, then written on a separate
  single-threaded executor so a save never blocks decoding.

## 11. Testing

- `ChunkComponents`: a river chunk gives two components; a bridge over a road gives two; stairs
  join two levels into one; an empty chunk gives none; `componentOf` finds the component of any cell
  and null for a non-walkable one; representative choice is deterministic.
- `CoarseGraph`: replacing a chunk rewrites neighbour edges both ways; edges stay symmetric after any
  sequence of replacements; unknown sides keep old edges.
- `CoarseSearch`: finds the shortest path on a hand-built graph; `NO_PATH` across a gap; `BUDGET` at
  the cap; deterministic.
- `Waypoints`: picks the last node of the leading in-window captured run; a path that leaves the
  window and re-enters stops where it first leaves; null when only the start is inside.
- `RegionHeaders`: a fabricated header with a known set of offsets lists exactly those chunks;
  parses negative region coordinates from file names.
- `CoarseGraphCodec`: round trip of a graph with progress; a truncated stream fails cleanly.
- `WalkSimulation` gains a coarse mode: the graph is built from the synthetic city with the real
  `ChunkComponents`, and every search runs through the same `GuidedSearch` the server uses. The river and
  dead-end scenarios then reach the goal with no reversal; the river walker reaches the bridge
  without passing the bank's far end.
- Architecture: `walk.coarse` has no Bukkit imports.

Manual acceptance on the dev server, recorded in the PR: build Reveille with `/directions walk
build Reveille` and watch `/directions debug walk` stay under 2 ms of capture time per tick; a `/gps`
across the river heads for the bridge from the first route; a WorldEdit wall across a road is routed
around within five minutes; `coarse.enabled: false` restores the penalty-only behaviour.
