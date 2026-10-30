# Changelog

All notable, player-visible changes to Friendai. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions follow [Semantic Versioning](https://semver.org/); the version in each heading is the `mod_version` in `gradle.properties`.

The file starts at 0.87.0. Earlier versions predate it, so their history stays in `git log`. Every shipped change adds a line here; see `AGENTS.md`, "Changelog", for the categories and the wording.

## [Unreleased]

<!-- Add entries here while you work; the version bump moves them into that version's section. -->

## [0.93.1] - 2026-10-30

### Fixed

- A companion no longer spends its last building block on a single rise it cannot climb: when a stair or bridge needs more blocks than it carries, it gathers them first or gives the job up instead of leaving a stump in the way.
- A companion boxed in with no way out now reports that the target is unreachable instead of crashing the walk while it looks for building material.

## [0.93.0] - 2026-10-30

### Added

- Mining a valuable block whose drop would fall into lava, fire, a hole or the void now builds a catch block under it first, takes the drop, and then removes the block again. Ore next to a lava pool no longer burns and ore over a ravine no longer strands.
- `mining.protectDrops`, `mining.valuableBlocks` and `mining.catchBlocks` in the server config and a Mining category in the settings screen.

### Changed

- An ore whose drop has nowhere safe to land and no catch block can be built there is left in place with an answer that names where the drop would go and what is missing, instead of being mined and lost.
- The debug overlay marks the catch cells before the block is broken, and the mining summary says how many catch blocks were placed and removed.

### Fixed

- Stopping a mining job no longer leaves its catch blocks or route pillars standing in the world; they are taken down like a cancelled build's scaffold.

## [0.92.0] - 2026-10-30

### Added

- The companion can read its own instruction catalogue: `list_skills` shows the id, title, source, version, on/off state and a one-line description of every skill it knows, one page at a time.
- `skill_help` returns the skill-authoring checklist with the real limits, so the rules are available even on a turn where the Writing skills procedure was not selected.
- A new built-in skill, Writing skills, teaches the companion to turn a finished goal or a failed shift into one reusable procedure instead of losing the lesson.
- Nine more built-in skills now advise on planning after a failure, routes and terrain, mining and the drops, self-care, exploration, combat, storage and crafting, trust and privacy, and repairing mistakes. Trust and privacy is public, so a guest also receives it.
- Built-in skill titles follow one naming rule, so adding a catalogue entry is a data file plus two titles.

## [0.91.1] - 2026-10-30

### Fixed

- A running companion no longer skids past the planned route: it slows down before a corner and before the end of the route, so it stays on the path instead of swinging wide and doubling back.
- A companion that arrives at the edge of a gap off the line of the jump keeps lining up first, so it no longer takes off sideways and falls into the pit.

## [0.91.0] - 2026-10-30

### Added

- Pointing at an ore or a log now mines the whole connected vein (up to 32 blocks) instead of stopping at the one block. Every block is checked for tools and protection as before, and the answer says how many were mined.

### Fixed

- A pointed mine reports what it actually picked up. When the drop is still on the ground the answer gives its place and suggests picking it up, instead of claiming the block dropped nothing.
- A drop that fell into the hole the mine just dug is picked up from the rim of the hole instead of being left behind as unreachable.
- A pointed mine with a full inventory refuses before breaking the block, so the drop is not left lying on the ground.
- Answers like "no coal nearby" now say that nothing was seen or remembered within the radius and name the tools that look around, instead of reading like a fact about the whole world.

## [0.90.1] - 2026-10-30

### Fixed

- A tree, block or place search starts around the companion itself. Asking for wood no longer sends it to the tree nearest to you, and the job still starts while you are far away or offline.

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
