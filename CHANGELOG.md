# Changelog

All notable, player-visible changes to Friendai. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions follow [Semantic Versioning](https://semver.org/); the version in each heading is the `mod_version` in `gradle.properties`.

The file starts at 0.87.0. Earlier versions predate it, so their history stays in `git log`. Every shipped change adds a line here; see `AGENTS.md`, "Changelog", for the categories and the wording.

## [Unreleased]

<!-- Add entries here while you work; the version bump moves them into that version's section. -->

## [0.90.0] - 2026-10-30

### Changed

- The companion jumps like a player: it leaps up onto a ledge one block higher across a gap of up to two blocks, and down onto lower ground across a gap of up to four blocks, a longer crossing the further it falls.
- A companion too hungry to sprint now hops a one-block gap with a walking jump instead of walking around it; wider gaps still need a running start.

### Fixed

- The companion holds its speed through a leap, so it no longer drops short of a wide gap or the edge of a ledge.

## [0.89.2] - 2026-10-30

### Fixed

- A companion carried past the middle of a block by its own momentum on a climb or a diagonal step now counts the step as done instead of turning back to the middle, so it no longer steps back a block before going on.

## [0.89.1] - 2026-10-30

### Changed

- Route planning is lighter: the path search and the terrain cache it reads no longer box every block position, so long routes cost less per tick, and the companion sorts out what you ask of it a little faster.

## [0.89.0] - 2026-10-30

### Added

- The companion spends its own survival items: it pours a water bucket to break a fall and takes the source back, drinks milk to clear a poison or a slow that costs the job, eats a golden apple or drinks a healing potion when it is critical outside a fight, drinks fire resistance in lava and water breathing under water, and throws a splash potion at a wounded owner standing next to it.

### Fixed

- Clearing an area no longer stands still on leaves the companion cannot reach: it works from the nearest cells outward, which opens up the inside of a thick canopy, and it stops with a report after three unreachable cells in a row instead of searching for a route to each of them.
- A tree felled from a log high on the trunk now loses every log down to its foot: the companion follows the trunk from what it saw instead of stopping at the part it could see.
- A log cut above the leaves is no longer lost: the companion breaks the leaf the drop rests on so it falls where it can be picked up.

## [0.88.0] - 2026-10-30

### Added

- The companion leaps a whole ravine: a running jump now clears a gap of one, two or three empty blocks instead of a single missing block.

### Fixed

- The companion runs up to a gap and leaps it instead of walking off the edge and falling in; without the food to sprint it walks around the gap or bridges it.

## [0.87.0] - 2026-10-30

### Added

- Survival building places blocks at a player's pace: `tasks.placeIntervalTicks` (default 4 ticks, range 1–40, server config schema 17) instead of one per server tick; creative mode keeps one block per tick.
- The companion holds the block it is placing and swings once per placement instead of working with an empty hand.
