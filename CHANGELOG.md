# Changelog

## 3.0.0

### Added
- Block-level walking paths on the Pathetic A* engine, behind `pathfinding.enabled` (default off).
- Real walking distances for start, destination and transfer legs when routing.
- Guided arrow that follows the walking path, path-based boss bar distance, and automatic re-routing.
- Chunk flag cache: main-thread snapshot pump, off-thread region-file reader, block-change patching.
- `/directions debug path|flags|capture|stats` for previewing paths, inspecting classification,
  capturing terrain and reading statistics.
- Headless self-test (`-Ddirections.selftest`) with a synthetic course world; Gradle `serverSmoke`,
  `runServer`, `perfTest` and `fuzzLong` tasks.

### Changed
- Stops carry the centre height of their region so walking searches start at platform level.
- `TransitGraph` accepts precomputed walking distances for walking edges.
