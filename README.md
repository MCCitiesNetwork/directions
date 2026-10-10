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
Whole-world routing is set under `walking-paths.coarse:` (`enabled`, `build-chunks-per-tick`,
`max-expansions`).
Existing installations need new lang keys, `walk.no-route`, `walk.debug-disabled` and the eight
`walk.build-*` keys (`build-started`, `build-resumed`, `build-stopped`, `build-done`,
`build-running`, `build-no-world`, `build-disabled`, `build-none`); delete `lang.yml` to
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
