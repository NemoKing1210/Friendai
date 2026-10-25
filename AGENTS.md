# AGENTS.md

Guidance for AI coding agents (and humans) working on **Friendai**: a Fabric mod for Minecraft **26.3** that adds an AI companion you talk to by chat or voice. Mod id `friendai`, root package `dev.friendai`, MIT licensed.

The mod is **not client-only**: the server owns the companion entity, the brain, tasks and memory; the client adds rendering, the companion menu, voice input and the settings screen. Both sides must run the mod (the integrated server in singleplayer counts).

**Orientation for a new agent:**

1. Read "Commands" and "Project map" to find your way around.
2. Read "Architecture" and "Rules that keep it working" before changing code.
3. Find the exact entry point in "Where to start".
4. Follow "Testing" and "Definition of done".
5. If several sessions work in this checkout, read "Parallel sessions" first.

## Commands

Java **25** is required (Gradle and Loom refuse to run on older JVMs). If your default `java` is older, point Gradle at a JDK 25 for the command:

```bash
JAVA_HOME="/path/to/jdk-25" ./gradlew build
```

| Command | What it does |
|---|---|
| `./gradlew build` | Compile, run unit tests **and** the Fabric GameTests (`runGameTest` is part of `check`), then package. The full gate for a release; not required before a commit. |
| `./gradlew test` | Unit tests only (JUnit 5, no Minecraft). Fast. |
| `./gradlew test --tests '*SomeTest'` | One unit test class. |
| `./gradlew runGameTest` | In-game tests only. Boots a Minecraft server and runs the whole suite (several minutes). |
| `./gradlew check` | Unit tests + GameTests, without packaging. |
| `./gradlew assemble` | Compile and package; `exportTestJar` refreshes `test-builds/friendai-<version>.jar`. Does not run tests. |
| `./gradlew runClient` / `runServer` | Dev client / server. `run/` is git-ignored; the server needs `run/eula.txt` with `eula=true`. |
| `./gradlew genSources` | Decompiled Minecraft sources, for looking up API names (see "Minecraft API notes"). |

The first build downloads Minecraft and takes minutes; later builds take seconds.

- Unit results: `build/test-results/test/*.xml` (one XML per class; sum `tests`/`failures`).
- GameTest results: console output — `========= N GAME TESTS COMPLETE` and `N required tests failed` (or `All N required tests passed`).
- Gradle may run `runGameTest` before `test`; if a GameTest fails, run `./gradlew test` separately to get the unit-test count.
- Only one Gradle run per checkout at a time: parallel builds fight over the Gradle lock and the shared `run/` directory.

## Project map

### Top level

