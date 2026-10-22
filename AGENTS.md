# AGENTS.md

Guidance for AI coding agents (and humans) working on **Friedai**: a Fabric mod for Minecraft **26.3** that adds an AI companion you talk to by chat or voice. Mod id `friedai`, root package `dev.friedai`, MIT licensed.

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

Minecraft layer:

- `entity/` — `CompanionEntity` (a `PathfinderMob` with a 27-slot inventory, never despawns), `CompanionManager` (one per owner).
- `task/` — `Task`, `TaskRunner` (one task at a time, timeouts, crash isolation, falls back to Follow), `task/builtin/` (Follow, Stay, GoTo, PickupItems, GiveItems, MineBlocks), `task/mining/`, `task/nav/MoveHelper`.
- `tool/builtin/FriedaiTools` — the 9 skills as tools. `perception/` — surroundings snapshot for the prompt.
- `core/` — `BrainService` (one brain per player, shared `LlmClient`), `FriedaiServerState` (per-server config and brains), `ServerBrainScheduler`, `Components` (`UserMessage` → `Component`).
- `chat/ChatRouter`, `chat/CompanionSpeaker`, `command/FriedaiCommands`, `net/` (voice transcript packet).

Client (`src/client`): renderer, YACL settings screen (`client/config`), voice (`client/voice`: mic capture, push-to-talk, HUD, whisper-jni), `client/tts` (interface only).

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
