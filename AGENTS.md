# AGENTS.md

Guidance for AI coding agents (and humans) working on **Friendai**: a Fabric mod for Minecraft **26.3** that adds an AI companion you talk to by chat or voice. Mod id `friendai`, root package `dev.friendai`, MIT licensed.

The mod is **not client-only**: the server owns the companion entity, the brain, tasks and memory; the client adds rendering, the companion menu, voice input, speech output and the settings screen. Both sides must run the mod (the integrated server in singleplayer counts).

**How to read this file.** A **bold** sentence states a rule or a hard-won fact; the plain text around it explains how to meet it. `MUST`/`SHOULD`/`MAY` keep their usual meaning. Where this file and the code disagree, the code wins and the file is fixed in the same change.

**Which file says what.** `AGENTS.md` is the canonical rulebook for agents; `CONTRIBUTING.md` is a short human-facing summary that must not add rules — when the two disagree, `AGENTS.md` wins and `CONTRIBUTING.md` is fixed; `ARCHITECTURE.md` is the reference half (package map, runtime flows, data, protocol) and `GLOSSARY.md` defines the terms. Conditional procedures live in `.agents/skills/`: they are read only when the task calls for them.

**Changing this guide is its own docs commit.** A rule change needs the maintainer's agreement, a `docs:` commit that says why, and no code riding along; never edit a rule to match a shortcut you just took.

**The guide is checked.** `scripts/check-docs.sh` fails on a relative link to a missing path, on a `#anchor` that no heading in that file produces, and on a table row whose cell count differs from its header's; CI's `Docs links` job runs it and `CI gate` requires that job. Run it after you rename a heading, move a file, add a link or edit a table.

**Orientation for a new agent:**

1. Read "Commands" and "Project map" to find your way around.
2. Read `ARCHITECTURE.md` — the package map, runtime flows, data and the network protocol — and "Rules that keep it working" here, before changing code. Look a term up in `GLOSSARY.md` when it is unfamiliar.
3. Find the exact entry point in `ARCHITECTURE.md`, "Where to start".
4. Follow "Testing" and "Definition of done".
5. If several sessions work in this checkout, read "Parallel sessions" first.
6. Before starting, check whether the task splits into independent parts — see "Working with subagents".

**Taking a task from `TODO.md`?** Read "Working from TODO.md" and the [`executing-todo-items`](.agents/skills/executing-todo-items/SKILL.md) skill: most items need a spec and a plan before any code.

