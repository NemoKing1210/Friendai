# Changelog

All notable, player-visible changes to Friendai. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions follow [Semantic Versioning](https://semver.org/); the version in each heading is the `mod_version` in `gradle.properties`.

The file starts at 0.87.0. Earlier versions predate it, so their history stays in `git log`. Every shipped change adds a line here; see `AGENTS.md`, "Changelog", for the categories and the wording.

## [Unreleased]

<!-- Add entries here while you work; the version bump moves them into that version's section. -->

## [0.105.0] - 2026-11-01

### Added

- Google Cloud Speech-to-Text as a paid recognition provider, with a key and a language.

## [0.104.0] - 2026-11-01

### Added

- Google Cloud Text-to-Speech as a paid speech-synthesis provider, with a key, voice, language, speed and LINEAR16 playback.

## [0.103.1] - 2026-11-01

### Fixed

- The German and Spanish **Reset** buttons no longer clip at the smallest GUI scale.

## [0.103.0] - 2026-11-01

### Added

- `/friendai settings` prints the effective provider, model, autonomy, chunk loading, hearing radius, memory and instruction-skill settings for a dedicated server without opening the client screen.

## [0.102.0] - 2026-11-01

### Added

- The provider screen now has a **Get an API key** button that opens the selected service's key page.

## [0.101.0] - 2026-11-01

### Added

- German, French, Spanish, Brazilian Portuguese, Simplified Chinese and Ukrainian interface translations, with placeholder parity enforced for every language.
- Portuguese is now recognised by the language detector, so an owner writing Portuguese gets Portuguese replies.
- The README is now a landing page with a feature table, contents, badges and language switcher, and is translated into Russian, Ukrainian, German, French, Spanish, Brazilian Portuguese and Simplified Chinese.

## [0.100.0] - 2026-11-01

### Changed

- Provider model lists are cached for a day and keep the last successful list when a provider is offline, so opening the provider screen no longer discards models on a failed refresh.

## [0.99.0] - 2026-11-01

### Added

- The companion's body language now shows above its head: focus while it works, worry when hurt or in danger, pleasure after a finished job, displeasure after a failed one, and alarm in combat.

## [0.98.0] - 2026-11-01

### Added

- Named LLM presets for OpenRouter, DeepSeek, Groq, Mistral, xAI, Together AI, Fireworks AI, Cerebras and GitHub Models, all using the existing OpenAI-compatible protocol; `openai_compat` remains the escape hatch.

## [0.97.0] - 2026-11-01

### Added

- The operator Log tab now has request and reply entries for each model step: the system/message previews and offered tools on the way out, and the reply text, tool calls, finish reason and usage on the way back.

## [0.96.4] - 2026-11-01

### Fixed

- The planner is now told that a standing tree is one `fell_tree` step, not a `mine_blocks` count, so “collect 32 jungle logs” no longer starts a per-log mining plan.

## [0.96.3] - 2026-11-01

### Fixed

- A companion can now climb a water column taller than eight blocks: a rising stroke no longer counts as a submerged lateral swim and exhausts the breath budget.

## [0.96.2] - 2026-11-01

### Fixed

- A companion no longer breaks a block that has lava directly below it: the break is refused with the cell and a remedy, so neither the drop nor the companion falls into the lava.

## [0.96.1] - 2026-11-01

### Fixed

- A task that is alive but stops making progress now stops with “no progress for N s: …” instead of burning the whole five-minute timeout, and a short soak checks that a long runner session finishes or cancels without a duplicate terminal event.

## [0.96.0] - 2026-11-01

### Added

- The companion now understands the exact orders “follow me”, “stay”, “come here”, “stop”, “status” and “inventory” in English and Russian even when the language model is unavailable, and runs them through the same tools and access checks as before; `/friendai status` says when only these local orders work.

## [0.95.12] - 2026-11-01

### Fixed

- A guarding companion no longer starts fights with neutral mobs or the warden: endermen, piglins and zombified piglins are attacked only when provoked, a piglin group is avoided unless ordered, and the warden and dragon are treated as dangers rather than targets.

## [0.95.11] - 2026-11-01

### Added

- `/friendai diagnose` writes a bounded, redacted diagnostic bundle for the owner (or an operator for a named owner), with an optional `chat` history and never an API key.

## [0.95.10] - 2026-11-01

### Fixed

- A house asked for with a planks, slab or full-block roof now gets the matching stairs instead of failing with an unknown block state, and a roof with no stairs variant is refused before the first block with a message that names the field and the fix.

## [0.95.9] - 2026-11-01

### Fixed

- A companion that broke the block it was standing on no longer calls the thing it was sent for unreachable. Standing on the rim of the hole it has just dug, with nothing under its own feet, it now steps back onto solid ground and carries on instead of refusing every step it could take.

## [0.95.8] - 2026-11-01

### Fixed

- A companion that drops into the hole it has just dug keeps working instead of giving up: while it is falling there is nothing under its feet to stand and reach from, and it used to call the block it was sent for unreachable. It now waits until it lands and mines on from there.

## [0.95.7] - 2026-11-01

### Fixed

- A companion on a busy server looks at what is around it right now instead of standing blind: the view sweep no longer queues a fresh pass behind the one still running, so the sights it acts on stay current and a block its own digging exposes is noticed at once instead of being lost behind minutes-old checks.

## [0.95.6] - 2026-11-01

### Fixed

- A companion digging for a buried resource on a busy server now sees the faces its own digging opens. With several companions around, a dig's sighting could be dropped behind a full queue of view checks, so the search gave up with "no minecraft:stone observed or remembered nearby" while standing in the stone it had just mined.
- A companion asked for leather no longer answers that cows have none. It learns what creatures drop from sixteen samples instead of five, so one unlucky run of empty loot rolls can no longer hide a real source.

## [0.95.5] - 2026-10-31

### Fixed

- A companion that mined a few blocks down now places its crafting table or furnace on the rim of the hole it dug instead of giving up with "no free spot nearby", so it can craft what it went down for.

## [0.95.4] - 2026-10-31

### Fixed

- The companion notices the stone its own digging uncovers: restarting the view sweep from a new spot no longer throws away the block changes the dig had just queued, so a buried resource it exposes becomes known instead of being searched for again.

## [0.95.3] - 2026-10-31

### Fixed

- The companion reaches a mob or a player it was sent to instead of standing a step short and giving up: the last stretch of the walk is finished by hand when the route ends just outside the reach.
- The companion notices a block its own digging exposes: a sighting queued before it moved is re-aimed from where it stands instead of being dropped.

## [0.95.2] - 2026-10-31

### Fixed

- The companion finishes a tall build instead of giving up partway: when it has scaffolded itself above a block it still has to place, it takes its own column back down and places the block instead of failing the whole build.

## [0.95.1] - 2026-10-31

### Fixed

- A tall tree is felled completely and every log is picked up: the companion finishes its climb standing on the scaffold, not in mid-jump, so the top logs are no longer left behind, and it walks the last step onto a drop its route stopped just short of.
- A campfire that has gone out no longer counts as a cooking station: the companion checks the live block instead of the memory of it.

## [0.95.0] - 2026-10-30

### Changed

- The Tasks tab shows each task's arguments as readable chips instead of raw JSON: the block or item with its icon and name, the coordinates as X Y Z, and the count. Item ids in task results and history are shown by their game names, and tool names read as plain words.
- The Overview tab's Activity card is laid out as labelled rows (free time, next step, budget, last result, planner model) with item icons and game names instead of one block of text.
- The Toggles tab is now Settings and sits at the bottom of the menu list, below Debug. Each switch is a card with a title, a short description, the reason when it cannot be changed, and a clear On/Off switch.
- Opening the companion menu no longer pauses the companion: it keeps working while you look at its tabs.

### Fixed

- The movement overlay no longer switches itself on when the settings page opens: it is off at the start of every connection and is no longer stored in the client config.

## [0.94.0] - 2026-10-30

### Added

- The companion can now look back at itself: `read_log` shows its own recent activity (owner only), `recent_chat` returns the last messages of a conversation including its own replies, `memory_overview` lists every fact with its id and how full each memory book is, and `compress_memory` merges duplicate or superseded facts, as a dry run first and applied only when the owner agrees. Promises are never merged and no fact is dropped without a replacement.
- Conversations are kept per owner in a transcript that survives restarts and death. A trusted player can read only their own conversation and a guest none; secrets are masked when it is written and when it is read.
- The Memory tab has a "Recent chat" entry that shows the last lines and a "Clear chat" button with a confirming second click.
- `memory.transcriptMaxEntries`, `memory.transcriptMaxTextChars`, `memory.transcriptRetentionDays`, `memory.compactAtPercent` and `memory.compactionEnabled` in the server config (schema 19; an older file is upgraded with a backup).

### Changed

- `recall` can be narrowed by a text fragment and a category, and says what it searched when nothing matches instead of claiming nothing is remembered.
- The memory block of the prompt shows how full each facts book is, and a short "your own records" note tells the model when to read its log, conversations and memory.

### Fixed

- The Memory tab refreshes only when what it shows has changed, including for a player with a custom description.

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
