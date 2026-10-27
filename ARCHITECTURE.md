# ARCHITECTURE.md

The reference half of the project guide: what each source set and package is for, how one chat turn and one task run, what is persisted where, what crosses the network, and who may do what. Read it before changing a package you have not worked in.

`AGENTS.md` is the other half and the one every session loads: commands, workflow, testing, git hygiene, versioning. When a file here disagrees with the code, the code wins — fix the stale file in the same commit.

## Source sets

`build.gradle` uses `loom.splitEnvironmentSourceSets()`; one mod (`friendai`) spans `main` + `client`.

| Source set | Path | Contains | Notes |
|---|---|---|---|
| `main` | `src/main/java` | Pure layers, Minecraft-layer server/common code, resources | The shipped jar's common half. |
| `client` | `src/client/java` | Renderer, screens, HUD, keybinds, voice capture, speech output | Loaded only on the client. |
| `test` | `src/test/java` | JUnit tests and reusable fakes | No Minecraft at runtime. |
| `gametest` | `src/gametest/java` | Fabric GameTest classes plus `GameTestSupport` | Separate mod `friendai-gametest`; entrypoints listed in `src/gametest/resources/fabric.mod.json`. |

## Where to start

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
| Places | `place/PlaceService` (the server-thread entry point), `place/PlaceRegistry` (the book), `client/ui/PlacesTab`. |
| Commands | `command/FriendaiCommands`, `command/PlaceCommand`. |
| Networking | `net/FriendaiNetworking` and the handlers/streams in `net/`. |
| Configuration | `config/ConfigStore`, `config/ServerConfig`, `config/ClientConfig`. |
| Tests | `src/test/.../testutil` (unit fakes), `src/gametest/.../GameTestSupport` (world helpers). |
| Speech input | `voice/stt/` (pure: protocols, client, presets), `voice/VoiceReadiness`, `client/voice/CloudStt`, `client/voice/WhisperLocalStt`, `client/voice/VoiceRuntime`. |
| Speech output | `voice/tts/` (pure: protocols, engines, queue, voices), `client/tts/` (`SpeechRuntime`, `SpeechPlayback`, `SpeechKeybind`); replies leave `chat/CompanionSpeaker` as `CompanionSpeechS2CPayload`. |

## Overview

Bottom-up. The dependency rule is per class/module, not per package: **pure classes must not import `net.minecraft.*` or `net.fabricmc.*`**. They are plain Java, unit-tested without the game, and could be extracted as a library. `chat/`, `net/` and `perception/` are mixed packages; only the classes named below are pure.

```
client  (render, UI screens/tabs, HUD, voice capture, speech output)
   |  Fabric payloads (C2S requests, S2C snapshots/prompt/memory views)
server  (entity, task, core, command, net, menu, perception builders)
   |  calls into pure code with plain data
pure Java  (llm, brain, agent, queue, tool, memory, knowledge, store, place,
            crafting, pathing, mining, build, survival, creative, movement,
            skin, voice, debug, text, config, pure perception data)
```

## Pure Java modules