| Path | What it is |
|---|---|
| `src/main/java/dev/friendai/` | Common + server code (the mod's `main` source set). |
| `src/client/java/dev/friendai/client/` | Client-only code (Loom split source set). |
| `src/test/java/dev/friendai/` | JUnit 5 unit tests and `testutil` fakes. |
| `src/gametest/java/dev/friendai/test/` | Fabric GameTests (a separate mod id, `friendai-gametest`). |
| `src/main/resources/` | `fabric.mod.json`, `assets/friendai/lang/`, icon, GUI sprites. |
| `src/gametest/resources/` | GameTest `fabric.mod.json` (entrypoints) and `data/.../structure/*.nbt`. |
| `build.gradle`, `settings.gradle`, `gradle.properties` | Loom setup, dependencies, and all versions including `mod_version`. |
| `gradle/` | Wrapper. |
| `scripts/` | Maintenance scripts (`setup-branch-protection.sh`). |
| `.github/workflows/` | CI: unit tests, GameTests, build, commit lint, release. |
| `.githooks/` | Local `commit-msg` hook mirroring commitlint. Enable with `git config core.hooksPath .githooks`. |
| `docs/superpowers/{specs,plans}/` | Local design specs and implementation plans. **Git-ignored**; read for context, never commit. |
| `test-builds/` | Exported test jars (`friendai-<version>.jar`). Git-ignored. |
| `run/`, `build/`, `logs/`, `.gradle/`, `config/` | Local runtime/generated files. Git-ignored. |
| `README.md`, `CONTRIBUTING.md`, `MANUAL_CHECKLIST.md`, `commitlint.config.mjs` | User docs, contributor rules, manual test checklist, commit rules. |

### Source sets

`build.gradle` uses `loom.splitEnvironmentSourceSets()`; one mod (`friendai`) spans `main` + `client`.

| Source set | Path | Contains | Notes |
|---|---|---|---|
| `main` | `src/main/java` | Pure layers, Minecraft-layer server/common code, resources | The shipped jar's common half. |
| `client` | `src/client/java` | Renderer, screens, HUD, keybinds, voice capture, TTS stub | Loaded only on the client. |
| `test` | `src/test/java` | JUnit tests and reusable fakes | No Minecraft at runtime. |
| `gametest` | `src/gametest/java` | Fabric GameTest classes plus `GameTestSupport` | Separate mod `friendai-gametest`; entrypoints listed in `src/gametest/resources/fabric.mod.json`. |

### Where to start

| Concern | Start here |
|---|---|
| Mod init (common) | `FriendaiMod` — registers entity, sounds/commands, server lifecycle. |
| Client init | `client/FriendaiClient` — renderer, HUD, keybind, payload receivers, config. |
| Optional Mod Menu / YACL | `client/config/ModMenuIntegration`, `client/config/FriendaiConfigScreen`. |
| Brain loop | `brain/CompanionBrain`, `brain/PromptBuilder`, `brain/ConversationMemory`. |
| Brain wiring per server | `core/BrainService`, `core/FriendaiServerState`, `core/ServerBrainScheduler`. |
| Tool registry and skills | `tool/builtin/FriendaiTools` (groups and `ToolKit` live in the same package). |
| Tasks | `task/Task`, `task/TaskRunner`, `task/TaskFactory`, `task/builtin/`. |
| LLM access | `llm/LlmClient`, `llm/provider/ProviderRegistry`, `llm/protocol/`, `llm/http/HttpTransport`. |
| The companion | `entity/CompanionEntity`, `entity/CompanionManager`. |
| Commands | `command/FriendaiCommands`. |
| Networking | `net/FriendaiNetworking` and the handlers/streams in `net/`. |
| Configuration | `config/ConfigStore`, `config/ServerConfig`, `config/ClientConfig`. |
| Tests | `src/test/.../testutil` (unit fakes), `src/gametest/.../GameTestSupport` (world helpers). |

## Architecture

Bottom-up. The dependency rule is per class/module, not per package: **pure classes must not import `net.minecraft.*` or `net.fabricmc.*`**. They are plain Java, unit-tested without the game, and could be extracted as a library. `chat/`, `net/` and `perception/` are mixed packages; only the classes named below are pure.

```
client  (render, UI screens/tabs, HUD, voice capture)
   |  Fabric payloads (C2S requests, S2C snapshots/prompt/memory views)
server  (entity, task, core, command, net, menu, perception builders)
   |  calls into pure code with plain data
pure Java  (llm, brain, tool, memory, crafting, pathing, build, survival,
            movement, voice, debug, text, config, pure perception data)
```

### Pure Java modules

- `llm/model/` — `Message`, `ToolCall`, `LlmRequest`/`LlmResponse`, `LlmException`. Invariant: a malformed tool-argument JSON leaves `ToolCall.arguments == null` while `rawArguments` is kept; Gemini's opaque `signature` must be echoed back.
- `llm/protocol/` — one class per wire format: `OpenAiChatProtocol`, `OpenAiResponsesProtocol`, `AnthropicMessagesProtocol`, `GeminiProtocol`, `OllamaProtocol`; each has `toWire`/`fromWire`. Anthropic and Gemini must receive consecutive USER/TOOL messages as **one** user message; malformed arguments serialize as `{}`.
- `llm/provider/` — presets and routing as **data**: `ProviderPreset`, `RoutingRule`, `Route`, `LlmProfile`. First glob match wins; base URL precedence is profile → rule → preset. Protocols and presets are registered in `ProviderRegistry.withBuiltins`.
- `llm/http/HttpTransport` + `llm/LlmClient` — retries only retryable error kinds, maps HTTP statuses to `LlmErrorKind`, redacts keys; `LlmClient.complete` resolves the route and delegates.
- `config/` — `ConfigStore` (atomic temp + move, `.bak` on corruption, versioned migrations), `ServerConfig` (schema version 4, clamping validation), `ClientConfig`, `SecretResolver` (`env:NAME`). API keys are never persisted or displayed unredacted.
- `text/` — `UserMessage(key, args)` and `UserFacingException`: the only player-facing protocol flowing out of pure layers.
- `tool/` (except `tool.builtin`) — `Tool`, `ToolRegistry`, `ArgValidator`, `SchemaBuilder`, `AccessLevel`/`Caller`, `PlanParser`, `InventoryAccess`, `StopScope`. `ToolRegistry.run` never throws: unknown/disabled tool, wrong access, bad JSON or schema violation become `ToolResult.error`. Access is ordinal `GUEST < TRUSTED < OWNER`; the default minimum is `TRUSTED`.
- `brain/` — `CompanionBrain` (the agent loop, one principal per turn), `PromptBuilder`, `ConversationMemory`, `AccessResolver`, `BrainEvent`. Invariants: at most one LLM request per turn; a generation counter silently drops late responses; trimming drops whole leading USER turns so history never starts with a tool result.
- `crafting/` — pure recipes and planning: `Recipe`, `Ingredient`, `Station`, `RecipeBook`, `CraftPlanner` (depth ≤ 5), `CraftPlan`, `CraftStep`, `StationLease` (600-tick lease, opaque packed position), `CraftText`. Minecraft adapters: `task/crafting/RecipeIndex`, `task/crafting/Stations`.
- `crafting/obtain/` — acquisition planning: `ObtainPlanner` (inventory → containers → craft/smelt/mine/hunt/harvest/shear/milk/explore, depth ≤ 8), `ObtainStep` (sealed; 9 kinds), `Source`/`SourceBook`, `WorldFacts`, `OreDepths`, `ToolKind`/`Tier`/`ToolNeed`, `ObtainText`. Adapters: `task/obtain/DropIndex`, `task/obtain/WorldScan`, `tool/builtin/ObtainTools`.
- `pathing/` — pure block-grid A*: `PathSearch` (incremental, per-tick time budget, resumable), `MoveGenerator` (walk/fall/swim/climb/dig/pillar/bridge), `Cell`/`CellView`/`Node`/`Pos` (packed keys), `CostModel`, `GoalPoint`/`GoalReach`, `LineOfSight`, `BlockList`, `MaterialPrices`, `PathingConfig`. Adapter: `task/nav/LevelCellView`; driver: `task/nav/Navigator`.
- `build/` — `Blueprint` (relative to the min corner; max 32³ / 8192 blocks), `BlockSpec`, `BlueprintParser` (palette + ops + ASCII layers, collects all errors), `BuildOrder` (bottom layer first; air, then free, then attachments), `Generators` (parametric houses, towers, walls, fences, platforms, bridges), `BlueprintJson` (saved format `size:[w,h,d]`, `blocks:[[x,y,z,state]]`).
- `survival/` — `HungerModel` (vanilla-like food, saturation, exhaustion, regeneration and starvation, tick by tick; returns a `TickResult` the caller applies) and `Difficulty`.
- `memory/` — `MemoryBook` (`PlayerRecord`, trust keyed by owner UUID; `Fact`, `Subject`, `Category` with monotonic never-reused ids; `revision()`), `MemoryStore` (lazy load, 5 s debounced save, `.bak` on corruption), `MemoryActions` (tool logic and the permission matrix via `MemoryPolicy`), `MemoryMenuActions`, `MemoryView` + `MemoryViewBuilder` + `MemoryViewCodec`, `MemoryViewers`, `MemoryPromptRenderer`, `MemoryReport`; the book also holds the owner's food rules (item id → allowed, an explicit rule wins over the built-in defaults). Invariants: a guest's prompt only shows facts about the guest; memory file format version 2.
- `movement/` — `PaceMode`, `PacePolicy` (AUTO hysteresis: run above 10 blocks, stop below 5).
- `chat/` (pure classes only) — `AddressDetector` (prefix/name matching), `CompanionPicker` (own nearby → nearest other with owner online → own anywhere), `ReplySplitter`, `ConfirmationMatcher`.
- `voice/` — `PcmConverter` (16-bit LE → float), `WhisperModel`/`ModelSpec`, `WhisperModelManager` (size + SHA-256, `.part` then atomic move), `AudioLevel`.
- `debug/` — `DebugLog` (bounded; redacted then truncated), `TaskHistory`, `SecretRedactor`, `DebugAccess`, `CompanionSnapshot` + `SnapshotCodec` (length-prefixed, bounded), `MemoryTabModel`, `PauseRowPlacement`, `RequestType`.
- `perception/` (pure data only) — `PerceptionSnapshot` and `OwnerFocus`, the surroundings and the speaker's view as prompt text.
- `net/RateLimiter` — the only pure class in `net/`.

### Minecraft layer

- `entity/` — `CompanionEntity` (a `PathfinderMob` with a 27-slot inventory, hunger, combat, furnace jobs, never despawns), `CompanionManager` (one loaded companion per owner; summon/dismiss/index), `FriendaiEntities`.
- `task/` — `Task` (`type/start/tick/stop/describe/saveData/cleanupAfterCancel`), `TaskRunner` (one task at a time; cancels the current one on `start`; timeout and crash isolation; pause/suspend/hold; falls back to `FollowTask`), `TaskContext`, `TaskFactory` (restores only `follow`, `stay`, `goto:`, `tend_farm:`, `build:` saved tasks), `TaskListener`, `TaskStatus`, `TaskSettings`.
- `task/builtin/` — 37 concrete activities, thin shells over the helper packages: Follow, Stay, GoTo, PickupItems, GiveItems, MineBlocks, MineBlockAt, PlaceBlock, UseBlock, LightArea, Build, Craft, Smelt, CookFood, CollectSmelted, CollectStation, Farm, TendFarm, Bonemeal, Breed, Shear, Milk, Tame, Lead, Honey, Fish, Hunt, AttackPlayer, Equip, Eat, GetFood, DropItems, StoreItems, TakeItems, CheckContainer, Explore, Obtain, plus `ContainerTaskBase`.
- `task/build/` — blueprint placement: `BlueprintStore` (`<world>/friendai/blueprints/*.json` + in-progress dir), `Scaffold`, `Placement`, `Materials`, `PreviewOutline`, `ScaffoldCleanupTask`.
- `task/combat/` — `CombatController` (reflex combat ticked before the task runner), `CombatMode`, `Equipment` (weapons by DPS, armor), `Food`, `Threats`.
- `task/cooking/` — `FurnaceJobs`/`FurnaceJob`, `FuelPolicy`, `FurnaceSlots`.
- `task/crafting/` — `RecipeIndex` (RecipeManager → pure model), `Stations` (find stations, detect a base).
- `task/farming/` — `Crops`, `Animals`, `FarmPass`, `FarmReport`, `Harvestable`.
- `task/interact/` — `CompanionHands` (one Fabric fake player per companion: vanilla placement/right-click, protection checks), `ContainerAccess`, `PlaceSpot`.
- `task/inventory/` — `InventoryOps`, `ItemMatcher`.
- `task/mining/` — `BlockBreaker`, `BlockSearch`, `BlockMatcher`, `ToolSelector`, `BreakProgress`.
- `task/nav/` — `Navigator`, `PathExecutor`, `MoveHelper` (`LevelCellView`/`MaterialGatherer` adapt pure pathing), `Pace`, `Sight`, `Approach`.
- `task/obtain/` — `DropIndex`, `WorldScan` (probe recipes/drops and scan the world for the planner).
- `task/plan/` — `SequenceTask` (ordered steps), `PlanSave`, `PlanEntry`, `StepFactory`.
- `task/survival/` — `HungerWatcher` (automatic food quest).
- `tool/builtin/` — LLM-facing skills. `FriendaiTools.build` calls 16 group classes (movement, gathering, inventory, building, storage, crafting, cooking, combat, farming, animal, obtain, build, info, memory, trust, control) and returns the registry and the plannable definitions; `ToolKit.add`/`taskTool` builds a task and calls `taskRunner().start`, `addCaller` does the same but hands the turn's caller to the tool, `instantTool` acts directly, and `callerTool` receives the turn's caller; `TaskToolDef` carries schema, factory, plannable flag and minimum access. 60 tools at the time of writing: movement (`follow_player`, `stay`, `go_to`, `jump`, `set_pace`), gathering (`mine_blocks`, `mine_block_at`, `pickup_items`), inventory (`give_items`, `drop_items`, `get_inventory`), building (`place_block`, `use_block`, `light_area`), storage (`store_items`, `take_items`, `check_container`), crafting (`craft_item`, `get_recipe`), cooking (`smelt_items`, `cook_food`, `collect_smelted`), combat (`attack`, `attack_player`, `set_combat_mode`, `equip_item`, `eat`, `get_food`, `allow_food`, `forbid_food`, `reset_food_rule`, `get_food_rules`), farming (`farm`, `tend_farm`, `use_bonemeal`), animals (`breed_animals`, `shear`, `milk`, `tame_animal`, `lead_animals`, `harvest_honey`, `fish`), obtaining (`obtain_item`, `plan_obtain`), blueprints (`blueprint_help`, `preview_structure`, `build_structure`, `save_blueprint`, `list_blueprints`), info (`look_around`, `find_blocks`, `player_view`), control (`show_inventory`, `stop`, `run_plan`), trust (`trust_player`, `untrust_player`). Memory tools `remember`, `forget`, `recall` are always registered; `enabled()` follows `memory.enabled`.
- `core/` — `BrainService` (one brain per owner, the shared `LlmClient`, tool registry, debug state, task↔brain events), `FriendaiServerState` (per-server config, memory, brains, trust), `ServerBrainScheduler`, `CompanionPerception`, `TrustConfirmations`, `Components` (`UserMessage` → `Component`).
- `chat/` — `ChatRouter` (hooks `CHAT_MESSAGE`), `CompanionRouting`, `CompanionSpeaker`, `AddressDetector`, `CompanionPicker`, `ConfirmationMatcher`, `ReplySplitter`.
- `command/` — `/friendai` tree: `summon`, `dismiss`, `stop`, `inventory`, `status`, `trust`/`untrust`/`trusted`, `pending`/`confirm`/`cancel`, `memory list`/`forget`/`clear`, `reload`.
- `net/` — `FriendaiNetworking` (payload registration), C2S `CompanionRequestC2SPayload` + `CompanionRequestHandler`, `VoiceTranscriptC2SPayload` + `VoiceTranscriptHandler`, S2C `CompanionSnapshotS2CPayload` + `PromptS2CPayload` + `MemoryViewS2CPayload`, `DebugStream`, `MemoryViewStream`, `SnapshotBuilder`, `Operators`.
- `menu/` — the companion inventory GUI: `FriendaiMenus` (type + open), `CompanionInventoryMenu`, `CompanionEquipmentContainer` (6 gear slots + 27 inventory slots + viewer inventory).
- `perception/` — `PerceptionBuilder` (brief radius 8, detailed 16) and `OwnerFocusBuilder`; they fill the pure snapshots on the server thread.

### Client

- `client/` — `FriendaiClient`: entity renderer, container screen, config load, HUD, pause-menu button, S2C receivers, push-to-talk. Client config is `config/friendai-client.json`.
- `client/config/` — YACL `FriendaiConfigScreen`, `ModMenuIntegration` (returns `null` when YACL is absent), `ModelCache`.
- `client/render/` — `CompanionRenderer` (humanoid/player model with vanilla skin and armor layer).
- `client/ui/` — `CompanionControlScreen` and tabs (`ControlTab`, `TasksTab`, `MemoryTab`, `LogTab`, `DebugTab`); `CompanionClientState`, `CompanionRequests`, `CompanionInventoryScreen`, `PauseMenuButton`.
- `client/voice/` — `MicCapture` (16 kHz/16-bit/mono), `PushToTalkHandler`, `VoiceRuntime`, `WhisperLocalStt`, `SpeechToText`, `VoiceState`, `VoiceHud`.
- `client/tts/` — interface only: `SpeechOutput`, `NoOpSpeechOutput` (nothing calls them yet).

## Runtime flows

### One chat or voice turn

1. A chat message reaches `ChatRouter.onChat`; voice text arrives as `VoiceTranscriptC2SPayload` and `VoiceTranscriptHandler` hops to the server thread. Trust confirmations are handled before addressing.
2. `AddressDetector` decides whether the companion is addressed; `CompanionRouting` → `CompanionPicker` chooses which companion hears it (own within hearing → nearest other within hearing whose owner is online → own anywhere).
3. `BrainService.post` → `CompanionBrain.post`; guest throttling happens in `shouldDrop`.
4. `maybeStartTurn` batches the events for one principal, fixes `turnCaller`, appends one USER message and calls `requestStep`.
5. `requestStep` builds the system prompt with `PromptBuilder.system` and calls `gateway.complete(system, memory.snapshot(), tools.specs(access))`.
6. The response callback speaks the text and executes every tool call in order through `ToolRegistry.run`; tools normally start a `Task`.
7. Tool results are appended as TOOL messages and `requestStep` loops until there are no calls or `maxToolSteps` is reached; `endTurn` applies the cooldown.
8. When a task finishes, its `TaskListener` posts `TaskFinished` to the brain, which starts the next step.

### Task lifecycle

`TaskRunner.start` cancels the current task, calls `stop`, and starts the new one. Each tick enforces the timeout and isolates crashes (a throwing task becomes `FAILED`, not a broken runner); with nothing to do it falls back to `FollowTask`. Pausing (companion menu, combat retreat) suspends ticking without losing the task. `TaskFactory.restore` rebuilds only the saved task kinds after a reload; anything else starts fresh.

### Companion menu

The client subscribes with `CompanionRequestC2SPayload(SUBSCRIBE)`; `CompanionRequestHandler` calls `DebugStream.sendNow`. `DebugStream` pushes `CompanionSnapshotS2CPayload` every 10 ticks to subscribers; `SnapshotBuilder` builds it for the requester's own companion. Everyone sees status and task history; the brain log, prompt, perception text and tool names are operator-only (`DebugAccess`, checked on the server). The Memory tab sends `MEMORY_OPEN`/`MEMORY_CLOSE` and receives `MemoryViewS2CPayload`.

### Configuration

`FriendaiServerState` loads `config/friendai-server.json` with `ConfigStore`; the client loads `config/friendai-client.json` the same way. `ConfigStore` writes atomically, keeps a `.bak` when it recovers from corruption, and runs versioned migrations. `ServerConfig.activeProfile` → `toLlmProfile` → `LlmClient`; a reload rebuilds the client and registry-bound tools.

### Voice

The client captures audio only while push-to-talk is held, checks that the local Whisper model is ready, transcribes on a daemon thread, and sends `VoiceTranscriptC2SPayload(text)` only if the server can receive it. Audio never leaves the machine; the model is downloaded into `<gameDir>/friendai/models/` and verified by size and SHA-256.

## Data and persistence

| Data | Location | Notes |
|---|---|---|
| Server config | `config/friendai-server.json` | Schema version 4; atomic save, `.bak`, migrations; API keys may be `env:NAME`. |
| Client config | `config/friendai-client.json` | Voice device/model, UI preferences. |
| Companion state | World save, `CompanionEntity` additional data | Owner, inventory, combat mode, pace mode, food/saturation/exhaustion, saved task. Hands, previews, station leases and furnace jobs are transient. |
| Memory | `<world>/friendai/memory/<owner-uuid>.json` | One book per owner, format version 2; 5 s debounced flush; `.bak` on corruption. |
| Blueprints | `<world>/friendai/blueprints/<name>.json` | `size:[w,h,d]`, `blocks:[[x,y,z,state]]`; in-progress builds have their own directory. |
| Whisper models | `<gameDir>/friendai/models/` | Downloaded `tiny`/`base`/`small`, size + SHA-256 checked. |
| Translations | `src/main/resources/assets/friendai/lang/` | `en_us.json` is the source of truth; `ru_ru.json` mirrors it. |

## Network protocol

All payloads are registered in `net/FriendaiNetworking`.

| Direction | Payload | Purpose |
|---|---|---|
| C2S | `CompanionRequestC2SPayload` | Menu request: subscribe/unsubscribe, summon/dismiss/stop, send message, run tool, request prompt, memory tab operations (`RequestType`). |
| C2S | `VoiceTranscriptC2SPayload` | Transcribed voice text; the handler hops to the server thread. |
| S2C | `CompanionSnapshotS2CPayload` | Menu state and task history; operator-only fields are filtered server-side. |
| S2C | `PromptS2CPayload` | The current prompt text, operators only. |
| S2C | `MemoryViewS2CPayload` | What the Memory tab shows; malformed data is dropped by the client. |

The companion inventory/equipment window uses a vanilla container menu (`menu/FriendaiMenus`), not a custom payload.

## Access and permissions

- `AccessLevel`: `GUEST < TRUSTED < OWNER`; each `Tool` has a minimum access, and `ToolRegistry.run` filters before execution.
- Memory rights: `AccessResolver` classifies the caller; `MemoryPolicy` decides read/write/forget per subject; a guest only sees facts about themself. Trust changes need the owner's confirmation (`TrustConfirmations`).
- Operator-only: brain log, prompt, perception text and debug tools (`net/Operators`, `debug/DebugAccess`); `/friendai reload` requires gamemasters.
- Owner-only: the companion inventory window (owner, same dimension, within 8 blocks, alive).
- World protection: `CompanionHands.canInteract` asks `level.mayInteract` through a fake player; build/break blacklists come from `ServerConfig` (`TaskSettings.breakList` and the pathing blacklists).

## Rules that keep it working

1. **Threads.** The world is touched only on the server thread. HTTP and speech recognition run on their own threads and hop back with `server.execute(...)` (`BrainScheduler.execute`). A `CompletableFuture` callback never touches entities directly.
2. **A skill = a `Tool` + usually a `Task`.** Tools describe the skill to the LLM and validate arguments; tasks do the work over time. Add a skill by writing the task, registering the tool in the matching `*Tools` group called from `FriendaiTools.build`, and adding a GameTest. The core does not change.
3. **Several tool calls in one response** must run in order, and Anthropic/Gemini must receive all the results in **one** user message (otherwise HTTP 400). Covered by tests; keep them green.
4. **History trimming** must never leave a tool result or a call without results at the head, and must never drop the turn in progress.
5. **A late LLM response** after `stop`/dismiss/owner logout must be dropped silently (generation counter in `CompanionBrain`).
6. **Malformed tool arguments** from the model must become an error `tool_result`, never an exception.
7. **API keys** never appear in logs, chat, exception messages or `toString()`. Keys go in headers, never URLs.
8. **Layer boundary.** Pure classes import neither `net.minecraft.*` nor `net.fabricmc.*` (see "Architecture"). If you need a world fact, add it to a pure data type and fill it in a Minecraft adapter.
9. **Prefer vanilla behavior.** Placement, right-clicking and protection checks go through the fake player (`CompanionHands`); do not reimplement vanilla block/item logic. Use `Items`/`Blocks`/registry lookups, never hard-coded item ids.
10. **Optional integrations stay optional.** YACL and Mod Menu are `compileOnly`; only `client/config` may touch them, and only when the mod is present.

## Language and localization

- **Everything in the repository is English**: code, comments, identifiers, logs, Markdown, commit messages, config defaults, the system prompt, tool descriptions and results, task summaries.
- **Player-facing text is always a translation key** rendered with `Component.translatable`. `src/main/resources/assets/friendai/lang/en_us.json` is the source of truth; `ru_ru.json` must have exactly the same keys and `%s`/`%d` placeholders. `LangFilesTest` enforces this and also fails if code references a key (a string literal like `"friendai.something"`) that does not exist. Do not build keys by concatenating a literal that ends in `.` — the test will flag it.
- Pure-Java layers return `UserMessage`; the Minecraft layer converts it with `Components.of(...)`.
- Not translated: LLM replies, anything addressed to the LLM, logs, command literals.
- To add a string: add the key to `en_us.json`, the same key to `ru_ru.json`, reference it from code, then run `./gradlew test --tests '*LangFilesTest'`.

## Testing

- **TDD.** Write the failing test first and watch it fail for the right reason. Unit tests use the fakes in `src/test/java/dev/friendai/testutil` — no mocking frameworks.
- **Unit fakes you can reuse:** `MockHttpServer` (scripted loopback HTTP, records method/path/headers/body), `FakeGateway` (scripted LLM responses/calls/failures), `FakeToolExecutor` (records specs/executions/access), `ManualScheduler` (virtual clock), `GridCellView` (ASCII grid `CellView`), `RecordingSpeaker` (captures speech/errors/thinking).
- **GameTests share one world and run in parallel.** So: never rely on global state; give each test its own block type (`MineBlocksTests` uses a different log per test) and filter item/entity counts by type; build a floor with `GameTestSupport.platform(h, size)`; use `GameTestSupport.mockPlayerAt(...)` because mock players log in at the world origin, in an unloaded area. Drive a `TaskRunner` yourself inside `h.succeedWhen(() -> { runner.tick(); ... })` for deterministic ticking, and set `@GameTest(maxTicks = ...)` for slow tests.
- **Entities tick only in the chunk(s) the test structure covers.** A mob that walks or is knocked out of them freezes mid-step: it keeps its velocity, stops falling and stops taking damage, so a fight against it never lands another hit and a companion that walks out stops arriving. Arenas are wider than the 8x8 structure, so build them with `GameTestSupport.platform`/`platformAt`, which force-load every chunk they cover; add `GameTestSupport.enclose(h, y, size, height)` when a hurt or fleeing mob could run off the edge.
- **GameTest runs are expensive; batch them.** Every `runGameTest` boots a Minecraft server, so do not repeat a filtered boot per test. While debugging one test, filter it: `./gradlew runGameTest -Dfabric-api.gametest.filter=friendai-gametest:<test_name>` (the property is read by the GameTest server; if your launcher does not forward it, run the full suite). Run the tests that cover the change when practical; a full `./gradlew runGameTest` is only needed when the change touches the game layer, and it is not a prerequisite for a commit. If a full run shows a failure in an unrelated, timing-sensitive test (the shared world makes a few rotate), re-run only that test filtered for a clean signal instead of repeating the whole suite.
- **New GameTest class?** Add it to the `fabric-gametest` entrypoint list in `src/gametest/resources/fabric.mod.json`, or it will not run.
- Per-owner test hooks exist on `BrainService` (`setGatewayOverride`, `setSpeakerWrapper`) so tests can script the LLM without touching the network.
- `MANUAL_CHECKLIST.md` lists what automation cannot cover (real providers, voice, LAN, both UI languages). Do not tick an item you did not perform.

## Minecraft API notes (26.3)

26.x is unobfuscated: **Mojang names**, no Yarn. Names differ from older versions you may remember, so look them up instead of guessing:

- Decompiled sources: after `./gradlew genSources`, find `*-sources.jar` under `.gradle/loom-cache/minecraftMaven/net/minecraft/` (`minecraft-common-*` and `minecraft-clientOnly-*`). Extract them somewhere outside the repo and `grep`. Without sources, `javap -p -c -cp <minecraft.jar> <class>` still works because the jar keeps its names.
- Fabric API / YACL / Mod Menu jars live in the Gradle cache; inspect with `javap -cp <jar> <class>`.
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
| A Minecraft/Fabric name does not compile | Look it up in decompiled sources or with `javap`; do not guess a variant. |
| A GameTest class does not run | It is missing from the `fabric-gametest` entrypoints in `src/gametest/resources/fabric.mod.json`. |
| `./gradlew build` stops in `runGameTest` | Fix the GameTest first; `test` may not have run. Run `./gradlew test` for the unit count. |
| The settings screen is missing | YACL is not installed; the mod still works through the config files. |

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

1. Tests are not a gate for a commit: commit a coherent change when it is ready. When you do run them, quote the real result (unit-test count and GameTest line) instead of assuming.
2. New player-facing strings exist in both language files.
3. New behavior has a test that failed first; bugs get a regression test.
4. Layer boundaries respected (no Minecraft imports in the pure layers).
5. `mod_version` bumped according to "Versioning" (or a stated reason why no bump is needed).
6. Anything you could not verify (real provider, real microphone, GUI) is stated plainly rather than implied.
7. Your changed files are committed (unless the file also holds another session's edits — then say so). For a multi-step task, the commits follow the batches you finished.
8. `test-builds/friendai-<version>.jar` is refreshed (`exportTestJar` runs during `build`/`assemble`) and you tell the maintainer its path and version.

## Glossary

- **Companion** — the NPC entity (`CompanionEntity`), one per owner, carrying the inventory and hunger.
- **Owner / trusted / guest** — the player who summoned the companion, a player the owner trusts, and anyone else who talks to it.
- **Brain** — `CompanionBrain`, the per-owner agent loop that turns messages into LLM requests and tool calls.
- **Turn** — one user message plus the tool calls and final reply that answer it.
- **Skill** — a player-facing ability exposed to the LLM as a `Tool`, usually implemented by a `Task`.
- **Tool** — the LLM-facing definition (schema, access level, executor) registered in `FriendaiTools`.
- **Task** — long-running world work driven tick by tick by `TaskRunner`.
- **Plan** — an ordered list of tasks (`run_plan` / `SequenceTask`).
- **Blueprint** — a saved or generated block layout that `BuildTask` places.
- **Station** — a crafting/cooking block the companion uses; a `StationLease` remembers one it placed.
- **Perception** — the surroundings and owner focus rendered into prompt text.
- **Snapshot** — the menu state built by `SnapshotBuilder` and sent to the client.
- **Provider / preset / route / protocol** — where an LLM request goes and how it is encoded (`ProviderRegistry`, `llm/protocol`).
