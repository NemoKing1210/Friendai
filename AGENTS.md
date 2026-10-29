# AGENTS.md

Guidance for AI coding agents (and humans) working on **Friendai**: a Fabric mod for Minecraft **26.3** that adds an AI companion you talk to by chat or voice. Mod id `friendai`, root package `dev.friendai`, MIT licensed.

The mod is **not client-only**: the server owns the companion entity, the brain, tasks and memory; the client adds rendering, the companion menu, voice input, speech output and the settings screen. Both sides must run the mod (the integrated server in singleplayer counts).

**Orientation for a new agent:**

1. Read "Commands" and "Project map" to find your way around.
2. Read `ARCHITECTURE.md` — the package map, the runtime flows, data, the network protocol — and "Rules that keep it working" here, before changing code.
3. Find the exact entry point in `ARCHITECTURE.md`, "Where to start".
4. Follow "Testing" and "Definition of done".
5. If several sessions work in this checkout, read "Parallel sessions" first.

**Taking a task from `TODO.md`?** Read "Working from TODO.md" first: most items need a spec and a plan before any code.

## Working from TODO.md

`TODO.md` at the repo root is the list of work that was asked for but not started yet; finished work lives in `TODO_ARCHIVE.md`, so the open list stays readable. Picking an item from it is a different entry point than "do this now":

1. **Write a specification and then a plan before writing code**, unless the item is trivial (see below). The spec goes to `docs/superpowers/specs/`, the plan to `docs/superpowers/plans/` (both git-ignored); name the `TODO.md` item they belong to.
2. Implement against that spec and plan, following "Test scope" and "Definition of done" like any other task.
3. Close the loop: when the item is done, tick it `[x]` and move it to `TODO_ARCHIVE.md`, in the same commit that carries the change. Never renumber the remaining items — the maintainer refers to work by number ("do task 2"), so numbers are stable and finished items keep theirs. The archive is read-only history: never renumber an archived item and never reuse its number.

**Pick a whole item, never a slice.** When the maintainer asks you to choose work from `TODO.md` ("take the next task", "pick something", "do a task"), choose a top-level item with its own number — `17`, not `17.1`. A sub-item is one slice of its parent, so picking it leaves the rest of the parent unattended and that number half-finished in the list, while the parent reads as still open. Once you have claimed the parent, work through its sub-items in order and tick each `[x]` as it lands. If the maintainer names a sub-item ("do 17.1"), that is an explicit order: take that line alone and leave the parent's box as it is (see the claim rule below).

**Claim the item before you start it.** Several sessions share this checkout, so an unmarked item can be taken twice. As the first edit of the work — before the spec, before any code — change the item's `- [ ]` to `- [~]`. That marker is the whole claim: **never add** a session name, a timestamp or an `**In progress**` line under the item, and do not ask another session to add one. Taking a single sub-item — only when the maintainer names that sub-item, per "Pick a whole item, never a slice" — marks that line the same way (`- [~] **2.3. …`) and leaves the parent's box alone. Check `grep -nE '^\s*- \[~\]' TODO.md` and `git log --oneline -5` before picking, and leave a claimed item to its session.

**The claim is never a commit of its own.** Mark the item `[~]` and leave `TODO.md` uncommitted: the marker waits and is committed only once the whole task is finished, together with the task's files (the commit that ticks the item `[x]` and moves it to `TODO_ARCHIVE.md`; for a task split into several commits, the final one). Do not make a `chore(todo): take task N` commit. The trade-off is real — another checkout cannot see an uncommitted marker — so before you take an item check `grep -nE '^\s*- \[~\]' TODO.md`, `git status` and `git log`, and when two sessions share the file, coordinate instead of trusting the commit history.

When the work ends: tick `[x]` and move the item to `TODO_ARCHIVE.md`, in the commit that carries the change. When you stop or abandon it: restore `[ ]` and say why in your final message. Never delete or silently rewrite another session's marker; a `[~]` older than 24 hours whose session is visibly gone may be taken over by saying so.

**When the spec and the plan are not needed.** Small, fully understood items go straight to code: a simple bug fix with a clear cause, a text change in the UI, a translation or comment fix, a version bump. The call is the agent's. If you can state the whole change in two sentences and the risk of a wrong direction is low, skip the paperwork. If the item spans several modules, changes an interface, a save format, a config schema or user-visible behavior that needs design, or you are not sure what the fix is, write the spec and the plan first. Unsure whether it is small? Write a one-paragraph spec: the cost is low, a wrong direction is not.

**Recording an item is not a commit of its own.** When the maintainer dictates work into `TODO.md`, write it and leave the file uncommitted — several items usually arrive in one sitting, and one commit per item is noise. Commit `TODO.md` once the batch is done or when the maintainer asks, along with the other files of that commit. This is one of two deliberate exceptions to "commit your own work as you finish it": dictated items wait for the batch, and a claim marker waits for the task it marks.

## Commands

Java **25** is required (Gradle and Loom refuse to run on older JVMs). If your default `java` is older, point Gradle at a JDK 25 for the command:

```bash
JAVA_HOME="/path/to/jdk-25" ./gradlew build
```

