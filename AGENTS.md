# AGENTS.md

Guidance for AI coding agents (and humans) working on **Friendai**: a Fabric mod for Minecraft **26.3** that adds an AI companion you talk to by chat or voice. Mod id `friedai`, root package `dev.friedai`, MIT licensed.

## Commands

Java **25** is required (Gradle and Loom refuse to run on older JVMs). If your default `java` is older, point Gradle at a JDK 25 for the command:

```bash
JAVA_HOME="/path/to/jdk-25" ./gradlew build
```

| Command | What it does |
|---|---|
| `./gradlew build` | Compile, run unit tests **and** the Fabric GameTests (`runGameTest` is part of `check`). The full gate — run it before declaring work done. |
| `./gradlew test` | Unit tests only (JUnit 5, no Minecraft). Fast. |
| `./gradlew test --tests '*SomeTest'` | One test class. |
| `./gradlew runGameTest` | In-game tests only. |
| `./gradlew runClient` / `runServer` | Dev client / server. `run/` is git-ignored; the server needs `run/eula.txt` with `eula=true`. |
| `./gradlew genSources` | Decompiled Minecraft sources, for looking up API names (see "Minecraft API notes"). |

The first build downloads Minecraft and takes minutes; later builds take seconds. Test results are in `build/test-results/test/*.xml`; GameTest results are in the console output (`All N required tests passed`).

## Architecture

Layers, bottom-up. **The first group must not import `net.minecraft.*` or `net.fabricmc.*`** — they are plain Java, unit-tested without the game, and could be extracted as a library.

Pure Java:

- `llm/` — `model/` (Message, ToolCall, LlmRequest/Response, LlmException), `protocol/` (`openai_chat`, `openai_responses`, `anthropic_messages`, `gemini`, `ollama`; each has a `toWire`/`fromWire` pair), `provider/` (presets and routing as **data**), `http/HttpTransport` (errors, retries), `LlmClient`.
- `config/` — `ConfigStore` (atomic save, `.bak` on corruption, versioned migrations), `ServerConfig`, `ClientConfig`, `SecretResolver` (`env:NAME` keys).
- `text/` — `UserMessage(key, args)` and `UserFacingException`: how pure layers talk to the player.
- `tool/` (except `tool.builtin`) — `Tool`, `ToolRegistry`, `ArgValidator`, `SchemaBuilder`.
- `brain/` — `CompanionBrain` (the agent loop), `ConversationMemory`, `PromptBuilder`, events. It sees the world only through `PerceptionSource` and acts only through `ToolExecutor`.
- `chat/AddressDetector`, `chat/ReplySplitter`, `voice/` (`PcmConverter`, `WhisperModelManager`), `net/RateLimiter`.
- `debug/` — the companion menu's model: `DebugLog` (bounded brain log), `TaskHistory`, `SecretRedactor`, `DebugAccess` (who may do what), `CompanionSnapshot` + `SnapshotCodec` (what travels to the client), `RequestType`, `PauseRowPlacement`. `brain/BrainObserver` is the hook `CompanionBrain` reports to; `DebugLogObserver` writes it into the log.

Minecraft layer:

- `entity/` — `CompanionEntity` (a `PathfinderMob` with a 27-slot inventory, never despawns), `CompanionManager` (one per owner).
- `task/` — `Task`, `TaskRunner` (one task at a time, timeouts, crash isolation, falls back to Follow), `task/builtin/` (Follow, Stay, GoTo, PickupItems, GiveItems, MineBlocks), `task/mining/`, `task/nav/MoveHelper`.
- `tool/builtin/FriedaiTools` — the 9 skills as tools. `perception/` — surroundings snapshot for the prompt.
- `core/` — `BrainService` (one brain per player, shared `LlmClient`), `FriedaiServerState` (per-server config and brains), `ServerBrainScheduler`, `Components` (`UserMessage` → `Component`).
- `chat/ChatRouter`, `chat/CompanionSpeaker`, `command/FriedaiCommands`, `net/` (voice transcript packet; companion menu: `CompanionRequestC2SPayload` handled by `CompanionRequestHandler`, snapshots built by `SnapshotBuilder` and pushed by `DebugStream`; debug tabs are operator-only, checked on the server).

Client (`src/client`): renderer, companion menu (`client/ui`: pause-menu button, `CompanionControlScreen` and its tabs), YACL settings screen (`client/config`), voice (`client/voice`: mic capture, push-to-talk, HUD, whisper-jni), `client/tts` (interface only).

Source sets: `main`, `client` (Loom split environments), `test` (JUnit), `gametest` (Fabric GameTest, mod id `friedai-gametest`).

### Rules that keep it working

