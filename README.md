# Directions

Transit directions for Paper Minecraft servers, using WorldGuard regions as stops and destinations.

## Commands
- `/directions start <region>` - start directions to a WorldGuard region
- `/directions start at <x> <z>` - start directions to map coordinates (uses your current Y for navigation)
- `/directions start at <x> <y> <z>` - start directions to exact coordinates
- `/directions stop` - stops active navigation
- `/directions reload` - reload configuration files (requires `directions.reload`)

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

## Building
`./gradlew shadowJar`

## Troubleshooting
- If command says region not found, verify that the region exists in player's current world.
- If route quality is poor, try adjusting `transfer-penalty` and `max-walking-distance`.
- If stops advance too quickly/slowly, try adjusting `stop-arrival-buffer` and `departure-clearance-radius`.
- If stop names appear unfriendly, try adjusting `names.yml`