| Command | What it does |
|---|---|
| `./gradlew build` | Compile, run unit tests **and** the Fabric GameTests (`runGameTest` is part of `check`), then package. The full gate: CI runs it at merge to `main`; agents do not run it while working (see "Test scope"). |
| `./gradlew test` | Root and engine unit tests (JUnit 5, no Minecraft); root `test` aggregates `:engine:test`. |
| `./gradlew :engine:test --tests 'dev.friendai.<package>.*'` | Tests only the selected pure engine package. |
| `./gradlew test --tests '*SomeTest'` | One unit test class. |
| `./gradlew runGameTest` | In-game tests only. Boots a Minecraft server and runs the whole suite (several minutes). |
| `./gradlew runGameTest -Dfabric-api.gametest.filter='friendai-gametest:<class>_tests_*'` | One GameTest class (or one test by its full id) in under a minute. The filter is a single glob with `*` wildcards; it cannot exclude tests or list several patterns. |
| `./gradlew check` | Unit tests + GameTests + `spotlessCheck`, without packaging. |
| `./gradlew spotlessApply` | Rewrites Java sources in the root and engine with the project formatter (Palantir: 4-space indent, 120 columns) and sorts the imports. `-PspotlessFiles=<root-relative paths>` limits it to those files; prefix engine paths with `engine/`. Run it at the end of a task; CI runs `spotlessCheck`. |
| `./gradlew assemble verifyModArtifact` | Compile and package, verify nested engine classes/resources and fixture exclusion; `exportTestJar` refreshes `test-builds/friendai-<version>.jar`. Does not run tests. |
| `./gradlew runClient` / `runServer` | Dev client / server. `run/` is git-ignored; the server needs `run/eula.txt` with `eula=true`. |
| `./gradlew genSources` | Decompiled Minecraft sources, for looking up API names (see "Minecraft API notes"). |

The first build downloads Minecraft and takes minutes; later builds take seconds.

- Unit results: `build/test-results/test/*.xml` and `engine/build/test-results/test/*.xml` (one XML per class; sum `tests`/`failures`).
- GameTest results: console output — `========= N GAME TESTS COMPLETE` and `N required tests failed` (or `All N required tests passed`).
- Gradle may run `runGameTest` before `test`; if a GameTest fails, run `./gradlew test` separately to get the unit-test count.
- Only one Gradle run per checkout at a time: parallel builds fight over the Gradle lock and the shared `run/` directory.

## Project map

### Top level