1. **Threads.** The world is touched only on the server thread. HTTP and speech recognition run on their own threads and hop back with `server.execute(...)` (`BrainScheduler.execute`). A `CompletableFuture` callback never touches entities directly.
2. **A skill = a `Tool` + usually a `Task`.** Tools describe the skill to the LLM and validate arguments; tasks do the work over time. Add a skill by writing the task, registering the tool in `FriedaiTools.create`, and adding a GameTest. The core does not change.
3. **Several tool calls in one response** must run in order, and Anthropic/Gemini must receive all the results in **one** user message (otherwise HTTP 400). Covered by tests; keep them green.
4. **History trimming** must never leave a tool result or a call without results at the head, and must never drop the turn in progress.
5. **A late LLM response** after `stop`/dismiss/owner logout must be dropped silently (generation counter in `CompanionBrain`).
6. **Malformed tool arguments** from the model must become an error `tool_result`, never an exception.
7. **API keys** never appear in logs, chat, exception messages or `toString()`. Keys go in headers, never URLs.

## Language and localization

- **Everything in the repository is English**: code, comments, identifiers, logs, Markdown, commit messages, config defaults, the system prompt, tool descriptions and results, task summaries.
- **Player-facing text is always a translation key** rendered with `Component.translatable`. `src/main/resources/assets/friedai/lang/en_us.json` is the source of truth; `ru_ru.json` must have exactly the same keys and `%s` placeholders. `LangFilesTest` enforces this and also fails if code references a key (a string literal like `"friedai.something"`) that does not exist. Do not build keys by concatenating a literal that ends in `.` — the test will flag it.
- Pure-Java layers return `UserMessage`; the Minecraft layer converts it with `Components.of(...)`.
- Not translated: LLM replies, anything addressed to the LLM, logs, command literals.

## Testing

- **TDD.** Write the failing test first and watch it fail for the right reason. Unit tests use `MockHttpServer` (`src/test/.../testutil`) plus the fakes there (`FakeGateway`, `ManualScheduler`, …) — no mocking frameworks.
- **GameTests share one world and run in parallel.** So: never rely on global state; give each test its own block type (`MineBlocksTests` uses a different log per test) and filter item/entity counts by type; build a floor with `GameTestSupport.platform(h, size)`; use `GameTestSupport.mockPlayerAt(...)` because mock players log in at the world origin, in an unloaded area. Drive a `TaskRunner` yourself inside `h.succeedWhen(() -> { runner.tick(); ... })` for deterministic ticking, and set `@GameTest(maxTicks = ...)` for slow tests.
- Per-owner test hooks exist on `BrainService` (`setGatewayOverride`, `setSpeakerWrapper`) so tests can script the LLM without touching the network.
- `MANUAL_CHECKLIST.md` lists what automation cannot cover (real providers, voice, LAN, both UI languages). Do not tick an item you did not perform.

## Minecraft API notes (26.3)

26.x is unobfuscated: **Mojang names**, no Yarn. Names differ from older versions you may remember, so look them up instead of guessing:

- Decompiled sources: after `./gradlew genSources`, find `*-sources.jar` under `.gradle/loom-cache/minecraftMaven/net/minecraft/` (`minecraft-common-*` and `minecraft-clientOnly-*`). Extract them somewhere outside the repo and `grep`.
- Fabric API / YACL / Mod Menu jars live in the Gradle cache; inspect with `javap -cp <jar> <class>`.
- Things that tripped us before: `Identifier` (not `ResourceLocation`); entity save/load uses `ValueOutput`/`ValueInput`; `Entity.snapTo`; the current screen is `minecraft.gui.screen()`, toasts are `minecraft.gui.toastManager()`, open a screen with `setScreenAndShow`; `Mob.swing(hand, SwingAnimation.DEFAULT)`; `EntityRenderers.register`; HUD via `HudElementRegistry`; keybinds via `KeyMappingHelper` and `KeyMapping.Category.register(Identifier)`; command permissions via `Commands.hasPermission(Commands.LEVEL_GAMEMASTERS)`.
- YACL and Mod Menu are **optional** dependencies. Code that touches them lives in `client/config` and must only be loaded when `yet_another_config_lib_v3` is present.

## Working with subagents

Use the delegation tool your environment provides (`task`, `Task`/`Agent`, `subagent`, `@mention`, "spawn agents", etc.). The rules below are tool-independent; the adapter notes at the end only name the tools.

**You are the orchestrator.** Split the work, write the briefs, integrate the results, and own the final verification. Subagents do bounded pieces.

### When to delegate

- The task splits into 2+ independent parts (separate modules or files, parallel searches, comparing several options, writing tests, reviewing). Launch them **in parallel in one step**, not one after another, if the tool allows it.
- Heavy reading goes to a subagent: wide code searches, tracing a call path through many files, reading logs or decompiled Minecraft sources. Ask for a short summary with `path:line` references so your own context stays small.
- Review changes with parallel reviewer subagents (e.g. one for thread/layer rules in the Architecture section, one for tests and localization), when the tool supports it.

