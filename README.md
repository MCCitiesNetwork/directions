# Directions

Transit directions for Paper Minecraft servers, using WorldGuard regions as stops and destinations.

## Commands
- `/directions start <region>` - start directions to a WorldGuard region
- `/directions start at <x> <z>` - start directions to map coordinates (uses your current Y for navigation)
- `/directions start at <x> <y> <z>` - start directions to exact coordinates
- `/directions stop` - stops active navigation
- `/directions reload` - reload configuration files (requires `directions.reload`)
- `/gps <region>` and `/compass <x> <z>` - direct navigation without transit
- `/directions debug path <x> <z>` | `path region <id>` - preview a walking path with particles (requires `directions.debug`)
- `/directions debug flags` - show how the pathfinder classifies the blocks around you
- `/directions debug capture <name> <radius>` - save terrain flags around you to `plugins/Directions/captures/`
- `/directions debug stats` - search counts, latencies, chunk cache and pump statistics

## Configuration
- `transfer-penalty`: extra cost for transit hops
- `max-walking-distance`: max static walking edge distance
- `stop-arrival-buffer`: 2D buffer for configured stops only
- `next-stop-notify-distance`: when to emit "get ready" messages
- `departure-clearance-radius`: distance required from previous stop before advancing
- `coordinate-arrival-radius`: distance to a coordinate destination before navigation completes
- `bossbar-format`: bossbar text template (`<stop>`, `<remaining>`)
- `walking-transfer-policy`:
  - `shared-mode-only` (recommended)
  - `strict`: allows walking edges regardless of stop mode

## Walking paths (pathfinding)
Since 3.0.0, Directions can compute real block-level walking paths with the
[Pathetic](https://github.com/bsommerfeld/pathetic) A* engine. When enabled:

- the legs to the first stop, from the last stop, and walking transfers between stops are costed
  by real walking distance instead of straight lines, so route choice respects canals, walls and
  buildings;
- the guidance arrow follows the walking path (pointing a few blocks ahead along it) and the boss
  bar shows the remaining walking distance; the player is re-routed when they leave the path;
- `/gps` and `/compass` get the same guided arrow.

Everything falls back to the previous straight-line behaviour when pathfinding is disabled, when a
search finds nothing, or when it runs out of its time budget. The feature ships **disabled**; set
`pathfinding.enabled: true` in `config.yml` to turn it on. See the commented `pathfinding:` block
for every knob.

The walker treats doors, fence gates and trapdoors as passable, swims through water at a cost, climbs
ladders and vines, steps up one block, drops up to three, and never enters lava, fire, cactus, magma
or powder snow. Fences, walls, panes and bars block it. It never asks the server to load a chunk:
loaded chunks are snapshotted on the main thread (a few per tick), generated-but-unloaded chunks are
read from the region files off the main thread, and anything else is treated as impassable.

## Building
`./gradlew shadowJar`

## Testing
- `./gradlew test` - unit tests: terrain scenarios, path invariants, optimality against an
  independent Dijkstra on random mazes, budget enforcement, region-file decoding, config parsing.
- `./gradlew fuzzLong` - 5,000-seed optimality fuzz.
- `./gradlew serverSmoke` - starts a real Paper 1.21.8 server headlessly, builds a synthetic course
  world with every terrain feature, runs `src/serverTest/scenarios-course.yml` and fails on any
  failing scenario.
- `./gradlew serverSmoke -PsmokeScenarios=sample-data/scenarios.yml` - same against a real world
  placed in the git-ignored `sample-data/` folder (see `docs/pathetic-integration-plan.md`).
- `./gradlew runServer` - interactive Paper 1.21.8 server in `run/` with WorldGuard and WorldEdit,
  using the sample world when present.

## Troubleshooting
- If command says region not found, verify that the region exists in player's current world.
- If route quality is poor, try adjusting `transfer-penalty` and `max-walking-distance`.
- If stops advance too quickly/slowly, try adjusting `stop-arrival-buffer` and `departure-clearance-radius`.
- If stop names appear unfriendly, try adjusting `names.yml`
