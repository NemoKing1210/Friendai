---
name: writing-gametests
description: Use when adding or changing a Fabric GameTest in the Friendai checkout, when a GameTest hangs, fails only in a full run, or when a new test class must be registered in the shard map.
---

# Writing GameTests

`AGENTS.md` "Testing" states the rules. This skill is the mechanics: arenas, chunks, deterministic ticking, tracing and shard registration.

## Isolation

GameTests share one world and run in parallel.

- Never rely on global state. Give each test its own block type (`MineBlocksTests` uses a different log per test) and filter item/entity counts by type.
- Build a floor with `GameTestSupport.platform(h, size)` or `platformAt(...)`; these also load the floor and its margins.
- Use `GameTestSupport.mockPlayerAt(...)`: mock players log in at the world origin, in an unloaded area.
- Load extra chunks explicitly when entities must move farther. Add `GameTestSupport.enclose(h, y, size, height)` when a hurt or fleeing mob could run off the edge.
- A test that changes server settings needs its own `friendai-gametest:config` environment resource under `src/gametest/resources/data/friendai-gametest/test_environment/`. Minecraft restores saved configuration at batch teardown, including after a failure. Assert on the test's own entity, owner or chunk ticket.

## Arena and coordinates

- Choose the smallest `GameTestArenas` template that contains the fixture and its movement range. Structure grid spacing uses these dimensions, and the shared floor and wall helpers reject a fixture wider than its template.
- Start fixtures at nonnegative relative X and Z.
- Call `h.absolutePos(...)` or `h.absoluteVec(...)` before passing a relative position to a level or entity API.

## Deterministic ticking

Drive an `ExecutionCoordinator` yourself inside `h.succeedWhen(() -> { coordinator.tick(); ... })`; do not wait on server time for task logic. Set `@GameTest(maxTicks = ...)` for slow tests.

## Tracing a hanging task

Use `TaskTrace.of(h, runner, companion).succeedWhen(() -> { … })` instead of a bare `h.succeedWhen(...)`. It records one line per tick: position, active task with its `debugStatus()`, queued jobs, sight backlog and how much the companion currently sees.

- It prints the last twelve ticks every six hundred failed ticks, at most twice.
- A task that fails and leaves the coordinator idle prints its last sixty ticks right then; an unexpected exception does the same.
- A green test prints nothing; a flaky one leaves the whole timeline in `build/gametest-shards/<class>.log`, which CI uploads when a shard fails.
- Do not add ad-hoc `System.out.println` probes to product code for a test.

## Batching boots

Every `runGameTest` boots a Minecraft server.

- Filter while debugging: `./gradlew runGameTest -Dfabric-api.gametest.filter=friendai-gametest:<test_name>`.
- The filter is one glob where `*` matches any text (`friendai-gametest:hunger_tests_*` runs the class); no negation, no pattern list.
- Run the affected class first. When a full run fails in an unrelated, timing-sensitive test, re-run only that test filtered instead of repeating the whole suite.

## Registering a new class

1. Add the class to the `fabric-gametest` entrypoint list in `src/gametest/resources/fabric.mod.json`, or it does not run.
2. Add it to exactly one shard in `.github/gametest-shards.json`.
3. Run `./gradlew verifyGametestShards`; `CI gate` does this before any shard starts and fails a class that is missing, listed twice or unknown.

## Scripted gates

`CompanionSessions` exposes `setGatewayOverride` and `setSpeakerWrapper` so tests script the LLM without touching the network.