**Contents:** [Working from TODO.md](#working-from-todomd) · [Commands](#commands) · [Project map](#project-map) · [Rules that keep it working](#rules-that-keep-it-working) · [When to stop and ask](#when-to-stop-and-ask) · [Mixins](#mixins) · [Language and localization](#language-and-localization) · [Formatting](#formatting) · [Testing](#testing) · [Minecraft API notes](#minecraft-api-notes-263) · [Common recipes](#common-recipes) · [Failure modes we have already seen](#failure-modes-we-have-already-seen) · [Debugging with logs](#debugging-with-logs) · [Troubleshooting](#troubleshooting) · [Working with subagents](#working-with-subagents) · [Parallel sessions](#parallel-sessions) · [Git and repository hygiene](#git-and-repository-hygiene) · [Branches and pull requests](#branches-and-pull-requests) · [Versioning](#versioning) · [Changelog](#changelog) · [Definition of done](#definition-of-done)

## Working from TODO.md

`TODO.md` is the one list of work: open items and, until the list is cleared, finished ones ticked `[x]`. Clearing is an explicit request handled by the [`clearing-todo-list`](.agents/skills/clearing-todo-list/SKILL.md) skill; ordinary work never removes an item. The full order of operations — gates for a stale premise, regression watch, when to ask, plan, verify, close-out — is in the [`executing-todo-items`](.agents/skills/executing-todo-items/SKILL.md) skill; the contract below is what any session must honour.

1. **Write a specification and then a plan before writing code**, unless the item is trivial. The spec goes to `docs/superpowers/specs/`, the plan to `docs/superpowers/plans/` (both git-ignored); name the `TODO.md` item they belong to. Skip the paperwork only when the whole change fits two sentences and the direction is settled.
2. **Check the premise before claiming.** Read the code the item names: the cited `path:line` may have moved, another session may already have delivered the behavior, and the item's assumption may be wrong. Ask what the change does to the product, not only whether it is implementable.
3. **Pick a whole item, never a slice.** When asked to choose work, offer two or three candidates (number, what the owner gets, size, risk, recommendation) and start when the maintainer answers; take the item directly when it was named, when "anything" was said, or when only one unclaimed item is left. A sub-item is taken alone only when the maintainer names it.
4. **Claim it as the first edit:** change `- [ ]` to `- [~]`, with no session name, timestamp or `**In progress**` line. Check `grep -nE '^\s*- \[~\]' TODO.md`, `git status` and `git log` first. The marker is never a commit of its own; it lands with the finished task, together with `[x]`.
5. **Never renumber an item and never reuse a number**, finished or open: the maintainer refers to work by number. A cleared range stays retired in the header, and a surviving open or claimed item keeps its number.
6. **If the item as written would leave the mod worse, do not implement it.** Restore `[ ]`, write one line under it — `**Skipped:** <what is wrong> (<evidence: path:line, commit, failing test>)` — keep `TODO.md` uncommitted for it, and report the number and reason.
7. **Tick `[x]` in the commit that carries the change** and leave the item where it is until the list is cleared. A skipped or abandoned item goes back to `[ ]` with its one-line note.
8. **Plan against the architecture, then extend an established mechanism instead of adding a parallel one.** Read the matching runtime flow in `ARCHITECTURE.md` before the first line of code and name the existing types that own each part. A skill is a `Task` plus a tool through `FeatureToolContributor`; a restorable task carries a checkpoint decoder; a provider is a preset entry; persistence uses `OwnerFileStore`/`StoreCodec`; configuration uses `ConfigStores` and an adjacent migration. If the item seems to need a second queue, store, event log, tool registry or turn loop, the plan is wrong.
9. **Leave the result flexible, reliable and extensible.** Decisions live in pure policy or data, not in branches scattered through adapters. Work per tick is bounded, timeouts are in ticks, state can be held and resumed, failures are typed results, and persistence versions and protects what it writes.
10. **Better ideas are welcome, within bounds.** Improve the item you are doing when it lowers risk or deletes code, and say what changed and why. A new dependency, package restructure, schema redesign, cross-cutting refactor or rewrite is the maintainer's decision — propose it, do not smuggle it in. Never "fix" modules the item does not touch.

To review what closed items actually shipped across the whole codebase, or to explain a session from its logs, use the [`auditing-codebase`](.agents/skills/auditing-codebase/SKILL.md) and [`analyzing-logs`](.agents/skills/analyzing-logs/SKILL.md) skills. Both report and propose; neither changes code, `TODO.md` or configuration without the maintainer's approval.

## Commands

Java **25** is required (Gradle and Loom refuse to run on older JVMs). If your default `java` is older, point Gradle at a JDK 25 for the command:

```bash
JAVA_HOME="/path/to/jdk-25" ./gradlew build
```

| Command | What it does |
|---|---|
| `./gradlew build` | Compile, unit tests, Fabric GameTests, then package. Use `check --continue` for full local verification when packaging is already covered. |
| `./gradlew test` | Root and engine unit tests (JUnit 5, no Minecraft); root `test` aggregates `:engine:test`. |
| `./gradlew :engine:test --tests 'dev.friendai.<package>.*'` | Tests only the selected pure engine package. |
| `./gradlew test --tests '*SomeTest'` | One unit test class; root `test` also aggregates `:engine:test`. |
| `./gradlew compileGametestJava` | Compiles GameTest sources without booting Minecraft. |
| `./gradlew runClientGameTest` | Isolated client: YACL settings fields, transitions, save/cancel/reset, local HTTP previews, then layout in all eight shipped languages. Needs a display; no paid providers. |
| `./gradlew runGameTest` | Boots a server and runs the whole GameTest suite (several minutes). |
| `./gradlew runGameTest -Dfabric-api.gametest.filter='friendai-gametest:<class>_tests_*'` | One GameTest class (or one test id) in under a minute. The filter is a single glob; no negation, no list. |
| `./gradlew updatePromptGolden` | Rewrites `engine/src/test/resources/prompt-golden/`; review the diff as a prompt diff and commit the goldens. |
| `./gradlew check` | Unit tests + GameTests + `spotlessCheck`, without packaging. |
| `./gradlew spotlessApply -PspotlessFiles=<paths>` | Formats only the named root-relative files (prefix engine paths with `engine/`). A bare `spotlessApply` is forbidden in a shared checkout. |
| `./gradlew assemble verifyModArtifact` | Compiles every source set, packages, verifies nested engine classes/resources and fixture exclusion. Does not run tests. |
| `./gradlew exportTestJar` | Refreshes `test-builds/friendai-<version>.jar`; it does **not** reach a launcher profile's `mods/` folder. Runs after `assemble`. |
| `./gradlew deployTestJar` | The only task that copies the jar into the game's `mods/` folder (`TEST_BUILD_PATH` from the environment or a git-ignored root `.env`; skips silently when unset). Quote its `Deployed …` line. |
| `./gradlew verifyGametestShards` | Fails when `.github/gametest-shards.json` and the `fabric-gametest` entrypoints disagree. `CI gate` runs it. |
| `./gradlew runClient` / `runServer` | Dev client / server; `run/` is git-ignored and the server needs `run/eula.txt`. |
| `./gradlew genSources` | Decompiled Minecraft sources, for looking up API names. |

The first build downloads Minecraft and takes minutes; later builds take seconds. Details:

- Unit results: `build/test-results/test/*.xml` and `engine/build/test-results/test/*.xml` (one XML per class; sum `tests`/`failures`).
- GameTest results: console output — `========= N GAME TESTS COMPLETE` and `N required tests failed` (or `All N required tests passed`).
- Gradle may run `runGameTest` before `test`; if a GameTest fails, run `./gradlew test` separately for the unit count.
- Only one Gradle run per checkout at a time: parallel builds fight over the lock and the shared `run/` directory.
- **A jar the maintainer will load must land in the game's `mods/` folder, and only `deployTestJar` puts it there.** `exportTestJar` writes `test-builds/` and stops; run `deployTestJar` (or `assemble`, whose finalizers run both) whenever the maintainer is going to start the game.

## Project map

| Path | What it is |
|---|---|
| `engine/src/main/java/dev/friendai/` | Game-independent library: `foundation/`, `execution/`, `agent/`, `features/`, `infrastructure/`. No Minecraft, Fabric, optional UI or native audio imports. |
| `engine/src/test/`, `engine/src/testFixtures/` | Pure engine tests and reusable pure fakes/fixtures. |
| `src/main/java/dev/friendai/` | Common + server code, including Minecraft/server adapters. |
| `src/client/java/dev/friendai/client/` | Client-only code (Loom split source set). |
| `src/test/java/dev/friendai/` | JUnit 5 tests for root adapters; `testutil` holds root-only helpers. |
| `src/gametest/java/dev/friendai/test/`, `src/gametest/resources/` | Fabric GameTests (mod id `friendai-gametest`), their entrypoints and structures. |
| `src/main/resources/` | `fabric.mod.json`, `assets/friendai/lang/`, icon, GUI sprites. |
| `build.gradle`, `engine/build.gradle`, `gradle.properties` | Loom + engine setup, dependencies and all versions including `mod_version`. |
| `scripts/`, `.github/workflows/`, `.githooks/` | Docs/release/gametest scripts, CI, and the local `commit-msg` hook (enable with `git config core.hooksPath .githooks`). |
| `docs/superpowers/{specs,plans}/` | Local design specs and plans. **Git-ignored**; read for context, never commit. |
| `architecture/` | Tracked build, dependency and migration inventories and rules. |
| `test-builds/`, `run/`, `build/`, `logs/`, `.gradle/`, `config/` | Local artifacts; git-ignored. |
| `index.html`, `site/` | The capability map: `site/app.js` holds the data, `site/style.css` the look, `site/i18n.ru.js` the Russian text. Keep it current (see Definition of done). |
| `AGENTS.md`, `ARCHITECTURE.md`, `CONTRIBUTING.md`, `GLOSSARY.md` | Rules, reference, the human summary and the term list. |

**The rest of the map and every runtime flow live in `ARCHITECTURE.md`:** source sets, the **Where to start** table, the dependency rule, every package, one chat turn, the task lifecycle, the menu, configuration, speech in and out, persistence, the network protocol and access rules. Read it before changing a package you have not touched. Add or move a package either guide names, and update it in the same commit.

## Rules that keep it working

1. **Threads.** The world is touched only on the server thread. Server-side HTTP (the brain) runs on its own thread and hops back with `server.execute(...)` through `ServerBrainScheduler.execute`. Client speech stays on the client: recognition and synthesis run on worker threads and hop back with `Minecraft.getInstance().execute(...)`. A `CompletableFuture` callback never touches entities directly.
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
12. **Per-tick code stays cheap.** `Task.tick`, `ExecutionCoordinator` and the combat controller run every server tick: no allocations, registry lookups or pathfinding in the common path, bounded work per tick (as `PathSearch` does with its time budget), timeouts in ticks rather than wall-clock, and state that stays resumable so `hold()` can stop the task anywhere.

## When to stop and ask

Most of this guide exists so that an agent can act without interrupting the maintainer. These are the cases where acting first is the mistake. Ask in one short message: the decision, the realistic options, your recommendation, and what you will do if nobody answers (usually: stop and leave the tree unchanged).

| Situation | Why it cannot be decided alone |
|---|---|
| Rewriting history, force-pushing, deleting a branch or a worktree | Irreversible for every other session sharing this checkout. |
| Adding a runtime dependency or a build plugin, or bumping Minecraft, Fabric, Java or Loom beyond a patch | Changes every checkout and the shipped jar. |
| Changing a persisted schema or an owner/world record format so that existing saves stop loading | Players lose data; a migration is a product decision. |
| Renaming or removing a shipped tool, command, config key, translation key or network operation | Breaks saved plans, user configs or older clients. |
| Editing a file that holds another session's uncommitted work, or undoing anything you did not write | The other session cannot see that its work was taken. |
| Skipping a `TODO.md` item as harmful, or implementing a reduced version of it | The skip note and the report are the ask; nobody approved a different scope. |
| Merging a pull request, manually tagging or publishing a release, enabling auto-merge | Outward-facing and hard to undo. |
| Adding a second queue, store, registry, event log or turn loop beside the existing one | An architecture decision belongs to the maintainer. |
| Anything the permission layer, a sandbox or a hook blocked | A refusal is the answer. Never retry it another way. |

Everything else — a naming choice, a file layout inside your own package, which test to add — is yours to decide: choose, say what you chose in the report, and keep moving.

## Mixins

The mod ships one mixin set — `friendai.mixins.json`, package `dev.friendai.mixin`, `defaultRequire: 1` — so failures here are easy to cause and expensive to find:

- **A mixin is the last resort.** Prefer a Fabric event, a `Mob` goal, entity data or another vanilla hook. When vanilla offers no seam, as with hostile goals that ask for `Player`, the class javadoc says why, next to the code it patches.
- One file per target class, named `<Target>Mixin`; prefix every injected member with `friendai$`; never `@Overwrite` — inject or redirect and leave the vanilla path intact.
- `defaultRequire: 1` turns a renamed or removed vanilla method into a boot failure, not a compile error. A mixin change is verified by loading the game (GameTest boot or `runClient`), never by compilation alone, and the behavior it enables needs a GameTest.
- The authoritative list is `src/main/resources/friendai.mixins.json`; the sources live in `dev.friendai.mixin`. They cover three seams: mob targeting (`PiglinAiInvoker`, `NearestAttackableTargetGoalMixin`, `TargetGoalAccessor`), barter (`BehaviorUtilsBarterOutputMixin`, exact vanilla drop UUIDs) and advancements (`PlayerAdvancementsMixin`, feeding the happenings journal because Fabric has no advancement event).

## Language and localization

- **Everything in the repository is English**: code, comments, identifiers, logs, Markdown, commit messages, config defaults, the system prompt, tool descriptions and results, task summaries.
- **Player-facing text is always a translation key** rendered with `Component.translatable`. `src/main/resources/assets/friendai/lang/en_us.json` is the source of truth; every other file in that directory (`de_de`, `es_es`, `fr_fr`, `pt_br`, `ru_ru`, `uk_ua`, `zh_cn` today) must have exactly the same keys and the same `%s`/`%d` placeholders in the same order. `LangFilesTest` compares every file it finds there and fails on a key referenced in code that does not exist. Do not build keys by concatenating a literal that ends in `.`.
- Pure-Java layers return `UserMessage`; the Minecraft layer converts it with `Components.of(...)`.
- Not translated: LLM replies, anything addressed to the LLM, logs, command literals.
- To add a string, follow "Add a translation key" under Common recipes and run `./gradlew test --tests '*LangFilesTest'`.

## Formatting

Java layout is not a matter of taste: `build.gradle` wires [Spotless](https://github.com/diffplug/spotless) to [palantir-java-format](https://github.com/palantir/palantir-java-format) over `src/*/java/**/*.java`, both versions pinned in `gradle.properties`. Palantir is the formatter that matches this repository — 4-space indent and 120-column lines.

- **Format after your last edit and before the covering test run, never after it.** Run `./gradlew spotlessApply -PspotlessFiles=<comma-separated root-relative paths>` over the files you changed plus the files you created. If you edit them again, format again before the commit.
- **Paths are root-relative; prefix engine paths with `engine/`.** The root task delegates to both projects and each formats only its own selection. It rewrites files, sorts and prunes imports, trims trailing whitespace and ends each file with a newline. CI runs `spotlessCheck` and fails on any file that differs.
- **Never run a bare `./gradlew spotlessApply` in a shared checkout.** It rewrites the whole tree, including another session's uncommitted files. Pass your own files, or use `spotlessCheck -PspotlessFiles=…` to check without writing.
- The tool owns the layout: never hand-format around it, and do not reflow a file yourself to "fix" its diff. Wrap a block that must keep its own layout in `// spotless:off`/`// spotless:on` with a reason.
- A new source set is added to the `target` line in `build.gradle`. A formatter bump goes into `gradle.properties` together with the `style:` commit that applies it.

## Testing

The detailed GameTest mechanics — arenas, chunk loading, `TaskTrace`, shard isolation — are in the [`writing-gametests`](.agents/skills/writing-gametests/SKILL.md) skill. The rules below always apply.

### Test scope: fast feedback, full verification

- **During edits, start with the fastest check that can reproduce the problem or verify the change:** the area's root unit tests (`./gradlew test --tests 'dev.friendai.<package>.*'`), its engine equivalent (`./gradlew :engine:test --tests '…'`), or one filtered GameTest class when the game layer changed. Always run `LangFilesTest` when you touched a language file.
- **Before pushing broad game impact or fixes for recurring failures across classes, batch the fixes and run `./gradlew check --continue` locally.** Group tasks in one Gradle invocation; after later edits, rerun only what those edits affect.
- **Use CI's isolation when diagnosing a shard failure:** `scripts/run-gametest-shard.sh --class <class_id>`. An unfiltered local run shares a server across classes and can reveal different failures.
- **At merge to `main`** CI (`.github/workflows/ci.yml`) runs all unit tests and GameTests on every pull request, merge-queue entry and push. The required `CI gate` must pass; `Commit lint gate` checks commits and the PR title.
- **Report which groups or full suite ran, their actual results, and the status of CI.** Triage failures with evidence before widening the change.
- **A world change is exercised once before the commit.** Unit tests prove logic, not that a task runs: a change to a `Task`, `ExecutionCoordinator`, the entity, a builder or a menu is run once — a filtered GameTest class, or a real `runClient`/`runServer` session — and the report says what was run. Compiling is not running.

### Batch the loop: write everything, run once

1. Write the whole slice first: logic, tests that pin it, language keys, call sites, the doc line.
2. Run the covering group and read failures before editing again. Broaden verification after the fixes are batched.
3. Fix all of them in one pass, then re-run. Do not restart the loop after each individual fix.
4. Treat compilation as part of the batch: write against the type system, not the compiler's first error message.
5. Format as part of the batch: one `spotlessApply -PspotlessFiles=<your files>` before the single test run, never after it.
6. **One Gradle invocation per batch of edits, not per edit.** Finish the edits, then run one `compileJava` (`compileGametestJava` when you touched GameTest sources). Read every error, fix them in one pass, and rerun once.
7. **One filtered test invocation per batch.** Pass each affected package as another `--tests` flag instead of starting a second run; run the full `test` or `check` at most once, at the end.
8. **One GameTest boot per batch**, after the fixture code is complete; tuning one constant per boot turns a minute of work into an hour. A full engine pass takes under a minute — the cost is the number of invocations, not a single run.

**Keep the tests honest while batching.**

- **A test that passes on the first run has proven nothing.** Write it against the current behavior when you can; otherwise change the one guarded line, watch the red, then restore it.
- **A failure that is not the one you are testing hides the truth.** A missing floor, an unregistered GameTest class, a throwing fixture — each makes a green run meaningless.
- **TDD, batched.** Write the test before the implementation, but do not run it alone after each edit. Reusable pure fakes live in `engine/src/testFixtures/java/dev/friendai/testutil`; root-only helpers stay in `src/test/java/dev/friendai/testutil`. No mocking frameworks.
- **What a test must prove:** behavior, boundaries, state transitions, precedence, error paths — nothing else. Do not pin translation wording, assert that a helper was called, echo a fake's own input, or re-pin incidental defaults. A test that only proves the code still says what it says is deleted with the change it guards.
- **Reusable fakes:** `MockHttpServer`, `FakeGateway`, `FakeToolExecutor`, `ManualScheduler`, `GridCellView`, `RecordingSpeaker`.

### GameTest rules

- **GameTests share one world and run in parallel.** Never rely on global state; give each test its own block type and filter counts by type. Use `GameTestSupport.platform(...)`/`mockPlayerAt(...)`/`enclose(...)`, load the chunks a moving entity needs, and drive an `ExecutionCoordinator` yourself in `h.succeedWhen(() -> { coordinator.tick(); ... })`.
- **Scope server-wide changes to a dedicated test environment** under `src/gametest/resources/data/friendai-gametest/test_environment/`; assert on the test's own entity, owner or chunk ticket.
- **Drive hanging tasks through `TaskTrace`**, not bare `succeedWhen`, and never add `System.out.println` probes to product code.
- **Register a new GameTest class** in the `fabric-gametest` entrypoints and in exactly one shard of `.github/gametest-shards.json`; `verifyGametestShards` fails a miss. Batch boots and filter flaky tests.
- `CompanionSessions.setGatewayOverride`/`setSpeakerWrapper` script the LLM without the network. `MANUAL_CHECKLIST.md` lists what automation cannot cover; do not tick an item you did not perform.

### CI checks and red runs

- **The required checks are `CI gate` and `Commit lint gate`.** `CI gate` runs the unit suites, GameTest shards, `spotlessCheck`, packaging, `verifyGametestShards` and `Docs links`. Nothing merges on a red or pending gate.
- **The guides are checked too.** `Docs links` runs `scripts/check-docs.sh`; a broken relative link, a bad `#anchor` or a wrong table cell count fails it. `Workflow lint` runs `shellcheck` over `scripts/*.sh`.
- **A red GameTest shard names its class.** Reproduce it with that class's glob described above; read the uploaded `build/gametest-shards/<class>.log` before changing code.
- **Nightly and unfiltered local runs cover cross-class interactions.** Inspect shared-world state and timing when a failure appears only there.
- **Triage before rerunning.** Fix what is yours and report what is not with evidence; never merge around a red gate.

## Minecraft API notes (26.3)

26.x is unobfuscated: **Mojang names**, no Yarn. Names differ from older versions you may remember, so look them up instead of guessing:

- Decompiled sources: after `./gradlew genSources`, find `*-sources.jar` under `.gradle/loom-cache/minecraftMaven/net/minecraft/` (`minecraft-common-*` and `minecraft-clientOnly-*`). Extract them outside the repo and `grep`. Without sources, `javap -p -c -cp <minecraft.jar> <class>` still works because the jar keeps its names.
- Fabric API / YACL / Mod Menu jars live in the Gradle cache; inspect with `javap -cp <jar> <class>`.
- Names removed or moved in 26.x: `SavedData`/`SavedDataType` are in `net.minecraft.world.level.saveddata`; `level.getSharedSpawnPos()` → `level.getRespawnData().pos()`; `dimensionType().bedWorks()` → `level.environmentAttributes().getDimensionValue(EnvironmentAttributes.BED_RULE)` (`BedRule`); `level.getDayTime()` is gone (use `level.isDarkOutside()` or the world clock via `level.getDefaultClockTime()`).
- Things that tripped us before: `Identifier` (not `ResourceLocation`); entity save/load uses `ValueOutput`/`ValueInput`; `Entity.snapTo`; the current screen is `minecraft.gui.screen()`, toasts are `minecraft.gui.toastManager()`, open a screen with `setScreenAndShow`; `Mob.swing(hand, SwingAnimation.DEFAULT)`; `EntityRenderers.register`; HUD via `HudElementRegistry`; keybinds via `KeyMappingHelper` and `KeyMapping.Category.register(Identifier)`; command permissions via `Commands.hasPermission(Commands.LEVEL_GAMEMASTERS)`.
- Item behavior lives in data components: `DataComponents.TOOL` (mining rules and speeds), `ATTRIBUTE_MODIFIERS` (damage/speed), `EQUIPPABLE`, `WEAPON`, `FOOD`. Do not hard-code tool tiers; ask the stack.
- YACL and Mod Menu are **optional** dependencies. Code that touches them lives in `client/config` and must only be loaded when `yet_another_config_lib_v3` is present.

## Common recipes

The step-by-step versions are in `CONTRIBUTING.md`; these are the invariants.

- **Add a skill:** task adapter in `src/main/java/dev/friendai/features/<area>/minecraft/task/`, pure policy in `engine/.../features/<area>/`; register the tool in the area's `*Tools` contributor via `TaskToolDefinition`/`FeatureToolRegistry`; if restorable, contribute a checkpoint decoder with `.withCheckpoint(...)`; add language entries and a GameTest; bump `mod_version`.
- **Add a config option:** field and default in engine `ServerConfig`/`ClientConfig` with validation; a persisted-shape change bumps the schema and adds an explicit adjacent `ConfigStores` transition with tests; expose it in `client/config`; add label/tooltip keys to every locale.
- **Add an LLM provider or protocol:** pure wire logic in `engine/.../agent/llm/protocol/`, presets and routing in `agent/llm/provider/`, registered in `ProviderRegistry.withBuiltins`; keep key redaction and endpoint precedence; update the README provider table when user-visible.
- **Add a command:** literal and permissions in `command/FriendaiCommands`; return a `UserMessage`/`Component` key; translate it; add a GameTest when it touches the world or permissions.
- **Add a translation key:** `en_us.json` first, then every shipped locale with identical placeholders; reference it with `Component.translatable` or `Components.of(new UserMessage(...))`; run `LangFilesTest`.
- **Add a client payload:** wire values and codecs in engine `protocol/`, Fabric wrappers in `transport/payload/`, registered in `FriendaiNetworking`; gate work through `ServerProtocolConnections`; hop to the correct thread; check `canSend`; enforce access and redaction on the server.
- **Change persistence:** start from `OwnerFileStore`/`StoreCodec`; bound and version the codec; older and future formats stay untouched; keep atomic replace, backup recovery and protection from overwrite; add round-trip/unsupported/failure tests; update the persistence table in `ARCHITECTURE.md`.
- **Upgrade Minecraft, Fabric or Java:** change versions only in `gradle.properties` and keep `fabric.mod.json` ranges in step; run `genSources` and re-check every moved name; boot the game for every mixin-affected area; run the covering tests; treat it as breaking (a MINOR bump while `0.x`).

## Failure modes we have already seen

- **Guessed a Minecraft or Fabric name.** Look it up with `javap` or the decompiled sources; never accept a variant that happens to compile.
- **Trusted the compiler for a mixin.** `defaultRequire: 1` fails at boot; load the game.
- **Repeated full runs for a known failure.** Reproduce with the smallest group and batch the fixes.
- **Built a second mechanism.** A parallel queue, store, registry or turn loop beside the existing one.
- **Staged everything.** `git add -A` swept another session's files into a commit; name your paths.
- **Let a test pass on the first run.** Break the guarded line once and watch the red.
- **Left debug output behind.** A `System.out.println` in product code, a commented-out block, an unused field.
- **Edited the guide to match the shortcut.** The rule bent to the code instead of the code to the rule.
- **Added an attribution line.** `Co-Authored-By` and generated-with footers are forbidden here.
- **Treated a foreign error as yours.** A file you never touched fails because another session is mid-edit; check `git status`, report it, do not patch it.
- **Committed local state.** `docs/`, `run/`, `test-builds/`, `build/` or a downloaded model.
- **Reported a test jar the game could not see.** `exportTestJar` writes `test-builds/` only; `deployTestJar` is what reaches `mods/`.
- **Closed a TODO item quietly.** A skipped item needs its one-line `**Skipped:**` note and a report.

## Debugging with logs

Every world owns `<world>/friendai/logs/` with `server/<date>/boot-<boot>.jsonl` and `owners/<uuid>/<date>/session-*.jsonl`; the client tree and world index stay in `<gameDir>/logs/friendai/`. Records are JSONL; follow `cause` back to the turn or task that started a failure. For a structured pass over these files and the launcher logs, follow the [`analyzing-logs`](.agents/skills/analyzing-logs/SKILL.md) skill.

- `/friendai logs path` prints the exact world folder; `/friendai logs level …`, `logs prune` and `logs export` apply live.
- `normal` records metadata only and is the dedicated-server default; `detailed` adds prompt/reply/tool bodies after redaction. Do not raise the level on a server carrying other people's chat without their knowledge.
- **Never paste a bundle or a `detailed` file into an issue unread** — it can contain coordinates, inventory facts and prompt/reply text.
- The full path table, retention bounds, event table and `jq`/PowerShell recipes are in `README.md`, "Logs".

## Troubleshooting

| Symptom | Likely cause / fix |
|---|---|
| `Dependency requires at least JVM runtime version 25` | Gradle is on an older JDK. Set `JAVA_HOME` to a JDK 25. |
| Windows file lock or Gradle lock timeout | Another session is running `runGameTest`/`runClient` or a build. Wait; never delete lock files or kill Java processes. |
| Compile error in a file you did not touch | Another session's work in progress. Check `git status`/`git diff` and report it. |
| GameTest fails only in the full run | Shared world or timing. Re-run that test filtered; note it as flaky rather than changing unrelated code. |
| A mob freezes, a fight never lands a second hit, a companion stops arriving | The entity left the force-loaded chunks; use `GameTestSupport.platform`/`platformAt`/`enclose`. |
| `LangFilesTest` fails | Missing key, placeholder mismatch, or a key built by concatenation. |
| `spotlessCheck` fails on files you did not touch | Another session's uncommitted work. Format and commit only your own files. |
| A Minecraft/Fabric name does not compile | Look it up in decompiled sources or `javap`; do not guess. |
| A GameTest class does not run | It is missing from `fabric-gametest` entrypoints or from a shard. |
| The settings screen is missing | YACL is not installed; the mod still works through the config files. |

## Working with subagents

**Delegating is the default for work that splits into independent parts.** Split by ownership before the first child starts: each child owns a file set, module or review dimension, and two children never write the same file. Fix the shared interface yourself, launch all independent children in one batch, read their diffs, resolve conflicts and run the covering build/test once in the main session. The full brief template, isolation rules and adapter notes are in the [`delegating-work`](.agents/skills/delegating-work/SKILL.md) skill.

- **You are the orchestrator.** Split the work, write self-contained briefs, fix the shared contracts, integrate and own final verification. A child does not see this conversation.
- **Read-only work runs beside everything.** Searches, call-path tracing, log reading and independent review passes change nothing and may overlap with writers.
- **Do not delegate dependent steps, tiny tasks or design decisions.** If describing the piece takes longer than doing it, do it yourself.
- **Children run on a cheap model.** Never hand a child an expensive model; if a piece seems to need one, split it smaller or do it yourself. Say which model a child ran on when cost matters.
- **Subagent reports are claims, not proof.** Read the diff yourself and run `./gradlew assemble verifyModArtifact` plus the covering test group.
- **Only one agent runs Gradle in a checkout at a time.** Give writers their own worktree, or run the build yourself after integrating.

## Parallel sessions

The maintainer often runs several agent sessions on this repository at once, usually in the same checkout. Files can change under you at any time, and a dirty `git status` is normal.

- **Assume other changes are someone else's work.** Do not revert, delete, stash, reformat or "clean up" uncommitted modifications, untracked files or directories you did not create.
- **Never run `git checkout -- <path>`, `git restore`, `git reset --hard`, `git stash` or `git clean`** to get a tidy tree. Work around a blocking file or report it.
- **Re-read before you edit.** Make small, targeted edits instead of rewriting whole files, and re-check the file after a failed edit.
- **Stay in your lane.** Touch only the files your task needs; mention problems elsewhere in the final message.
- **Commit only your own files**, naming them explicitly (`git add <path>...`). Never `git add -A` or `git commit -a`. A file that mixes your edits with someone else's stays uncommitted and is named in the report.
- **Failures outside your change may not be yours.** Check `git status`/`git diff` before patching; if it is not yours, report it and re-run later.
- **One Gradle run at a time per checkout.** Wait and retry on a lock; never delete locks or kill Java processes.
- **Claim your `TODO.md` item first** with `[~]`, uncommitted; it lands with the task.
- **Prefer a worktree** for work that edits many files or runs long builds. A worktree has its own lock and `run/` directory.

**Worktree recipe for a parallel writer:**

```bash
git worktree add ../Friendai-<task> -b <type>/<slug> main
cd ../Friendai-<task>
JAVA_HOME="<jdk-25>" ./gradlew test --tests 'dev.friendai.<package>.*'
```

Commit and push from the worktree, then `git worktree remove ../Friendai-<task>` after it merges. `git worktree list` shows which worktree owns which branch; never remove or rewrite another session's worktree.

## Git and repository hygiene

- **Commit every finished task before you report it as done.** Do not leave a completed task uncommitted waiting for a later request, and do not say the work is done while its files sit in the working tree: stage only your own paths (`git add <path>...`), make one coherent conventional commit, and say where it landed. Never `git add -A`, and leave a file uncommitted when it mixes your edits with someone else's. The two exceptions are a dictated `TODO.md` item that waits for its batch and a claim marker that waits for the task it marks.
- **Large tasks commit in batches.** Commit after each finished subtask or plan step instead of one huge commit; bump the version once per task (see "Versioning").
- Commit messages: conventional style (`feat:`, `fix:`, `chore:`, `docs:`, optional scope), imperative, English. **No `Co-Authored-By` or other AI attribution lines.** Header ≤ 100 characters, no trailing full stop; the `commit-msg` hook and `commitlint.config.mjs` enforce this.
- `docs/` (design specs and plans) is **git-ignored on purpose** — never commit it.
- Never commit generated or local files: `run/`, `logs/`, `build/`, `.gradle/`, game configs, worlds, downloaded models. `.gitignore` entries stay anchored (`/config/`, not `config/`) so they cannot swallow source packages such as `src/**/config/`.
- Do not rewrite history, force-push or delete branches without being asked.
- Config files at runtime: `config/friendai-server.json`, `config/friendai-client.json`; whisper models in `<gameDir>/friendai/models/`.

### Self-review before you commit

- Read your own diff (`git diff` and `git diff --cached`) file by file, as if reviewing someone else's change.
- Remove debug prints, commented-out code, stray `TODO`/`FIXME`, unused imports and fields.
- Re-check rules 1–12 where the change touches them: server-thread access, layer imports, key handling, synced client data and per-tick cost.
- Read every new user-facing string in every language and every new log line for secrets.
- Confirm the index holds only your files (`git status`); never stage another session's work.

## Branches and pull requests

- **One branch per task, cut from an up-to-date `main`.** Name it `<type>/<slug>`, using the conventional commit type and, for a `TODO.md` item, its number: `feat/37-retry-last`. Do not mix unrelated work.
- **Code and resource changes land through a pull request.** Push the branch and open a PR with a conventional title. Its body states what changed, why, the tests you ran with their real result, the version and the test-jar path, and what you could not verify.
- **A red check is triaged, not bypassed.** Fix it or report it as unrelated with evidence; never merge on a red or pending `CI gate`, and never enable auto-merge on your own.
- **Do not merge a pull request unless the maintainer asked for that specific merge.** Do not force-push a branch with an open PR; add commits instead.
- **A docs-only or one-line chore may go straight to `main` when the maintainer asks.** Anything that changes the shipped jar takes the branch path.
- Delete a merged branch only when asked; never delete another session's branch.

## Versioning

The single source of truth is `mod_version` in `gradle.properties` ([Semantic Versioning](https://semver.org/)). The jar name, `fabric.mod.json` and release tags derive from it; a release tag `vX.Y.Z` must equal it.

| Change | Bump | Examples |
|---|---|---|
| Bug fix, small tweak, translation fix, dependency patch | **PATCH** (`0.1.0` → `0.1.1`) | `fix:` commits, `perf:` with no API change |
| New feature or skill, new setting, new provider preset, user-visible improvement | **MINOR** (`0.1.1` → `0.2.0`), PATCH resets to 0 | `feat:` commits |
| Breaking change: config format change that needs a migration, removed or renamed command/tool/setting, save-data incompatibility, Minecraft or Java version bump | **MINOR** while `0.x`; **MAJOR** after 1.0 | `feat!:` / `BREAKING CHANGE:` commits |
| No effect on the shipped jar | **no bump** | `docs`, `ci`, `test`, `style`, pure `chore`/`build`/`refactor` |

- While the major version is `0`, breaking changes bump MINOR instead of MAJOR. Moving to `1.0.0` is the maintainer's decision.
- One bump per task/PR, sized by the largest change in it. Put it in the same commit as the change, or in a final `chore: bump version to X.Y.Z` commit.
- Changing a persisted schema: bump its format version and the mod version, add the tested `ConfigStores` transition and a backup. Unsupported config versions and older owner/world record formats stay untouched.
- A version bump merged to `main` authorizes the CI release for that version. Agents need separate maintainer authorization for manual tags or releases.
- When unsure between two sizes, pick the larger one and say why.

**Release checklist (maintainer).** Merge the `mod_version` bump and a nonempty `CHANGELOG.md` section for that version into `main` together. After `CI gate` passes, `.github/workflows/release.yml` verifies the jar against `mod_version`, pins `vX.Y.Z` to the tested commit, and publishes the jar, its SHA-256 sidecar and the version's notes. A conflicting tag stops the run. Retry a transient failure from its original CI run. For a local artifact check, run `scripts/check-release.sh vX.Y.Z` after `./gradlew assemble`.

## Changelog

`CHANGELOG.md` is the player-facing history of the shipped mod, newest version first, in [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) form.

- **An entry lands with the change** under `## [Unreleased]`, or under the current version heading once its bump has landed. Internal-only changes (`docs`, `ci`, `test`, `style`, pure `chore`/`build`/`refactor`) get no line.
- **Map the commit type to a category:** `feat` → Added; changed behavior/setting/command/tool → Changed; withdrawn → Removed/Deprecated; `fix` → Fixed; `perf` and user-visible improvements → Changed; data-safety fix → Security. Add a category only when it has a line.
- **Write for a player.** One line says what the owner can now do or which bug no longer happens; name tools/commands/settings by their player-facing name. No class names, paths, test names, hashes or "refactor"/"cleanup"/"bump".
- **One block per version.** When the bump lands, retitle the `Unreleased` block `## [X.Y.Z] - YYYY-MM-DD` and put a fresh `Unreleased` above it.
- **A tagged version is history.** Never rewrite, reorder or delete a tagged section; correct a plain factual error only. Do not invent history: the file starts at 0.87.0.

## Definition of done

1. **Code must compile before it is committed.** Tests are optional for a commit, compilation is not: run `./gradlew assemble verifyModArtifact` with `JAVA_HOME` on JDK 25 and read its result. A docs-only change needs neither the build nor the tests. Formatting is part of this gate.
2. Commit coherent changes after verification at the scope in "Testing"; report actual unit-test counts and GameTest results.
3. New player-facing strings exist in every shipped language file.
4. New behavior has a test that pins it and would fail without the change; bugs get a regression test.
5. Layer boundaries are respected (no Minecraft imports in the pure layers).
6. `mod_version` is bumped according to "Versioning", or the report states why no bump is needed.
7. Anything you could not verify (real provider, real microphone, GUI) is stated plainly.
8. **The task is committed.** Your changed files are in git before you report the work as done. A file that also holds another session's edits stays uncommitted, and you say so. For a multi-step task, the commits follow the batches you finished.
9. `test-builds/friendai-<version>.jar` is refreshed, and you give its path and version. When the maintainer will load the build, it is also deployed with `deployTestJar`/`assemble` and the report quotes the deployed path.
10. **The capability map stays current.** When a change adds, renames, removes or materially changes something `index.html` shows, update `site/app.js` in the same commit, matching the surrounding entries: tools/skills in `CATS` (`name|description`), the turn flow (`FLOW`), stored data (`MEM_CARDS`), layers/packages/rules (`ARCH_CARDS`, `ARCH_FLOW`). Add the Russian text to `site/i18n.ru.js` (keys are the exact English strings). Open the page once and check that the affected graph renders without overlaps. If nothing it shows changed, no edit is needed.
11. **The changelog line is in.** A change that alters the shipped mod has its `CHANGELOG.md` line under `Unreleased` or the new version heading, in the same commit as the version bump.

**The task report.** Close every task with this report, in this order, each line carrying only what a reviewer needs:

```text
Changed: <files, with path:line where it helps>
Tests: <command> — <N passed / N failed>; GameTest <class glob> — <"N GAME TESTS COMPLETE" line>
        Full local suite: <result or scope reason>; CI gate: <status>
Version: <old> -> <new>, <why>; test jar: test-builds/friendai-<version>.jar
Not verified: <real provider, microphone, GUI, LAN — or "nothing">
Foreign changes noticed: <files another session left uncommitted — or "none">
```

A docs-only or `ci:` change says so instead of inventing a version and a jar line.

### What to update with a change

| Change | Update in the same commit |
|---|---|
| A package, class, flow or persistence record | `ARCHITECTURE.md` — its package map, runtime flow or persistence table |
| A tool, skill, turn-flow step, stored data or layer rule | `site/app.js`, plus `site/i18n.ru.js` for every new or changed string |
| Player-facing text | `en_us.json`, and every file in `assets/friendai/lang/` mirrors it |
| Shipped behavior, feature or fix | `CHANGELOG.md` under `Unreleased` |
| A provider, config key, command or player-visible limit | `README.md` when user-visible |
| A new GameTest class | `src/gametest/resources/fabric.mod.json` and one shard in `.github/gametest-shards.json` |
| A config schema or owner/world format | its version, a `ConfigStores` transition or codec change, tests, and the persistence table in `ARCHITECTURE.md` |
| Behavior automation cannot check | a line in `MANUAL_CHECKLIST.md` saying what to try and what to expect |
| Any change to the shipped jar | refresh `test-builds/friendai-<version>.jar`, and `deployTestJar` when the maintainer will launch the game |
| A rule or workflow in `AGENTS.md` | `CONTRIBUTING.md` only where it repeats the old rule — it must not gain a rule the canonical file does not have |

If a row does not apply, nothing is needed; do not add a line that only says the code changed.

## Glossary

The terms — companion, owner/trusted/guest, brain, turn, goal, shift, skill, instruction skill, description, tool, task, reflex, hold, checkpoint/restore, plan, blueprint, station, perception, snapshot, owner store, provider/preset/route/protocol — are defined in [`GLOSSARY.md`](GLOSSARY.md).