- `llm/model/` — `Message`, `ToolCall`, `LlmRequest`/`LlmResponse`, `LlmException`. Invariant: a malformed tool-argument JSON leaves `ToolCall.arguments == null` while `rawArguments` is kept; Gemini's opaque `signature` must be echoed back.
- `llm/protocol/` — one class per wire format: `OpenAiChatProtocol`, `OpenAiResponsesProtocol`, `AnthropicMessagesProtocol`, `GeminiProtocol`, `OllamaProtocol`; each has `toWire`/`fromWire`. Anthropic and Gemini must receive consecutive USER/TOOL messages as **one** user message; malformed arguments serialize as `{}`.
- `llm/provider/` — presets and routing as **data**: `ProviderPreset`, `RoutingRule`, `Route`, `LlmProfile`. First glob match wins; base URL precedence is profile → rule → preset. Protocols and presets are registered in `ProviderRegistry.withBuiltins`.
- `llm/http/HttpTransport` + `llm/LlmClient` — retries only retryable error kinds, maps HTTP statuses to `LlmErrorKind`, redacts keys; `LlmClient.complete` resolves the route and delegates.
- `config/` — `ConfigStore` (atomic temp + move, `.bak` on corruption, versioned migrations), `ServerConfig` (schema version 12, clamping validation, tree settings under `tasks`, `structures.maxNearbySpawned` caps golems around a site, the `agent` section sets tool routing, autonomy, the replan budget, the goal tick, the prompt budget and the planner profile, and the `places` section switches remembering and auto-registration on, caps the book, the automatic merge radius and the mined-block threshold, and sizes the prompt block and the planner list, and the `autonomy` section sets free time: idle start, quiet period, shift length, hourly shift budgets online and offline, failure cooldown, place protection, building and the keep list; `companion.chunkLoading` controls whether the companion holds a chunk ticket and how wide), `ClientConfig` (schema version 3: `voice.stt` for recognition and `voice.tts` for synthesis, each with `local` settings and a `cloud` map of per-provider entries; migrations from v1 and both v2 shapes by shape detection, not only the version number), `VoiceDraft` (pending settings-screen edits per provider), `SecretResolver` (`env:NAME`). A key typed into a config is stored there in plaintext, so `env:NAME` is preferred; keys are never logged, put in a URL, or included in toasts and error messages, and the settings screen masks them until **Show key** is pressed.
- `text/` — `UserMessage(key, args)` and `UserFacingException`: the only player-facing protocol flowing out of pure layers.
- `tool/` (except `tool.builtin`) — `Tool`, `ToolRegistry`, `ArgValidator`, `SchemaBuilder`, `AccessLevel`/`Caller`, `PlanParser`, `InventoryAccess`, `StopScope`. `ToolRegistry.run` never throws: unknown/disabled tool, wrong access, bad JSON or schema violation become `ToolResult.error`. Access is ordinal `GUEST < TRUSTED < OWNER`; the default minimum is `TRUSTED`.
- `brain/` — `CompanionBrain` (the agent loop, one principal per turn), `PromptBuilder` (a thin entry point over `agent/prompt`), `ConversationMemory`, `AccessResolver`, `BrainEvent`, `AgentSettings`, and the ports the agent layer plugs into the loop: `GoalSource` (the goal shown in the prompt) and `PlannerPort` (consult the planner, queue its plan). Invariants: at most one LLM request per turn; a generation counter silently drops late responses, including a planner proposal that arrives after a stop; trimming drops whole leading USER turns so history never starts with a tool result.
- `agent/` — the cognitive layer above the task queue, all pure Java. `goal/` (`GoalBoard`: one primary and up to three background goals with criteria that may carry a queue `Guard`, at most five 160-character lessons, a replan budget; `GoalSave` the `goals:v1:` text; `GoalService` one cached board per owner kept in the owner's memory book, so goals outlive the entity), `route/` (`TurnRouter` picks chat, action, goal, recovery or event from deterministic English and Russian vocabularies in `agent/intents_*.json`, defaulting to action with every tool group), `tools/` (`ToolCatalog`: every tool belongs to a `ToolGroup` and may carry a "when to use" hint; core tools are always visible, `request_tools` unlocks a group for the turn; a tool missing from the catalog is always visible, and a GameTest fails the build until every registered tool has an entry), `prompt/` (`PromptSection`, `PromptAssembler` with a character budget that cuts tool hints first, then rules, memory, mission and state, and never identity, speech, working method or role; `DefaultSections`, `MissionText`), `verify/` (`OutcomeChecker` turns before/after `WorldSnapshot`s into at most eight facts), `recover/` (`RecoveryAdvisor` classifies a failure text and suggests remedies not yet tried; `FailureHandler` appends facts and diagnosis to events and spends the goal's replan budget), `plan/` (`Planner`, `LlmPlanner` with one validation retry and a timeout, `PlannerParser` validates through `QueueParser`, so a plan can only use tools the requester may use), `tick/` (`GoalTickPolicy` decides when an idle companion with an unfinished goal is nudged; only `autonomous` may also ask what to do next), `autonomy/` (free time: `FreeTime` state per owner saved as `freetime:v1:` in the memory book through `FreeTimeService`; `AutonomyFacts` in, `NeedsAssessor` agenda out (at most six needs, reflex or director), `ProgressionLadder` (wood → stone → iron → diamond as data), `ShiftPolicy` (once a second: reflex, director, fallback template or wait; owner work, an active owner goal and the quiet period after the owner speaks come first), `ShiftBudget` (director shifts per hour online and offline, doubling backoff), `IdleStartPolicy`, `ReflexPlans` (unload, shelter, collect, fallback templates), `ShiftOutcome` (record, lesson, cooldown, new stage), `AwaySummary`, `FreeTimeText`; at most two model requests per shift, and a failed shift is not replanned). Adapters: `core/OutcomeFacts`, `BrainService.GamePlannerPort` and `AgentEvents`. Goal tools: `set_goal`, `update_goal`, `finish_goal` (OWNER only: a goal is replayed with the owner's authority).
- `queue/` — the plan queue behind `run_plan` and every goal: `Job`/`JobState`/`JobQueue`, `Guard`/`GuardAction`/`GuardFacts` (a job runs only while its guard holds), `QueuePolicy` picks the next job, `QueueOptimizer`/`MergePolicy` collapse duplicates, `FailurePolicy` decides retries, `PlanRecord`/`PlanArchive`/`PlanStatus`, `QueueSave` (the persisted queue), `QueueSnapshot`/`QueueText` (menu rows and the planner's view), `QueuedStep`, `QueueLimits`, `QueueNotice`, `PlanOptions`.
- `combat/` — pure combat decisions: `ProtectionPolicy` (self only, bodyguard, guard, avoid combat), `FightingStyle`, `RetreatPreference`, immutable `CombatSettings` (persistence format and strict decode), `CombatSnapshot`/`CombatDecision`, deterministic `CombatPolicy` (priority, pursuit bound, target scoring, bounded lead aim), `WeaponPolicy` (tactical score and hysteresis band), `ProjectileThreat` (bounded segment/box intersection; deflection scoped out), `PotionFacts`/`PotionDecision`/`PotionPolicy`. Adapters: `task/combat/CombatController`, `Equipment`.
- `creative/` — the creative ruleset as data: `CreativeMode`, `CreativeAction`, `CreativeActionContext` (initiator + mode revision), `CreativePermissions`, `CreativePolicy`, `CreativeCommandPolicy` (the command allowlist), `CreativeItemCount` (bounded arithmetic for item requests).
- `crafting/` — pure recipes and planning: `Recipe`, `Ingredient`, `Station`, `RecipeBook`, `CraftPlanner` (depth ≤ 5), `CraftPlan`, `CraftStep`, `StationLease` (600-tick lease, opaque packed position), `CraftText`. Minecraft adapters: `task/crafting/RecipeIndex`, `task/crafting/Stations`.
- `crafting/obtain/` — acquisition planning: `ObtainPlanner` (inventory → containers → craft/smelt/mine/hunt/harvest/shear/milk/explore, depth ≤ 8), `ObtainStep` (sealed; 9 kinds), `Source`/`SourceBook`, `WorldFacts`, `OreDepths`, `ToolKind`/`Tier`/`ToolNeed`, `ObtainText`. Adapters: `task/obtain/DropIndex`, `task/obtain/WorldScan`, `tool/builtin/ObtainTools`.
- `pathing/` — pure block-grid A*: `PathSearch` (incremental, per-tick time budget, resumable), `MoveGenerator` (walk/fall/swim/climb/dig/pillar/bridge), `Cell`/`CellView`/`Node`/`Pos` (packed keys), `CostModel`, `GoalPoint`/`GoalReach`, `LineOfSight`, `BlockList`, `MaterialPrices`, `PathingConfig`. Adapter: `task/nav/LevelCellView`; driver: `task/nav/Navigator`.
- `mining/` — digging decisions without the world: `MiningGoal` (block targets reachable from a stance, optionally through an occluded approach), `MiningPathPolicy`, `MiningSafety` (`canApproach`, support checks), `MiningBounds`, `ExplorationPlanner`.
- `build/` — `Blueprint` (relative to the min corner; max 32³ / 8192 blocks), `BlockSpec`, `BlueprintParser` (palette + ops + ASCII layers, collects all errors), `BuildOrder` (bottom layer first; air, then free, then attachments), `Generators` (parametric houses, towers, walls, fences, platforms, bridges), `BlueprintJson` (saved format `size:[w,h,d]`, `blocks:[[x,y,z,state]]`).
- `farming/` — the farm planner without Minecraft: `CropInfo`/`CropBook`, `FieldCell`/`CellKind`/`Soil`, `FoodNeed`, `FarmSnapshot` (what is in the field, in the bag and in need), `FarmGoal`, `FarmPlanner` (snapshot in, ordered `FarmStep`s out: harvest, replant, sow, till, bone meal, missing seeds, in serpentine order with a seed budget) and `FarmPlan` (`describe()` is the LLM-facing line). Adapters: `task/farming/CropIndex`, `FarmSnapshotBuilder`; executor: `task/farming/FarmPass`.
- `structure/` — special structures as data: `SpecialStructure` (a `Blueprint` body, a `Trigger` (`LastBlock` or `Ignite`), an `Outcome` (`EntitySpawned` or `BlocksPresent`), a `Footprint`, required items, `allowedInEnd`), `StructureBook` (`withBuiltins()`: `iron_golem`, `snow_golem`, `nether_portal`; a new structure is a registry entry, ids never repeat), `StructureSnapshot` (what the world looks like at a site), `StructurePlanner` (snapshot in, `StructurePlan` out: `Ready` with ordered `StructureStep`s, `Blocked` with a `Reason`, or `Done`). Adapters: `task/structure/StructureSite` (origin + rotation → world positions), `StructureSnapshotBuilder`, `StructureIndex` (checks the book against the registries); executor: `task/builtin/SpecialStructureTask`.
- `survival/` — `HungerModel` (vanilla-like food, saturation, exhaustion, regeneration and starvation, tick by tick; returns a `TickResult` the caller applies) and `Difficulty`.
- `memory/` — `MemoryBook` (`PlayerRecord`, trust keyed by owner UUID; `Fact`, `Subject`, `Category` with monotonic never-reused ids; `revision()`), `MemoryStore` (lazy load, 5 s debounced save, `.bak` on corruption), `MemoryActions` (tool logic and the permission matrix via `MemoryPolicy`), `MemoryMenuActions`, `MemoryView` + `MemoryViewBuilder` + `MemoryViewCodec`, `MemoryViewers`, `MemoryPromptRenderer`, `MemoryReport`; the book also holds the owner's food rules (item id → allowed, an explicit rule wins over the built-in defaults), the companion's name, the encoded goal board (`goals`, absent means none) and the encoded free-time state (`freeTime`, absent means never used). Invariants: a guest's prompt only shows facts about the guest; memory file format version 3.
- `knowledge/` — what the companion has learned about resources: `ResourceKnowledge`, `ResourceObservation` + `ObservationWindow` (how long an observation still counts), `ResourceSite`, `ResourceQuery`, `KnowledgeStore`/`KnowledgeFile`/`KnowledgeLimits`, plus `search/` (`SearchPlanner`, `SearchSession`, `SearchLedger`, `SearchCoverage`, `ResourceFamily`, `ResourceSearchProfile`) and `travel/` (`TrailGraph`).
- `store/` — `OwnerFileStore` and `StoreCodec`: one JSON file per owner with lazy load, a 5 s debounced background write, atomic replace and a `.bak` on corruption. Places and knowledge are built on it today; a new per-owner book should be too.
- `place/` — what the companion remembers about the world, pure except `PlaceService`: `Place` (one id-keyed record: name, `PlaceKind`, label, dimension, anchor, radius, aliases, `PlaceSource`, author, visits, note), `PlaceRegistry` (one owner's book, the only mutator, a revision per change), `PlaceQuery` (text/kind/dimension matching, prefix over substring, nearest first), `PlaceText` (the prompt block, the planner's knowledge lines and `find_place` output), `PlaceNamer` (unique "wheat farm 2" auto names), `PlaceFile` (versioned DTO with structural checks), `PlaceStore` (per-owner lazy books, 5 s debounce, `.bak` on corruption), `PlaceAutoRegistration` (what a task's `PlaceCandidate` means for the book), `PlaceView`/`PlaceViewBuilder`/`PlaceViewCodec` (menu rows and their wire format). `PlaceService` is the server-thread adapter that tools, the task hook, the prompt and the menu go through; access levels live in the tools, and `go_to_place` never crosses dimensions.
- `movement/` — `PaceMode`, `PacePolicy` (AUTO hysteresis: run above 10 blocks, stop below 5).
- `chat/` (pure classes only) — `AddressDetector` (prefix/name matching), `CompanionPicker` (own within hearing → nearest other within hearing with owner online), `ReplySplitter`, `ConfirmationMatcher`, `NameColor` (stable colour derived from the companion name).
- `voice/` — shared speech plumbing: `VoicePreset`/`CloudField`/`VoiceAuth` (provider data, visible fields and auth style), `VoiceConfig`, `ResolvedStt`/`ResolvedTts`, `VoiceReadiness` (why a configuration cannot run), `VoiceHttp`/`PreparedRequest` (headers only for credentials, 10 MB cap, no automatic retries), `VoiceLanguages` (game locale → provider language), `VoiceErrors`, `PcmAudio`, `PcmConverter` (16-bit LE → float, gain, stereo), `SpatialAudio` (listener/source geometry → equal-power pan and distance level), `WavCodec`, `MultipartBody`, `WhisperModel`/`ModelSpec`, `WhisperModelManager` (size + SHA-256, `.part` then atomic move), `AudioLevel`.
- `voice/stt/` — recognition protocols and presets: `SttProtocol` with `OpenAiSttProtocol` (OpenAI and Groq) and `YandexSttProtocol`, `SttClient`, `SttPresets`.
- `voice/tts/` — speech output without Minecraft: `SpeechText` cleaning and splitting, `TtsClip`, `SpeechQueue`, `SpeechGate`/`PlaybackGate`, `TtsProtocol` with `OpenAiTtsProtocol`, `ElevenLabsTtsProtocol` and `YandexTtsProtocol`, `TtsClient`, `TtsPresets`, `VoiceModels`/`VoiceModelManager` for the five Piper voices, and the `SpeechEngine` implementations (`LocalTtsEngine`, `CloudTtsEngine`).
- `debug/` — `DebugLog` (bounded; redacted then truncated), `TaskHistory`, `SecretRedactor`, `DebugAccess`, `CompanionSnapshot` + `SnapshotCodec` (length-prefixed, bounded), `MemoryTabModel`, `PlacesTabModel`, `PauseRowPlacement`, `RequestType`.
- `skin/` — the server half of custom companion skins: `SkinFetcher`, `SkinStore`, `SkinImage`, `SkinHash`, `FetchedSkin` (fetched and cached by hash; the client keeps its own copy in `client/skin`).
- `perception/` — pure prompt data (`PerceptionSnapshot`, `TreeSummary`, `TreeText`, `OwnerFocus`, `SelfStatus`, `Threat`, `Landmark`, `RelativeDirection`, `AreaMap`) and the server-thread adapters that fill it (`PerceptionBuilder`, `OwnerFocusBuilder`, `GroundText`, `LandmarkScanner`, `AreaMapBuilder`). Only the data classes are pure; the adapters read the world.
- `net/RateLimiter` — the only pure class in `net/`.

## Minecraft layer

- `mixin/` — the only mixins: `NearestAttackableTargetGoalMixin` (vanilla hostile goals ask for `Player`, so after the vanilla search it also considers a `CompanionEntity` under the same conditions and prefers the nearer one) and `TargetGoalAccessor`. Declared in `friendai.mixins.json` with `defaultRequire: 1`.
- `entity/` — `CompanionEntity` (a `PathfinderMob` with a 27-slot inventory, hunger, combat, furnace jobs, never despawns), `CompanionManager` (one loaded companion per owner; summon/dismiss/index), `CompanionChunkLoader` (keeps each loaded companion's chunk ticking through a runtime-only ticket and restores anchors on startup), `CompanionAnchors` (saved owner → dimension + chunk, `SavedData`), `CompanionTicketSet` (pure reference-counted ticket bookkeeping), `FriendaiEntities`.
- `task/` — `Task` (`type/start/tick/stop/describe/saveData/cleanupAfterCancel`), `TaskRunner` (one task at a time; cancels the current one on `start`; timeout and crash isolation; pause/suspend/hold; falls back to `FollowTask`), `TaskContext`, `TaskFactory` (restores only `follow`, `stay`, `goto:`, `tend_farm:`, `build:` saved tasks), `TaskListener`, `TaskStatus`, `TaskSettings`.
- `task/builtin/` — 39 concrete activities, thin shells over the helper packages: Follow, Stay, GoTo, PickupItems, GiveItems, MineBlocks, MineBlockAt, FellTree, PlaceBlock, UseBlock, LightArea, Build, SpecialStructure, Craft, Smelt, CookFood, CollectSmelted, CollectStation, Farm, TendFarm, Bonemeal, Breed, Shear, Milk, Tame, Lead, Honey, Fish, Hunt, AttackPlayer, Equip, Eat, GetFood, DropItems, StoreItems, TakeItems, CheckContainer, Explore, Obtain, plus `ContainerTaskBase`.
- `task/build/` — blueprint placement: `BlueprintStore` (`<world>/friendai/blueprints/*.json` + in-progress dir), `Scaffold`, `Placement`, `Materials`, `PreviewOutline`, `ScaffoldCleanupTask`.
- `task/combat/` — `CombatController` (reflex combat ticked before the task runner; one action arbiter for movement, shields, bow, potions and retreat), `CombatMode` (legacy preset adapter), `Equipment` (weapons by DPS and durability, armor), `Food`, `Threats`.
- `task/cooking/` — `FurnaceJobs`/`FurnaceJob`, `FuelPolicy`, `FurnaceSlots`.
- `task/crafting/` — `RecipeIndex` (RecipeManager → pure model), `Stations` (find stations, detect a base).
- `task/farming/` — `Crops`, `Animals`, `FarmPass` (executes a `FarmPlan` step by step and re-plans when the world disagrees), `FarmReport`, `Harvestable`, `CropIndex` (registries → `CropBook`), `FarmSnapshotBuilder` (world → `FarmSnapshot`).
- `task/interact/` — `CompanionHands` (one Fabric fake player per companion: vanilla placement/right-click, protection checks), `ContainerAccess`, `PlaceSpot`.
- `task/inventory/` — `InventoryOps`, `ItemMatcher`.
- `task/mining/` — `BlockBreaker`, `BlockSearch`, `BlockMatcher`, `ToolSelector`, `BreakProgress`, `TreeFinder` (log clusters classified as tree, stump, floating or log structure; the descent finds the true base).
- `task/nav/` — `Navigator`, `PathExecutor`, `MoveHelper` (`LevelCellView`/`MaterialGatherer` adapt pure pathing), `Pace`, `Sight`, `Approach`.
- `task/obtain/` — `DropIndex`, `WorldScan` (probe recipes/drops and scan the world for the planner).
- `task/plan/` — `SequenceTask` (ordered steps), `PlanSave`, `PlanEntry`, `StepFactory`.
- `task/structure/` — `StructureSite`, `StructureSnapshotBuilder`, `StructureIndex` (the Minecraft adapters of `structure/`).
- `task/survival/` — `HungerWatcher` (automatic food quest).
- `task/autonomy/` — `PlaceGuard`: an autonomous job (a plan free time marked in `TaskRunner`) never breaks inside a known place (farm tools may harvest a farm), takes only from containers inside one, and never builds over one; owner orders are unaffected.
- `tool/builtin/` — LLM-facing skills. `FriendaiTools.build` calls 18 group classes (movement, gathering, inventory, building, storage, crafting, cooking, combat, farming, animal, obtain, build, structure, info, place, memory, trust, control) and returns the registry and the plannable definitions; `ToolKit.add`/`taskTool` builds a task and calls `taskRunner().start`, `addCaller` does the same but hands the turn's caller to the tool, `instantTool` acts directly, and `callerTool` receives the turn's caller; `TaskToolDef` carries schema, factory, plannable flag and minimum access. 110 tools at the time of writing: movement (`follow_player`, `stay`, `go_to`, `jump`, `set_pace`), gathering (`fell_tree`, `mine_blocks`, `mine_block_at`, `pickup_items`), inventory (`give_items`, `drop_items`, `get_inventory`), building (`place_block`, `use_block`, `light_area`), storage (`store_items`, `take_items`, `check_container`, `deposit_items`), crafting (`craft_item`, `get_recipe`), cooking (`smelt_items`, `cook_food`, `collect_smelted`), combat (`attack`, `attack_player`, `set_combat_mode`, `get_combat_settings`, `set_combat_settings`, `equip_item`, `eat`, `get_food`, `allow_food`, `forbid_food`, `reset_food_rule`, `get_food_rules`), farming (`farm`, `plan_farm`, `tend_farm`, `use_bonemeal`), animals (`breed_animals`, `shear`, `milk`, `tame_animal`, `lead_animals`, `harvest_honey`, `fish`), obtaining (`obtain_item`, `plan_obtain`), blueprints (`blueprint_help`, `preview_structure`, `build_structure`, `save_blueprint`, `list_blueprints`), special structures (`build_special`, `plan_special`: the finishing carved pumpkin or flint and steel must already be in the bag), info (`find_trees`, `look_around`, `find_blocks`, `player_view`, `describe_block`, `survey_area`), places (`remember_place`, `find_place`, `go_to_place`, `rename_place`, `forget_place`), control (`show_inventory`, `set_name`, `stop`, `run_plan`), trust (`trust_player`, `untrust_player`), free time (`start_free_time`, `end_free_time`, `choose_activity`). Memory tools `remember`, `forget`, `recall` are always registered; `enabled()` follows `memory.enabled`.
- `core/` — `BrainService` (one brain per owner, the shared `LlmClient`, tool registry, debug state, task↔brain events), `FriendaiServerState` (per-server config, memory, brains, trust), `ServerBrainScheduler`, `CompanionPerception`, `TrustConfirmations`, `Components` (`UserMessage` → `Component`), `FreeTimeDriver` (free time on the server thread: idle start, reflex and fallback plans, the director event, the planner call, shift ends, stop) and `AutonomyFactsBuilder` (world → `AutonomyFacts`, cached 5 s).
- `chat/` — `ChatRouter` (hooks `CHAT_MESSAGE`), `CompanionRouting`, `CompanionSpeaker` (chat lines plus the speech packet), `AddressDetector`, `CompanionPicker`, `ConfirmationMatcher`, `ReplySplitter`.
- `command/` — `/friendai` tree: `summon`, `dismiss`, `stop`, `inventory`, `status`, `trust`/`untrust`/`trusted`, `pending`/`confirm`/`cancel`, `memory list`/`forget`/`clear`, `reload`.
- `net/` — `FriendaiNetworking` (payload registration), C2S `CompanionRequestC2SPayload` + `CompanionRequestHandler`, `VoiceTranscriptC2SPayload` + `VoiceTranscriptHandler`, S2C `CompanionSnapshotS2CPayload` + `PromptS2CPayload` + `MemoryViewS2CPayload` + `PlacesViewS2CPayload` + `CompanionSpeechS2CPayload`, `DebugStream`, `MemoryViewStream`, `PlaceViewStream`, `SnapshotBuilder`, `Operators`.
- `menu/` — the companion inventory GUI: `FriendaiMenus` (type + open), `CompanionInventoryMenu`, `CompanionInventoryLayout` (the slot origins and bands the client draws around), `CompanionEquipmentContainer` (6 gear slots + 27 inventory slots + viewer inventory).
- `perception/` — `PerceptionBuilder` (brief radius 8, detailed 16) and `OwnerFocusBuilder`; they fill the pure snapshots on the server thread.

## Client

- `client/` — `FriendaiClient`: entity renderer, container screen, config load, HUD, pause-menu button, S2C receivers, push-to-talk. Client config is `config/friendai-client.json`.
- `client/config/` — YACL `FriendaiConfigScreen` (one Voice category with a Recognition and a Synthesis group; provider switches preserve the values of the provider being left through `VoiceDraft`), `ModMenuIntegration` (returns `null` when YACL is absent), `ModelCache`.
- `client/render/` — `CompanionRenderer` (humanoid/player model with vanilla skin, armor layer and the player's arm pose while eating).
- `client/ui/` — `CompanionControlScreen` (a wrapping tab bar over up to nine tabs) and the tabs (`ControlTab` as Overview, `TogglesTab` for the per-companion switches, `TasksTab`, `MemoryTab`, `PlacesTab`, `AppearanceTab`, `TradersTab`, `LogTab`, `DebugTab`); `CompanionClientState`, `CompanionRequests`, `CompanionInventoryScreen`, `PauseMenuButton`, `ClientKeys` (key category), `ClientToasts`.
- `client/voice/` — `MicCapture` (16 kHz/16-bit/mono), `PushToTalkHandler`, `VoiceRuntime` (provider selection, readiness and the record/transcribe flow), `CloudStt`, `WhisperLocalStt`, `SpeechToText`, `VoiceState`, `VoiceHud`.
- `client/tts/` — `SpeechRuntime` (queues replies and drives synthesis and playback), `SpeechPlayback` (writes to a `SourceDataLine`, applies the volume and the output device), `SpeechKeybind` (the **Stop speaking** key).

## Runtime flows

### One chat or voice turn

1. A chat message reaches `ChatRouter.onChat`; voice text arrives as `VoiceTranscriptC2SPayload` and `VoiceTranscriptHandler` hops to the server thread. Trust confirmations are handled before addressing.
2. `AddressDetector` decides whether the companion is addressed; `CompanionRouting` → `CompanionPicker` chooses which companion hears it (own within hearing → nearest other within hearing whose owner is online). Nothing in range means no companion hears it and the sender is told it is too far.
3. `BrainService.post` → `CompanionBrain.post`; guest throttling happens in `shouldDrop`.
4. `maybeStartTurn` batches the events for one principal, fixes `turnCaller`, appends one USER message and calls `requestStep`.
4a. The batch is routed (`TurnRouter`): the turn mode decides which tool groups are visible. A failed plan with replans left, and a complex request that sets a goal, consult the planner first; its plan is queued with the turn caller's access and shown once in the Mission block.
5. `requestStep` builds the system prompt with `PromptBuilder.system` and calls `gateway.complete(system, memory.snapshot(), tools.specs(access))`, where the specs are filtered to the visible groups when `agent.toolRouting` is on. Speech is still checked against every tool the caller has.
6. The response callback speaks the text and executes every tool call in order through `ToolRegistry.run`; tools normally start a `Task`.
7. Tool results are appended as TOOL messages and `requestStep` loops until there are no calls or `maxToolSteps` is reached; `endTurn` applies the cooldown.
8. When a task finishes, its `TaskListener` posts `TaskFinished` to the brain, which starts the next step.

### Task lifecycle

`TaskRunner.start` cancels the current task, calls `stop`, and starts the new one. Each tick enforces the timeout and isolates crashes (a throwing task becomes `FAILED`, not a broken runner); with nothing to do it falls back to `FollowTask`. Pausing suspends ticking without losing the task: `CombatController` sets it around a retreat and around a fight it did not order, the companion inventory window holds it through `hold()`/`release()`, and so does the companion menu — `DebugStream.openMenu`/`closeMenu` hold the owner's companion on subscribe and release it on unsubscribe, on a disconnect and when the subscriber is gone, so the world keeps ticking and only the companion pauses. Free time does not run behind the menu either: a held runner does not tick, and `IdleStartPolicy` already treats a paused runner as combat. `TaskFactory.restore` rebuilds only the saved task kinds after a reload; anything else starts fresh.

### Companion menu

The client subscribes with `CompanionRequestC2SPayload(SUBSCRIBE)`; `CompanionRequestHandler` calls `DebugStream.openMenu`, which subscribes the player, holds the owner's companion (`CompanionEntity.addMenuViewer`, so the work pauses while the world keeps running) and sends the first snapshot. `UNSUBSCRIBE` goes through `DebugStream.closeMenu`, the same path a disconnect and the ten-tick sweep take, and releases the hold. `DebugStream` pushes `CompanionSnapshotS2CPayload` every 10 ticks to subscribers; `SnapshotBuilder` builds it for the requester's own companion. A companion summoned, respawned or loaded while its owner's menu is open starts held (`CompanionManager.register`). Everyone sees status and task history; the brain log, prompt, perception text and tool names are operator-only (`DebugAccess`, checked on the server). The Memory tab sends `MEMORY_OPEN`/`MEMORY_CLOSE` and receives `MemoryViewS2CPayload`; the Places tab does the same with `PLACES_OPEN`/`PLACES_CLOSE` and `PlacesViewS2CPayload` (rename and delete travel as `PLACES_RENAME`/`PLACES_FORGET`).

### Configuration

`FriendaiServerState` loads `config/friendai-server.json` with `ConfigStore`; the client loads `config/friendai-client.json` the same way. `ConfigStore` writes atomically, keeps a `.bak` when it recovers from corruption, and runs versioned migrations. `ServerConfig.activeProfile` → `toLlmProfile` → `LlmClient`; a reload rebuilds the client and registry-bound tools.

### Speech recognition

The client captures audio only while push-to-talk is held, resolves the selected provider (local Whisper or a cloud preset), checks readiness, transcribes on a daemon thread, and sends `VoiceTranscriptC2SPayload(text)` only if the server can receive it. The transcript hops to the client thread first. Local audio never leaves the machine; a cloud provider uploads the recording only while it is selected. The Whisper model is downloaded into `<gameDir>/friendai/models/` and verified by size and SHA-256.

### Speech output

`CompanionSpeaker` sends each reply as a `CompanionSpeechS2CPayload(companion, text, addressee, companionId, audibleRange)`, the last two filled from the speaking entity and `chat.hearingRadius`. The client's `SpeechRuntime` cleans and splits the text, then either synthesizes locally with sherpa-onnx (`LocalTtsEngine`, a downloaded Piper voice) or calls a cloud engine (`CloudTtsEngine`: OpenAI, ElevenLabs or Yandex) through `TtsClient` and the shared `VoiceHttp` on a worker thread. `SpeechPlayback` plays the clip through the chosen device, opened with a small buffer so a change is heard without the device's default delay; when **Voice comes from the companion** is on (`voice.tts.spatial`), it mixes the clip to stereo in 20 ms blocks and ramps each channel from the previous block's gains to the `SpatialAudio` gains that `SpeechRuntime.tick` recomputes every client tick, so the pan and the attenuation follow the player without steps or clicks, and a reply whose companion is not in the client's level is dropped. With the setting off the clip plays centred at full volume. Synthesis is off by default; the settings live under `voice.tts` in the client config. `SpeechGate` drops a packet while recording or when it is addressed to another player and "speak for other players" is off. Push-to-talk and the **Stop speaking** keybind stop playback, and a late result is dropped by a generation counter. Only cloud engines send the reply text off the machine, keys travel in headers only, and paid requests are never retried automatically.

## Data and persistence

| Data | Location | Notes |
|---|---|---|
| Server config | `config/friendai-server.json` | Schema version 12; atomic save, `.bak`, migrations; API keys may be `env:NAME`. |
| Client config | `config/friendai-client.json` | Schema version 3: recognition (`voice.stt`) and synthesis (`voice.tts`), each with local settings and a per-provider cloud map; UI preferences. Migrated from v1 and both v2 shapes. |
| Companion state | World save, `CompanionEntity` additional data | Owner, inventory, combat mode, pace mode, food/saturation/exhaustion, saved task. Hands, previews, station leases and furnace jobs are transient. |
| Companion anchors | World `SavedData` `friendai:companion_anchors` | Owner → dimension + chunk, used to load the companion on startup. The chunk ticket itself is runtime-only and never saved. |
| Memory | `<world>/friendai/memory/<owner-uuid>.json` | One book per owner, format version 3 (facts, trust, food rules, name, persona, goals, free time); 5 s debounced flush; `.bak` on corruption. |
| Places | `<world>/friendai/places/<owner-uuid>.json` | One book per owner, format version 1: id-keyed places with names, aliases, dimension, anchor, visits and author; 5 s debounced flush; `.bak` on corruption. |
| Blueprints | `<world>/friendai/blueprints/<name>.json` | `size:[w,h,d]`, `blocks:[[x,y,z,state]]`; in-progress builds have their own directory. |
| Whisper models | `<gameDir>/friendai/models/` | Downloaded `tiny`/`base`/`small`, size + SHA-256 checked. |
| TTS voices | `<gameDir>/friendai/models/tts/` | The five catalogued Piper voices (sherpa-onnx), size + SHA-256 checked; each carries its own license. |
| Translations | `src/main/resources/assets/friendai/lang/` | `en_us.json` is the source of truth; `ru_ru.json` mirrors it. |

## Network protocol

All payloads are registered in `net/FriendaiNetworking`.

| Direction | Payload | Purpose |
|---|---|---|
| C2S | `CompanionRequestC2SPayload` | Menu request: subscribe/unsubscribe, summon/dismiss/stop, send message, run tool, request prompt, memory and places tab operations (`RequestType`). |
| C2S | `VoiceTranscriptC2SPayload` | Transcribed voice text; the handler hops to the server thread. |
| S2C | `CompanionSnapshotS2CPayload` | Menu state and task history; operator-only fields are filtered server-side. |
| S2C | `PromptS2CPayload` | The current prompt text, operators only. |
| S2C | `MemoryViewS2CPayload` | What the Memory tab shows; malformed data is dropped by the client. |
| S2C | `PlacesViewS2CPayload` | The Places tab: whether remembering is on, one row per place (name, kind, dimension, position, visits) and the last action's status; sent on open, after a mutation and every 10 ticks when the book or the config changed; malformed data is dropped by the client. |
| S2C | `CompanionSpeechS2CPayload` | `(companion, text up to 2000 chars, addressee, companionId, audibleRange)`: one reply to speak on the client; `companionId` locates the source in the client's level and `audibleRange` is the server's hearing radius. The client drops it while recording, when it is addressed to another player and `speakGuests` is off, or when spatial speech is on and that companion is not in the client's level. |

The companion inventory/equipment window uses a vanilla container menu (`menu/FriendaiMenus`), not a custom payload.

## Access and permissions

- `AccessLevel`: `GUEST < TRUSTED < OWNER`; each `Tool` has a minimum access, and `ToolRegistry.run` filters before execution.
- Memory rights: `AccessResolver` classifies the caller; `MemoryPolicy` decides read/write/forget per subject; a guest only sees facts about themself. Trust changes need the owner's confirmation (`TrustConfirmations`).
- Operator-only: brain log, prompt, perception text and debug tools (`net/Operators`, `debug/DebugAccess`); `/friendai reload` requires gamemasters.
- Owner-only: the companion inventory window (owner, same dimension, within 8 blocks, alive).
- World protection: `CompanionHands.canInteract` asks `level.mayInteract` through a fake player; build/break blacklists come from `ServerConfig` (`TaskSettings.breakList` and the pathing blacklists).

