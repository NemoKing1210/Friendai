# Implementation-start architecture baseline

Captured at commit `2ea08c0e353bc252c3bde04e1d4206a901b3011d`, mod version `0.80.1`, Minecraft `26.3`, Java `25`. This is an inventory for locating callers during the architecture renewal. Legacy schema versions, identifiers and package names below are historical references, not compatibility promises.

## Build and runtime registrations

- One Gradle Loom root project builds and exports one Fabric mod jar. Loom splits `main` and `client`; GameTests use the `friendai-gametest` source set and mod metadata.
- Root project: `friendai`; Maven group: `dev.friendai`; Java compile release/toolchain: 25.
- Fabric entrypoints: `dev.friendai.FriendaiMod` (`main`), `dev.friendai.client.FriendaiClient` (`client`), and `dev.friendai.client.config.ModMenuIntegration` (`modmenu`, optional).
- Mixin config: `friendai.mixins.json`, `defaultRequire: 1`. Registered mixins/accessors: `PiglinAiInvoker`, `BehaviorUtilsBarterOutputMixin`, `NearestAttackableTargetGoalMixin`, `TargetGoalAccessor`.
- GameTest mod metadata currently registers 89 entrypoint classes; see the complete list below. The project has 90 GameTest Java files including shared support.
- Source inventory: 817 main Java files, 49 client Java files, 329 unit-test Java files, 90 GameTest Java files, and 75 Java files under `task/builtin`.

## Current feature capabilities and task ownership

The current 75 files under `src/main/java/dev/friendai/task/builtin` are assigned below. These names are a migration lookup inventory only; implementation must re-scan the then-current checkout.

| Area | Current task files |
|---|---|
| Navigation | `CircleTask`, `FlyToTask`, `FollowTask`, `GoToPlaceTask`, `GoToTask`, `JumpTask`, `RelativeMoveTask`, `StayTask`, `TurnTask`, `SneakScope` |
| Interaction | `ApproachTask`, `ReachEntityTask`, `EmoteTask`, `ExecuteCommandTask`, `InteractEntityTask`, `MountTask`, `SitTask`, `UseBlockTask`, `WaitTask` |
| Inventory | `CheckContainerTask`, `ContainerTaskBase`, `DepositTask`, `DropItemsTask`, `EquipTask`, `GiveItemsTask`, `PickupItemsTask`, `StoreItemsTask`, `TakeItemsTask`, `ThrowItemTask` |
| Mining | `FellTreeSearchTask`, `FellTreeTask`, `MineBlockAtTask`, `MineBlocksTask` |
| Construction | `BuildTask`, `ClearAreaTask`, `LightAreaTask`, `PlaceBlockTask`, `SpecialStructureTask` |
| Crafting | `CraftTask` |
| Creative | `CreateItemsTask` |
| Cooking | `CollectSmeltedTask`, `CollectStationTask`, `CookFoodTask`, `SmeltTask` |
| Farming | `BonemealTask`, `FarmTask`, `TendFarmTask` |
| Animals | `BreedTask`, `FishTask`, `HoneyTask`, `LeadTask`, `MilkTask`, `ShearTask`, `TameTask` |
| Combat | `AttackPlayerTask`, `HuntTask` |
| Survival | `EatTask`, `GetFoodTask`, `SleepTask` |
| Fire | `ExtinguishFireTask` |
| Acquisition | `ObtainTask`, `SearchResourceTask`, `VisitResourceTask` |
| Exploration | `ExploreTask` |
| Trading | `BarterTask`, `InspectTraderTask`, `LevelTraderTask`, `RefreshTradesTask`, `TradeTask`, `WaitForRestockTask`, `TraderTaskNavigation` |
| Enchanting | `AnvilItemTask`, `EnchantItemTask`, `GrindstoneItemTask`, `MenuBlockTask` |

Current tool contribution groups registered by `FriendaiTools.build`: `MovementTools`, `GatheringTools`, `InventoryTools`, `BuildingTools`, `StorageTools`, `CraftingTools`, `CookingTools`, `EnchantTools`, `CombatTools`, `FarmingTools`, `AnimalTools`, `ObtainTools`, `ResourceTools`, `PlaceTools`, `BuildTools`, `StructureTools`, `InfoTools`, `MemoryTools`, `TrustTools`, `ControlTools`, `TradingTools`, `QueueTools`, `BodyTools`, `RelativeMovementTools`, `InteractionTools`, `FireTools`, `CreativeTools`, `GoalTools`, `SkillTools`, `RoutingTools`, `FreeTimeTools`.

Pure LLM protocols registered in `ProviderRegistry.withBuiltins`: `openai_chat`, `openai_responses`, `anthropic_messages`, `gemini`, `ollama`. Built-in provider IDs: `openai`, `openai_compat`, `anthropic`, `gemini`, `ollama`, `opencode_zen`, `opencode_go`, `command_code`.

The `/friendai` command tree is registered through `FriendaiCommands`, `PlaceCommand` and `SkillCommands`; its root includes summon, dismiss, stop, inventory, status, trust, untrust, trusted, pending confirmation, memory, free-time and operator reload operations. Exact subcommands and handlers remain in those source files.

## Resources and data registration inventory

