# Directions

Transit directions for Paper Minecraft servers, using WorldGuard regions as stops and destinations.

## Commands
- `/directions start <region>` - start directions to a WorldGuard region
- `/directions start at <x> <z>` - start directions to map coordinates (uses your current Y for navigation)
- `/directions start at <x> <y> <z>` - start directions to exact coordinates
- `/directions stop` - stops active navigation
- `/directions reload` - reload configuration files (requires `directions.reload`)
- `/directions debug walk` - walking-search statistics (requires directions.debug)

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
- When the route is extended or re-planned, the new route keeps you going the way you were headed
  unless turning back saves a lot. The search only sees about 80 blocks ahead, so along a river or
  wall it may follow the bank for a while before finding the crossing.
- If no route can be found nearby, you get one message and the straight arrow, and it retries as
  you move.

Configure it under `walking-paths:` in `config.yml`; `enabled: false` restores the plain arrow.

### Whole-world routing
The search window only sees about 160 blocks, so it cannot know where a river has a bridge or
where a wall ends. The optional whole-world graph records how the walkable parts of each chunk
connect. With it, routes head for bridges and gaps the window cannot see.

- Build it with `/directions walk build <world>` (permission `directions.debug`). It runs in the
  background. Check progress with `/directions debug walk`. A city the size of Reveille takes about
  an hour at the default rate. `/directions walk build stop` stops it and saves progress. A later
  build, or a restart, resumes where it stopped.
- The graph is saved in `plugins/Directions/walk/`, one `.coarse` file per world. It loads in the
  background on startup.
- It stays current by itself. Each chunk the trail reads updates that chunk in the graph, so
  WorldEdit changes appear within five minutes.
- Without a graph, or with `coarse.enabled: false`, routing works as before: the window search
  with the turn-back penalty.

Settings under `walking-paths.coarse:`
- `enabled` (default `true`): use the graph when a world has one.
- `build-chunks-per-tick` (default 4, 1 to 32): how many chunks the build reads each tick.
- `max-expansions` (default 50,000, 1,000 to 1,000,000): how much work one graph search may do.

Existing installations need new lang keys, `walk.no-route`, `walk.debug-disabled` and the nine
`walk.build-*` keys (`build-started`, `build-resumed`, `build-stopped`, `build-done`,
`build-running`, `build-no-world`, `build-disabled`, `build-none`, `build-loading`); delete `lang.yml` to
regenerate it, or copy them from the bundled file.

## Building
`./gradlew shadowJar`

## Testing
- ./gradlew runServer - a Paper 1.21.8 dev server in run/ with WorldGuard and WorldEdit.

## Troubleshooting
- If command says region not found, verify that the region exists in player's current world.
- If route quality is poor, try adjusting `transfer-penalty` and `max-walking-distance`.
- If stops advance too quickly/slowly, try adjusting `stop-arrival-buffer` and `departure-clearance-radius`.
- If stop names appear unfriendly, try adjusting `names.yml`