| Path | What it is |
|---|---|
| `engine/src/main/java/dev/friendai/` | Game-independent library: `foundation/` shared contracts; `execution/` queue/tool contracts; `agent/` turn/planning/LLM; `features/` pure policies and models; `infrastructure/` JVM configuration, cloud speech HTTP, appearance HTTP and persistence adapters. Speech policies live under `features/speech/{stt,tts}`; local model files and native synthesis remain under `src/client/java/dev/friendai/client/audio`. No Minecraft, Fabric, optional UI or native audio dependency. |
| `engine/src/test/java/dev/friendai/` | Pure engine unit tests; `engine/src/testFixtures/java/` holds reusable pure fakes and scenario fixtures. |
| `src/main/java/dev/friendai/` | Common + server code (the mod's `main` source set), including Minecraft/server adapters. |
| `src/client/java/dev/friendai/client/` | Client-only code (Loom split source set). |
| `src/test/java/dev/friendai/` | JUnit 5 tests for root adapters and features; `testutil` holds root-only helpers. |
| `src/gametest/java/dev/friendai/test/` | Fabric GameTests (a separate mod id, `friendai-gametest`). |
| `src/main/resources/` | `fabric.mod.json`, `assets/friendai/lang/`, icon, GUI sprites. |
| `src/gametest/resources/` | GameTest `fabric.mod.json` (entrypoints) and `data/.../structure/*.nbt`. |
| `build.gradle`, `engine/build.gradle`, `settings.gradle`, `gradle.properties` | Loom + engine setup, dependencies, and all versions including `mod_version`. |
| `gradle/` | Wrapper. |
| `scripts/` | Maintenance scripts (`setup-branch-protection.sh`). |
| `.github/workflows/` | CI: unit tests, GameTests, build, commit lint, release. |
| `.githooks/` | Local `commit-msg` hook mirroring commitlint. Enable with `git config core.hooksPath .githooks`. |
| `docs/superpowers/{specs,plans}/` | Local design specs and implementation plans. **Git-ignored**; read for context, never commit. |
| `architecture/` | Tracked build, dependency and migration inventories/rules. |
| `test-builds/` | Exported test jars (`friendai-<version>.jar`). Git-ignored. |
| `run/`, `build/`, `logs/`, `.gradle/`, `config/` | Local runtime/generated files. Git-ignored. |
| `index.html`, `site/` | The interactive capability map (Minecraft-styled graphs of tools, memory, turn flow, architecture). `site/app.js` holds the data (`CATS`, `FLOW`, `MEM_CARDS`, `ARCH_CARDS`), `site/style.css` the look; graphs are drawn by `vis-network` (pinned CDN version in `index.html`), draggable and with physics. `site/i18n.ru.js` is the Russian translation (a second, deliberate exception to "everything in the repository is English", like `ru_ru.json`). Keep it current: see "Definition of done", item 9. |
| `AGENTS.md`, `ARCHITECTURE.md` | The two halves of this guide: the rules every session loads, and the reference (packages, flows, persistence, protocol). See "Code map and flows". |
| `README.md`, `CONTRIBUTING.md`, `MANUAL_CHECKLIST.md`, `TODO.md`, `TODO_ARCHIVE.md`, `commitlint.config.mjs` | User docs, contributor rules, manual test checklist, the open backlog and its finished-item archive (see "Working from TODO.md"), commit rules. |

### Code map and flows

The rest of the map lives in `ARCHITECTURE.md`: the source sets, the **Where to start** table, the dependency rule, every package with its classes, the runtime flows (one chat turn, the task lifecycle, the menu, configuration, speech in and out), data and persistence, the network protocol and the access rules. Read it before changing a package you have not touched.

Both files are part of the contract: add or move a package that either one names, and update it in the same commit.
## Rules that keep it working

1. **Threads.** The world is touched only on the server thread. Server-side HTTP (the brain) runs on its own thread and hops back with `server.execute(...)` through `ServerBrainScheduler.execute`. Client speech stays on the client: recognition and synthesis run on worker threads and hop back with `Minecraft.getInstance().execute(...)`, and only the server-side transcript handler uses `server.execute(...)`. A `CompletableFuture` callback never touches entities directly.
2. **A world skill = a `Tool` + usually a `Task`.** Tools describe the skill to the LLM and validate arguments; tasks do the work over time. Add a skill by writing the task, registering the tool in the matching `*Tools` group called from `FriendaiTools.build`, and adding a GameTest. The core does not change.
3. **Several tool calls in one response** must run in order, and Anthropic/Gemini must receive all the results in **one** user message (otherwise HTTP 400). Covered by tests; keep them green.
4. **History trimming** must never leave a tool result or a call without results at the head, and must never drop the turn in progress.
5. **A late LLM response** after `stop`/dismiss/owner logout must be dropped silently (generation counter in engine `agent/turn/CompanionBrain`).
6. **Malformed tool arguments** from the model must become an error `tool_result`, never an exception.
7. **API keys** never appear in logs, chat, exception messages or `toString()`. Keys go in headers, never URLs.
8. **Layer boundary.** Pure classes import neither `net.minecraft.*` nor `net.fabricmc.*` (see `ARCHITECTURE.md`, "Overview"). If you need a world fact, add it to a pure data type and fill it in a Minecraft adapter.
9. **Prefer vanilla behavior.** Placement, right-clicking and protection checks go through the fake player (`CompanionHands`); do not reimplement vanilla block/item logic. Use `Items`/`Blocks`/registry lookups, never hard-coded item ids.
10. **Optional integrations stay optional.** YACL and Mod Menu are `compileOnly`; only `client/config` may touch them, and only when the mod is present.
11. **The client reads synced data only.** A screen, HUD or renderer may use what the server publishes through `SynchedEntityData` (`syncedFood()`, `syncedXpLevel()`, `creativeMode()` …) and never server-only state. A new client-side fact is a new `EntityDataAccessor` in `defineSynchedData`, not a field read through the entity.
12. **Per-tick code stays cheap.** `Task.tick`, `TaskRunner` and the combat controller run every server tick: no allocations, registry lookups or pathfinding in the common path, bounded work per tick (as `PathSearch` does with its time budget), timeouts in ticks rather than wall-clock, and state that stays resumable so `hold()` can stop the task anywhere.

## Mixins

The mod ships one mixin set — `friendai.mixins.json`, package `dev.friendai.mixin`, `defaultRequire: 1` — so failures here are easy to cause and expensive to find:

- **A mixin is the last resort.** Prefer a Fabric event, a `Mob` goal, entity data or another vanilla hook. When vanilla offers no seam, as with hostile goals that ask for `Player`, the class javadoc says why, next to the code it patches.
- One file per target class, named `<Target>Mixin`; prefix every injected member with `friendai$`; never `@Overwrite` — inject or redirect and leave the vanilla path intact.
- `defaultRequire: 1` turns a renamed or removed vanilla method into a boot failure, not a compile error. A mixin change is verified by loading the game (GameTest boot or `runClient`), never by compilation alone, and the behavior it enables needs a GameTest.
- The current set has four entries: `PiglinAiInvoker`, `BehaviorUtilsBarterOutputMixin`, `NearestAttackableTargetGoalMixin` and `TargetGoalAccessor`. The hostile-goal mixins let mobs treat the companion as a player; the barter mixin captures exact vanilla drop UUIDs. Check all four before changing entity AI, targeting or barter behavior.

## Language and localization

- **Everything in the repository is English**: code, comments, identifiers, logs, Markdown, commit messages, config defaults, the system prompt, tool descriptions and results, task summaries.
- **Player-facing text is always a translation key** rendered with `Component.translatable`. `src/main/resources/assets/friendai/lang/en_us.json` is the source of truth; `ru_ru.json` must have exactly the same keys and `%s`/`%d` placeholders. `LangFilesTest` enforces this and also fails if code references a key (a string literal like `"friendai.something"`) that does not exist. Do not build keys by concatenating a literal that ends in `.` — the test will flag it.
- Pure-Java layers return `UserMessage`; the Minecraft layer converts it with `Components.of(...)`.
- Not translated: LLM replies, anything addressed to the LLM, logs, command literals.
- To add a string: add the key to `en_us.json`, the same key to `ru_ru.json`, reference it from code, then run `./gradlew test --tests '*LangFilesTest'`.

## Formatting

Java layout is not a matter of taste: `build.gradle` wires [Spotless](https://github.com/diffplug/spotless) to [palantir-java-format](https://github.com/palantir/palantir-java-format) over `src/*/java/**/*.java`, both versions pinned in `gradle.properties`. Palantir is the formatter that matches this repository — 4-space indent and 120-column lines — where google-java-format would re-indent every file to 2 spaces.

- **Formatting is the last step of every task, before the commit.** Run `./gradlew spotlessApply -PspotlessFiles=<comma-separated root-relative paths>` over the files you changed — the ones `git diff --name-only -- '*.java'` lists plus the files you created. Include `engine/` in paths under the engine module. The root formatter task delegates to both projects and each formats only its own selection. It rewrites them, sorts and prunes imports, trims trailing whitespace and ends the file with a newline. The `Formatting` job in CI runs `spotlessCheck` and fails on any file that differs, so an unformatted file costs a red gate.
- **Never run a bare `./gradlew spotlessApply` in a shared checkout.** Without `-PspotlessFiles` it rewrites the whole tree, including another session's uncommitted files (see "Parallel sessions"). The bare form is for a checkout you own and for the one-time sweep after a formatter bump; in a shared one, pass your own files, or `./gradlew spotlessCheck -PspotlessFiles=…` to check without writing.
- The tool owns the layout: never hand-format around it, and do not reflow a file yourself to "fix" its diff.
- `// spotless:off` and `// spotless:on` around a block that must keep its own layout (an ASCII table, a generated literal, a golden file written out as text), with a reason next to it.
- A new source set is added to the `target` line in `build.gradle`. A formatter bump goes into `gradle.properties` together with the `style:` commit that applies it.

## Testing

### Test scope: group while working, everything at merge

A full run is about 9 minutes of GameTests plus the unit suite, so an agent does **not** run it while working on a task. The rule:

- **While working** (new module, skill, fix): run only the group that covers your change.
  - Root unit tests of the area: `./gradlew test --tests 'dev.friendai.<package>.*'` (or a single class). For engine-only code, use `./gradlew :engine:test --tests 'dev.friendai.<package>.*'`.
  - GameTests of the area, only if the change touches the game layer: one class glob per run, e.g. `./gradlew runGameTest -Dfabric-api.gametest.filter='friendai-gametest:<class>_tests_*'` (`BuildTests` → `build_tests_*`, `FarmingTests` → `farming_tests_*`). Name the class(es) you added or whose behavior you changed; a new skill's GameTest class counts.
  - Always run `LangFilesTest` when you touched a language file.
- **Do not** run `./gradlew build`, `check` or a bare `runGameTest` to "be safe" before a commit or a task report.
- **At merge to `main`** the full gate runs: CI (`.github/workflows/ci.yml`) runs all unit tests and all GameTests on every pull request, merge-queue entry and push to `main`, and `CI gate` is the required check. A local full run is the maintainer's call (before tagging a release), not an agent's default.
- In the task report, state which group you ran and its real result, and say plainly that the full suite was left to CI. A failure in an unrelated test on the full run is CI's finding to triage, not a reason to widen your own scope.
- **A world change is exercised once before the commit.** Unit tests prove logic, not that a task runs: a change to a `Task`, `TaskRunner`, the entity, a builder or a menu is run once — a filtered GameTest class, or a real `runClient`/`runServer` session — and the report says what was run. Compiling is not running.

### Batch the loop: write everything, run once

An agent slows itself down by running Gradle after every edit: a unit run costs tens of seconds, a GameTest boot minutes, the edit itself seconds. The rule:

1. Write the whole slice first: the logic, the tests that pin it, the language keys, the call sites, the doc line.
2. Run once, only the group that covers it (see "Test scope"), and read every failure before touching anything.
3. Fix all of them in one pass, then re-run. Do not restart the loop after each individual fix.
4. Treat compilation as part of the batch: write against the type system, not against the compiler's first error message.
5. Format as part of the batch too: one `./gradlew spotlessApply -PspotlessFiles=<your files>` before the single test run, never after it (see "Formatting").

**Keep the tests honest while batching.** A test written next to its implementation is still the specification, but a test that passes on the first run has proven nothing until you know it can fail. Write it against the current behavior when you can, or break the one line it guards and watch the red once, inside the same batch. Also watch for a failure that is not the one you are testing: a missing floor, a GameTest class absent from `fabric-gametest`, a fixture that throws, a test that fails on setup — each hides whether the behavior is right.

- **TDD, batched.** Write the test before the implementation and keep it honest (see "Batch the loop" above), but do not run it alone after each edit. Reusable pure fakes live in `engine/src/testFixtures/java/dev/friendai/testutil`; root-only helpers stay in `src/test/java/dev/friendai/testutil`. No mocking frameworks.
- **What a test must prove.** Behavior, boundaries, state transitions, precedence, error paths — nothing else. Do not pin translation wording, do not assert that a helper was called, do not let a fake echo its own input back, and do not re-pin incidental defaults. A test that only proves the code still says what it says is deleted with the change it guards.
- **Unit fakes you can reuse:** `MockHttpServer` (scripted loopback HTTP, records method/path/headers/body), `FakeGateway` (scripted LLM responses/calls/failures), `FakeToolExecutor` (records specs/executions/access), `ManualScheduler` (virtual clock), `GridCellView` (ASCII grid `CellView` in engine test fixtures, shared with pathing tests), `RecordingSpeaker` (captures speech/errors/thinking).
- **GameTests share one world and run in parallel.** So: never rely on global state; give each test its own block type (`MineBlocksTests` uses a different log per test) and filter item/entity counts by type; build a floor with `GameTestSupport.platform(h, size)`; use `GameTestSupport.mockPlayerAt(...)` because mock players log in at the world origin, in an unloaded area. Drive a `TaskRunner` yourself inside `h.succeedWhen(() -> { runner.tick(); ... })` for deterministic ticking, and set `@GameTest(maxTicks = ...)` for slow tests.
- **Entities tick only in the chunk(s) the test structure covers.** A mob that walks or is knocked out of them freezes mid-step: it keeps its velocity, stops falling and stops taking damage, so a fight against it never lands another hit and a companion that walks out stops arriving. Arenas are wider than the 8x8 structure, so build them with `GameTestSupport.platform`/`platformAt`, which force-load every chunk they cover; add `GameTestSupport.enclose(h, y, size, height)` when a hurt or fleeing mob could run off the edge.
- **GameTest runs are expensive; batch them.** Every `runGameTest` boots a Minecraft server, so do not repeat a filtered boot per test. While debugging one test, filter it: `./gradlew runGameTest -Dfabric-api.gametest.filter=friendai-gametest:<test_name>`; `build.gradle` forwards the property to the GameTest server. The value is one glob where `*` matches any text (`friendai-gametest:hunger_tests_*` runs the whole `HungerTests` class, 24 tests in about 50 s including the boot); there is no negation and no pattern list, so "everything except X" is not expressible. A full run takes about 9 minutes, mostly in the batches holding the long `BuildTests`; for a change in one area run that area's class glob first. Run the tests that cover the change when practical; a full `./gradlew runGameTest` is only needed when the change touches the game layer, and it is not a prerequisite for a commit. If a full run shows a failure in an unrelated, timing-sensitive test (the shared world makes a few rotate), re-run only that test filtered for a clean signal instead of repeating the whole suite.
- **New GameTest class?** Add it to the `fabric-gametest` entrypoint list in `src/gametest/resources/fabric.mod.json`, or it will not run.
- Per-owner test hooks exist on `BrainService` (`setGatewayOverride`, `setSpeakerWrapper`) so tests can script the LLM without touching the network.
- `MANUAL_CHECKLIST.md` lists what automation cannot cover (real providers, voice, LAN, both UI languages). Do not tick an item you did not perform.

## Minecraft API notes (26.3)

26.x is unobfuscated: **Mojang names**, no Yarn. Names differ from older versions you may remember, so look them up instead of guessing:

- Decompiled sources: after `./gradlew genSources`, find `*-sources.jar` under `.gradle/loom-cache/minecraftMaven/net/minecraft/` (`minecraft-common-*` and `minecraft-clientOnly-*`). Extract them somewhere outside the repo and `grep`. Without sources, `javap -p -c -cp <minecraft.jar> <class>` still works because the jar keeps its names.
- Fabric API / YACL / Mod Menu jars live in the Gradle cache; inspect with `javap -cp <jar> <class>`.
- Names removed or moved in 26.x (do not use the old ones): `SavedData`/`SavedDataType` are in `net.minecraft.world.level.saveddata`; `level.getSharedSpawnPos()` → `level.getRespawnData().pos()`; `dimensionType().bedWorks()` → `level.environmentAttributes().getDimensionValue(EnvironmentAttributes.BED_RULE)` (`BedRule`); `level.getDayTime()` is gone (use `level.isDarkOutside()` for night, or the world clock via `level.getDefaultClockTime()`).
- Things that tripped us before: `Identifier` (not `ResourceLocation`); entity save/load uses `ValueOutput`/`ValueInput`; `Entity.snapTo`; the current screen is `minecraft.gui.screen()`, toasts are `minecraft.gui.toastManager()`, open a screen with `setScreenAndShow`; `Mob.swing(hand, SwingAnimation.DEFAULT)`; `EntityRenderers.register`; HUD via `HudElementRegistry`; keybinds via `KeyMappingHelper` and `KeyMapping.Category.register(Identifier)`; command permissions via `Commands.hasPermission(Commands.LEVEL_GAMEMASTERS)`.
- Item behavior lives in data components: `DataComponents.TOOL` (mining rules and speeds), `ATTRIBUTE_MODIFIERS` (damage/speed), `EQUIPPABLE`, `WEAPON`, `FOOD`. Do not hard-code tool tiers; ask the stack.
- YACL and Mod Menu are **optional** dependencies. Code that touches them lives in `client/config` and must only be loaded when `yet_another_config_lib_v3` is present.

## Common recipes

### Add a skill

1. Write the `Task` in `task/builtin/` (or a helper package) and register it in `TaskFactory` only if it must survive a reload.
2. Add a `Tool` definition in the matching group class under `tool/builtin/` (`ToolKit.add` with a `TaskToolDef` for a task-backed skill, `instantTool`/`callerTool` otherwise; validate arguments, keep the description LLM-facing and English).
3. Add player-facing strings to both language files.
4. Add a GameTest (and register the class in the gametest `fabric.mod.json`).
5. Bump `mod_version` (MINOR for a new skill), run `./gradlew build`, then refresh the test jar.

### Add a config option

1. Add the field to `ServerConfig` or `ClientConfig` with a default and validation/clamping in `validate`.
2. If the schema changes, bump the config version and add a migration in `ConfigStore`.
3. Expose it in the YACL screen (`client/config`) if it is a server/client setting the player edits.
4. Add the label/tooltip keys to both language files; add a unit test for validation/migration.

### Add an LLM provider or protocol

1. A new wire format goes in `llm/protocol/` as a `toWire`/`fromWire` pair; keep the shared multi-message rules in `ProtocolSupport`.
2. Register the protocol and any preset/routing data in `ProviderRegistry.withBuiltins`.
3. Add unit tests with `MockHttpServer` and `FakeGateway`; update the README provider table if it is user-visible.

### Add a command

1. Add the literal and permissions in `command/FriendaiCommands`.
2. Return a `UserMessage`/`Component` translation key, never a literal string.
3. Add keys to both language files; add a GameTest if the command touches the world or permissions.

### Add a translation key

1. Add it to `en_us.json`, then the same key to `ru_ru.json` with identical placeholders.
2. Reference it via `Component.translatable` or `Components.of(new UserMessage(...))`.
3. Run `LangFilesTest`; it fails on missing, extra or placeholder-mismatched keys.

### Add a client payload

1. Define the payload in `net/`, register it in `FriendaiNetworking`, and handle it on the correct thread (server: hop with `server.execute`).
2. Guard sending with `ClientPlayNetworking.canSend` so a vanilla server does not crash the client.
3. Filter anything sensitive on the server (`DebugAccess`/`Operators`), not on the client.

### Change persistence

1. Bump the format version and add a migration (entity data is versioned by Mojang; memory/config/blueprints are ours).
2. Keep writes atomic (temp + move) and preserve the `.bak` recovery path.
3. Add a round-trip test and, for world data, a GameTest that saves and reloads.

## Troubleshooting

| Symptom | Likely cause / fix |
|---|---|
| `Dependency requires at least JVM runtime version 25` | Gradle is on an older JDK. Set `JAVA_HOME` to a JDK 25 for the command. |
| `Процесс не может получить доступ к файлу` / world lock, or Gradle lock timeout | Another session is running `runGameTest`/`runClient` or a build. Wait and retry; never delete lock files or kill other Java processes. |
| Compile error in a file you did not touch | Another session's work in progress. Check `git status`/`git diff` for that file and report it instead of patching. |
| GameTest fails only in the full run | Shared world or timing. Re-run that test filtered; if it passes alone, note it as flaky rather than changing unrelated code. |
| A mob freezes, a fight never lands a second hit, a companion stops arriving | The entity left the force-loaded chunks; use `GameTestSupport.platform`/`platformAt`/`enclose`. |
| `LangFilesTest` fails | Key missing in one language, placeholder mismatch, or a key built by concatenation. |
| `spotlessCheck` fails on files you did not touch | Another session's uncommitted work. Format and commit only your own files; do not rewrite theirs. |
| A Minecraft/Fabric name does not compile | Look it up in decompiled sources or with `javap`; do not guess a variant. |
| A GameTest class does not run | It is missing from the `fabric-gametest` entrypoints in `src/gametest/resources/fabric.mod.json`. |
| `./gradlew build` stops in `runGameTest` | Fix the GameTest first; `test` may not have run. Run `./gradlew test` for the unit count. |
| The settings screen is missing | YACL is not installed; the mod still works through the config files. |

## Working with subagents

Use the delegation tool your environment provides (`task`, `Task`/`Agent`, `subagent`, `@mention`, "spawn agents", etc.). The rules below are tool-independent; the adapter notes at the end only name the tools.

**You are the orchestrator.** Split the work, write the briefs, integrate the results, and own the final verification. Subagents do bounded pieces.

**Children run on a cheap model.** A subagent does bounded, mechanical work — a search, a slice of a diff, a review pass — so it runs on the default or the cheapest model that can do the job. Never hand a child an expensive model: no `astra` (or anything in that price class) as a `model`/`model_profile` argument, no `.codex/agents/*.toml` pinned to one, and no copying a pricey session model into a child. If a piece of work seems to need the expensive model, the brief is wrong: split it smaller or do that piece yourself. Say which model a child ran on when a delegation's cost matters.

### When to delegate

- The task splits into 2+ independent parts (separate modules or files, parallel searches, comparing several options, writing tests, reviewing). Launch them **in parallel in one step**, not one after another, if the tool allows it.
- Heavy reading goes to a subagent: wide code searches, tracing a call path through many files, reading logs or decompiled Minecraft sources. Ask for a short summary with `path:line` references so your own context stays small.
- Review changes with parallel reviewer subagents (e.g. one for the thread and layer rules, one for tests and localization), when the tool supports it.

### When NOT to delegate

- Steps depend on each other's output, the task is small (a few tool calls), or the decision needs one continuous context. Delegating then only adds tokens and latency.
- If your environment has no subagents, do not invent them. Do the work yourself, but still split it into independent steps and read only what you need.

### Writing a brief

A subagent does not see this conversation. Make each brief self-contained:

- **Goal** and why it matters, in one or two sentences.
- **Paths** to read and, for writers, the exact files it may change.
- **Constraints**: the relevant rules from this file (layer boundaries, threading, English only, no commits unless asked), and what is out of scope.
- **Result format**: e.g. "reply with a summary under 200 words plus `path:line` list".
- **Done when**: a checkable criterion (a test passes, a symbol is found, a list is complete).

### Isolation

- Subagents that edit code must own disjoint files. Fix the split before launching. If two parts must touch the same file, run them one after another.
- Prefer a separate branch or worktree for parallel writers if the tool offers it.
- Only one agent should run `./gradlew` in a given checkout at a time: parallel builds fight over the Gradle lock and the shared run directory. Give builders their own worktree, or run the build yourself after integrating.

### After they finish

- Subagent reports are claims, not proof. Read the diff yourself before saying the work is done; run `./gradlew build` when the change warrants verification (see "Definition of done").
- Resolve conflicting edits yourself. Do not ask a subagent to settle a conflict between subagents.

### Examples

- Research. Before: read `PromptBuilder`, `CompanionBrain` and `ToolRegistry` one by one in the main session. After: three read-only subagents, one per area, each returns the key types and call sites in a few lines.
- New skill. Before: write the task, then the tool, then the GameTest, then both lang files in sequence. After: after you fix the interface, run the task + tool, the GameTest, and the `en_us.json`/`ru_ru.json` entries in parallel on disjoint files; then integrate and build.
- Review. Before: one pass over the whole diff. After: parallel reviewers for layer/thread rules, test coverage, and localization; you merge the findings.

### Adapter notes

- **oh-my-pi (`omp`)**: tool `task` (parallel, optionally worktree-isolated); `wait` collects background results; `/review` spawns reviewers. Custom agents: `.omp/agents/*.md` (project) or `~/.omp/agent/agents/*.md`; `.claude/agents` is not read. Bundled: `scout` (read-only), `reviewer`, `task`.
- **Claude Code**: tool `Agent` (formerly `Task`). Custom agents: `.claude/agents/*.md` with YAML frontmatter (`name`, `description`, `tools`, `model`; `isolation: worktree` for an isolated checkout). Default cap is 20 concurrent subagents.
- **Codex CLI**: enabled by default; ask for it explicitly ("spawn agents for X, Y, Z in parallel"). Custom agents: TOML in `.codex/agents/` (`name`, `description`, `developer_instructions`). Cap: `agents.max_concurrent_threads_per_session`. Prefer parallelism for read-heavy work; be careful with concurrent writes. If Codex children would inherit the session's model, that is exactly the case the cheap-model rule above forbids: pass the cheap model explicitly and keep the TOMLs free of `model` pins — a session running `astra` spawns cheap children or none.
- **opencode**: `Task` tool and `@name` mentions. Built-in subagents: `general`, `explore`, `scout`. Custom agents: `.opencode/agents/*.md` with `mode: subagent`. Not an import: opencode does not expand `@file` in `AGENTS.md`.
- **pi**: no built-in subagents; they come from an installed extension (typically `.pi/agents/*.md` definitions, which may need project trust). Check which extension is loaded before relying on delegation.

## Parallel sessions

The maintainer often runs several agent sessions on this repository at once, usually in the same checkout. Files can change under you at any time, and a dirty `git status` is normal, not an error.

- **Assume other changes are someone else's work.** Uncommitted modifications, untracked files and new directories you did not create may belong to another live session. Do not revert, delete, stash, reformat or "clean up" them.
- **Never run `git checkout -- <path>`, `git restore`, `git reset --hard`, `git stash` or `git clean`** to get a tidy tree. If a file blocks you, work around it or report it.
- **Re-read before you edit.** Another session may have changed the file since you last read it. Make small, targeted edits instead of rewriting whole files, and re-check the file after a failed edit.
- **Stay in your lane.** Touch only the files your task needs. Spot a problem elsewhere? Mention it in your final message instead of fixing it.
- **Commit only your own files**, naming them explicitly (`git add <path>...`). Never use `git add -A` or `git commit -a`. If a file holds both your edits and someone else's, say so and leave it uncommitted.
- **Failures outside your change may not be yours.** A build or test error in code you did not touch is often another session's work in progress. Check `git status` and `git diff` for that file first; if it is not yours, report it rather than patching it, and re-run later.
- **One Gradle run at a time per checkout** (see "Working with subagents"). If a build fails on a lock or the `run/` directory, wait and retry; do not delete lock files or kill other Java processes.
- **Claim your `TODO.md` item first.** See "Working from TODO.md": flip `- [ ]` to `- [~]` and leave it uncommitted — no session line, and no separate claim commit. It lands with the task's files when the task is done.
- **Prefer a worktree** for work that edits many files or runs long builds, so you do not collide with other sessions. Do not delete or rewrite other sessions' worktrees or branches.
- In the final message, list the files you changed and mention any foreign changes you noticed, so the maintainer can reconcile them.

## Git and repository hygiene

- **Commit your own work as you finish it.** Do not leave a completed task uncommitted waiting for a later request. Stage the files you changed explicitly (`git add <path>...`) and follow the parallel-session rule: never `git add -A`, and leave a file uncommitted when it mixes your edits with someone else's.
- **Large tasks commit in batches.** When a task is split into subtasks, or executed from a plan with steps, commit after each finished subtask or step instead of one huge commit at the end. Each commit should be one coherent change; bump the version once per task (see "Versioning"), in the commit that carries the change or in a final `chore: bump version to X.Y.Z`.
- Commit messages: conventional style (`feat:`, `fix:`, `chore:`, `docs:`, optional scope), imperative, English. **No `Co-Authored-By` or other AI attribution lines.** Header ≤ 100 characters, no trailing full stop; the `commit-msg` hook and `commitlint.config.mjs` enforce this.
- `docs/` (design specs and implementation plans) is **git-ignored on purpose** — never commit it. Read it for context if it exists locally (`docs/superpowers/specs/`, `docs/superpowers/plans/`).
- Never commit generated or local files: `run/`, `logs/`, `build/`, `.gradle/`, game configs, worlds, downloaded whisper models. `.gitignore` anchors these to the repo root; keep new entries anchored (`/config/`, not `config/`) so they cannot swallow source packages such as `src/**/config/`.
- Do not rewrite history, force-push or delete branches without being asked.
- Config files at runtime: `config/friendai-server.json`, `config/friendai-client.json`; whisper models in `<gameDir>/friendai/models/`.

## Versioning

The single source of truth is `mod_version` in `gradle.properties` ([Semantic Versioning](https://semver.org/): `MAJOR.MINOR.PATCH`). The jar name, `fabric.mod.json` and release tags all derive from it; a release tag `vX.Y.Z` must equal it (the release workflow checks).

Bump it as part of any change that alters the shipped mod, by the size of the change:

| Change | Bump | Examples |
|---|---|---|
| Bug fix, small tweak, translation fix, dependency patch | **PATCH** (`0.1.0` → `0.1.1`) | `fix:` commits, `perf:` with no API change |
| New feature or skill, new setting, new provider preset, user-visible improvement | **MINOR** (`0.1.1` → `0.2.0`), PATCH resets to 0 | `feat:` commits |
| Breaking change: config format change that needs a migration, removed or renamed command/tool/setting, save-data incompatibility, Minecraft or Java version bump | **MAJOR** (`0.x` → next `MINOR`, see below), lower parts reset to 0 | `feat!:` / `BREAKING CHANGE:` commits |
| No effect on the shipped jar | **no bump** | `docs`, `ci`, `test`, `style`, `chore`, `build` (tooling only), pure `refactor` |

- While the major version is `0` (pre-1.0), breaking changes bump MINOR instead of MAJOR. Moving to `1.0.0` is the maintainer's decision; never do it on your own.
- One bump per task/PR, sized by the largest change in it, not one per commit. Put it in the same commit as the change, or in a final `chore: bump version to X.Y.Z` commit.
- Changing the config schema: also add the `ConfigStore` migration and bump the config version (see `config/`), in addition to the mod version.
- Do not create tags or releases yourself; tagging `vX.Y.Z` is the maintainer's call. Tell them when the version is ready to release.
- When unsure between two sizes, pick the larger one and say why in the final message.

## Definition of done

0. **The change must compile before it is committed.** Tests are optional for a commit, compilation is not: run `./gradlew assemble verifyModArtifact` (compiles every source set, packages and inspects the mod jar) and read its result, with `JAVA_HOME` pointing at JDK 25. Never commit code that was not compiled, and never rely on "the API probably has that method" for Minecraft names; look them up (see "Minecraft API notes (26.3)"). A commit that breaks the build blocks every other session. Formatting is part of this gate: run `./gradlew spotlessApply -PspotlessFiles=<the files you changed>` before the commit (see "Formatting").
1. Tests are not a gate for a commit: commit a coherent change when it is ready. Run only your group's tests (see "Test scope" under Testing); the full suite is CI's job at merge to `main`. Quote the real result of what you ran (unit-test count and GameTest line) instead of assuming.
2. New player-facing strings exist in both language files.
3. New behavior has a test that pins it and would fail without the change (see "Batch the loop" for how to prove that inside one run); bugs get a regression test.
4. Layer boundaries respected (no Minecraft imports in the pure layers).
5. `mod_version` bumped according to "Versioning" (or a stated reason why no bump is needed).
6. Anything you could not verify (real provider, real microphone, GUI) is stated plainly rather than implied.
7. Your changed files are committed (unless the file also holds another session's edits — then say so). For a multi-step task, the commits follow the batches you finished.
8. `test-builds/friendai-<version>.jar` is refreshed (`exportTestJar` runs during `build`/`assemble`) and you tell the maintainer its path and version.
9. **The capability map stays current.** When a change adds, renames, removes or materially changes something `index.html` shows — a tool or skill (the `CATS` lists in `site/app.js`, written as `name|description`, with `~` for built-in behaviour), the turn flow (`FLOW`), stored data and persistence (`MEM_CARDS`), or the layers, packages and rules (`ARCH_CARDS`, `ARCH_FLOW`) — update `site/app.js` in the same commit, in English, matching the surrounding entries. Add the Russian text for every new or changed string to `site/i18n.ru.js` (keys are the exact English strings; a missing key falls back to English, so nothing breaks, but the Russian view then shows English). A new category also needs a domain and a colour from `COLORS`. Open the page once in a browser and check that the affected graph still renders without overlaps. If nothing it shows changed, no edit is needed.

## Glossary

- **Companion** — the NPC entity (`CompanionEntity`), one per owner, carrying the inventory and hunger.
- **Owner / trusted / guest** — the player who summoned the companion, a player the owner trusts, and anyone else who talks to it.
- **Brain** — `CompanionBrain`, the per-owner agent loop that turns messages into LLM requests and tool calls.
- **Turn** — one user message plus the tool calls and final reply that answer it.
- **Skill** — a player-facing ability exposed to the LLM as a `Tool`, usually implemented by a `Task`.
- **Instruction skill** — bounded plain-text procedural guidance stored in `skill/`, selected per turn; it advises but never executes or changes tool permissions.
- **Description** — the companion summary/details plus the owner’s explicit language/address profile in memory format 4.
- **Tool** — the LLM-facing definition (schema, access level, executor) registered in `FriendaiTools`.
- **Task** — long-running world work driven tick by tick by `TaskRunner`.
- **Plan** — an ordered list of tasks (`run_plan` / `SequenceTask`).
- **Blueprint** — a saved or generated block layout that `BuildTask` places.
- **Station** — a crafting/cooking block the companion uses; a `StationLease` remembers one it placed.
- **Perception** — the surroundings and owner focus rendered into prompt text.
- **Snapshot** — the menu state built by `SnapshotBuilder` and sent to the client.
- **Provider / preset / route / protocol** — where an LLM request goes and how it is encoded (engine `agent/llm/provider` and `agent/llm/protocol`; root `llm/http/HttpTransport` implements the engine `JsonHttpTransport` port).