- Main resources: `fabric.mod.json`, `friendai.mixins.json`, `agent/intents_en.json`, `agent/intents_ru.json`, `assets/friendai/icon.png`, `assets/friendai/lang/en_us.json`, `ru_ru.json`, builtin skill bodies/index under `assets/friendai/skills/`, and `assets/friendai/textures/gui/sprites/companion.png`.
- GameTest resources: `fabric.mod.json` plus `data/friendai-gametest/structure/tiny_hut.nbt`.
- Unit-test resources: prompt-golden transcripts and planner scenario JSON fixtures under `src/test/resources`.
- Translation source has 667 English keys; Russian mirrors it.
- Existing configuration markers, for lookup only: server config schema 15 and client config schema 3.
- Existing world-data identifiers, for lookup only: `friendai:companion_carry`, `friendai:companion_anchors`; owner books under `friendai/{memory,skills,places,placed}`; blueprints under `friendai/blueprints`.
- Existing packet families, for lookup only: companion requests/snapshots, prompt, memory view, places view, skills request/view, voice transcript, companion speech, and movement debug.
- Local speech bindings/native resources are rooted in the mod/client packaging: Whisper JNI for STT and sherpa-onnx for TTS. YACL and Mod Menu are optional compile-only integrations.

## GameTest entrypoint classes
- `dev.friendai.test.FriendaiGameTests`
- `dev.friendai.test.PathingTests`
- `dev.friendai.net.MovementDebugTests`
- `dev.friendai.test.CompanionEntityTests`
- `dev.friendai.test.CompanionDeathTests`
- `dev.friendai.test.CreativeModeTests`
- `dev.friendai.test.CreativeSurvivalTests`
- `dev.friendai.test.CreativeToolsTests`
- `dev.friendai.test.CreativeMaterialTests`
- `dev.friendai.test.CreativeFlightTests`
- `dev.friendai.test.CreativeCommandTests`
- `dev.friendai.test.CreativeQueueTests`
- `dev.friendai.test.CompanionChunkLoadingTests`
- `dev.friendai.test.DropPickupTests`
- `dev.friendai.test.TaskTests`
- `dev.friendai.test.ItemTaskTests`
- `dev.friendai.test.MineBlocksTests`
- `dev.friendai.test.ProtectionTests`
- `dev.friendai.test.MiningTests`
- `dev.friendai.test.InteractTests`
- `dev.friendai.test.PlaceBlockTests`
- `dev.friendai.test.PlacedBlockTests`
- `dev.friendai.test.UseBlockTests`
- `dev.friendai.test.LightAreaTests`
- `dev.friendai.test.ClearTests`
- `dev.friendai.test.StorageTests`
- `dev.friendai.test.FreeTimeTests`
- `dev.friendai.test.ToolTests`
- `dev.friendai.test.PlaceTests`
- `dev.friendai.test.GuestTests`
- `dev.friendai.test.MemoryTests`
- `dev.friendai.test.MemoryMenuTests`
- `dev.friendai.test.SkillsTests`
- `dev.friendai.test.PlanTests`
- `dev.friendai.test.BrainServiceTests`
- `dev.friendai.test.VoiceTests`
- `dev.friendai.test.CompanionControlTests`
- `dev.friendai.test.CraftingTests`
- `dev.friendai.test.CookingTests`
- `dev.friendai.test.CombatTests`
- `dev.friendai.test.CombatMechanicsTests`
- `dev.friendai.test.CombatTacticsTests`
- `dev.friendai.test.CombatDefenseTests`
- `dev.friendai.test.CombatRangedTests`
- `dev.friendai.test.CombatPotionTests`
- `dev.friendai.test.CombatCoordinationTests`
- `dev.friendai.test.HungerTests`
- `dev.friendai.test.FireTests`
- `dev.friendai.test.QueueTests`
- `dev.friendai.test.ConditionalPlansTests`
- `dev.friendai.test.AnimalTests`
- `dev.friendai.test.FarmingTests`
- `dev.friendai.test.ObtainTests`
- `dev.friendai.test.ExploreTests`
- `dev.friendai.test.BuildTests`
- `dev.friendai.test.OwnerViewTests`
- `dev.friendai.test.PerceptionTests`
- `dev.friendai.test.ResourceObservationTests`
- `dev.friendai.test.ResourceToolsTests`
- `dev.friendai.test.ResourceSearchTests`
- `dev.friendai.test.TargetSearchTests`
- `dev.friendai.test.ResourceJourneyTests`
- `dev.friendai.test.PaceTests`
- `dev.friendai.test.TrustTests`
- `dev.friendai.test.InventoryWindowTests`
- `dev.friendai.test.ToolChoiceTests`
- `dev.friendai.test.SpeechTests`
- `dev.friendai.test.TreeFellingTests`
- `dev.friendai.test.BodyLanguageTests`
- `dev.friendai.test.ExperienceTests`
- `dev.friendai.test.MenuHandsTests`
- `dev.friendai.test.TradingMenuTests`
- `dev.friendai.test.TradingAuthorityTests`
- `dev.friendai.test.TradingTransactionTests`
- `dev.friendai.test.TradingTaskTests`
- `dev.friendai.test.TradingToolsTests`
- `dev.friendai.test.TradingRefreshTests`
- `dev.friendai.test.TradingBarterAttributionTests`
- `dev.friendai.test.TradingBarterTaskTests`
- `dev.friendai.test.EnchantSkillTests`
- `dev.friendai.test.AnvilSkillTests`
- `dev.friendai.test.GrindstoneSkillTests`
- `dev.friendai.test.StructureTests`
- `dev.friendai.test.SkinRenameTests`
- `dev.friendai.test.GoalTests`
- `dev.friendai.test.RecoveryTests`
- `dev.friendai.test.ToolCatalogTests`
- `dev.friendai.test.PlannerTests`
- `dev.friendai.test.GoalTickTests`
