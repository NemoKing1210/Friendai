# Changelog

All notable, player-visible changes to Friendai. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions follow [Semantic Versioning](https://semver.org/); the version in each heading is the `mod_version` in `gradle.properties`.

The file starts at 0.87.0. Earlier versions predate it, so their history stays in `git log`. Every shipped change adds a line here; see `AGENTS.md`, "Changelog", for the categories and the wording.

## [Unreleased]

<!-- Add entries here while you work; the version bump moves them into that version's section. -->

## [0.87.1] - 2026-10-30

### Fixed

- The companion runs up to a one-block gap and leaps it instead of walking off the edge and falling in; without the food to sprint it walks around the gap or bridges it.

## [0.87.0] - 2026-10-30

### Added

- Survival building places blocks at a player's pace: `tasks.placeIntervalTicks` (default 4 ticks, range 1–40, server config schema 17) instead of one per server tick; creative mode keeps one block per tick.
- The companion holds the block it is placing and swings once per placement instead of working with an empty hand.