### When NOT to delegate

- Steps depend on each other's output, the task is small (a few tool calls), or the decision needs one continuous context. Delegating then only adds tokens and latency.
- If your environment has no subagents, do not invent them. Do the work yourself, but still split it into independent steps and read only what you need.

### Writing a brief

A subagent does not see this conversation. Make each brief self-contained:

- **Goal** and why it matters, in one or two sentences.
- **Paths** to read and, for writers, the exact files it may change.
- **Constraints**: the relevant rules from this file (layer boundaries, threading, English only, no commits), and what is out of scope.
- **Result format**: e.g. "reply with a summary under 200 words plus `path:line` list".
- **Done when**: a checkable criterion (a test passes, a symbol is found, a list is complete).

### Isolation

- Subagents that edit code must own disjoint files. Fix the split before launching. If two parts must touch the same file, run them one after another.
- Prefer a separate branch or worktree for parallel writers if the tool offers it.
- Only one agent should run `./gradlew` in a given checkout at a time: parallel builds fight over the Gradle lock and the shared run directory. Give builders their own worktree, or run the build yourself after integrating.

### After they finish

- Subagent reports are claims, not proof. Read the diff, then run `./gradlew build` and quote the real result before saying the work is done (see "Definition of done").
- Resolve conflicting edits yourself. Do not ask a subagent to settle a conflict between subagents.

### Examples

- Research. Before: read `PromptBuilder`, `CompanionBrain` and `ToolRegistry` one by one in the main session. After: three read-only subagents, one per area, each returns the key types and call sites in a few lines.
- New skill. Before: write the task, then the tool, then the GameTest, then both lang files in sequence. After: after you fix the interface, run the task + tool, the GameTest, and the `en_us.json`/`ru_ru.json` entries in parallel on disjoint files; then integrate and build.
- Review. Before: one pass over the whole diff. After: parallel reviewers for layer/thread rules, test coverage, and localization; you merge the findings.

### Adapter notes

- **oh-my-pi (`omp`)**: tool `task` (parallel, optionally worktree-isolated); `wait` collects background results; `/review` spawns reviewers. Custom agents: `.omp/agents/*.md` (project) or `~/.omp/agent/agents/*.md`; `.claude/agents` is not read. Bundled: `scout` (read-only), `reviewer`, `task`.
- **Claude Code**: tool `Agent` (formerly `Task`). Custom agents: `.claude/agents/*.md` with YAML frontmatter (`name`, `description`, `tools`, `model`; `isolation: worktree` for an isolated checkout). Default cap is 20 concurrent subagents.
- **Codex CLI**: enabled by default; ask for it explicitly ("spawn agents for X, Y, Z in parallel"). Custom agents: TOML in `.codex/agents/` (`name`, `description`, `developer_instructions`). Cap: `agents.max_concurrent_threads_per_session`. Prefer parallelism for read-heavy work; be careful with concurrent writes.
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
- **Prefer a worktree** for work that edits many files or runs long builds, so you do not collide with other sessions. Do not delete or rewrite other sessions' worktrees or branches.
- In the final message, list the files you changed and mention any foreign changes you noticed, so the maintainer can reconcile them.

## Git and repository hygiene

- Commit messages: conventional style (`feat:`, `fix:`, `chore:`, `docs:`, optional scope), imperative, English. **No `Co-Authored-By` or other AI attribution lines.**
- `docs/` (design specs and implementation plans) is **git-ignored on purpose** — never commit it. Read it for context if it exists locally (`docs/superpowers/specs/`, `docs/superpowers/plans/`).
- Never commit generated or local files: `run/`, `logs/`, `build/`, `.gradle/`, game configs, worlds, downloaded whisper models. `.gitignore` anchors these to the repo root; keep new entries anchored (`/config/`, not `config/`) so they cannot swallow source packages such as `src/**/config/`.
- Do not rewrite history, force-push or delete branches without being asked.
- Config files at runtime: `config/friedai-server.json`, `config/friedai-client.json`; whisper models in `<gameDir>/friedai/models/`.

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

1. `./gradlew build` passes (unit tests **and** GameTests) — quote the real result, do not assume.
2. New player-facing strings exist in both language files.
3. New behavior has a test that failed first; bugs get a regression test.
4. Layer boundaries respected (no Minecraft imports in the pure layers).
5. `mod_version` bumped according to "Versioning" (or a stated reason why no bump is needed).
6. Anything you could not verify (real provider, real microphone, GUI) is stated plainly rather than implied.
