# TODO

Work items to implement later, newest at the bottom. Pick one by its number ("do task 2"). Open work lives in this file; finished items stay ticked `[x]` until an explicit clearing removes them, so `grep -nE '^\s*- \[[ ~]\]' TODO.md` lists what is still to do.

**Numbers are stable.** Never renumber an existing item and never reuse a number, finished or open: a number said out loud in a session must keep pointing at the same work. A closed item keeps its line and its number until the list is cleared; clearing retires the range in the header. Sub-items keep theirs (`2.3`).

**Retired numbers.** Numbers up to and including 188 are reserved and never reused; a cleared item's number resolves through git history, and a surviving open or claimed item keeps its number.

**Choose whole items, not slices.** Asked to pick work, take a top-level item with its own number (`17`), never a sub-item (`17.1`): a sub-item is one slice of its parent and is done as part of it. An explicitly named sub-item ("do 17.1") is an order and is the one exception. The full rule is in `AGENTS.md`, "Working from TODO.md".

**An item is claimed before it is worked on.** `- [~]` instead of `- [ ]` means a session has taken it. That marker alone is the claim: do not add a session name, a timestamp or an `**In progress**` line under the item. The marker stays uncommitted while the task runs and is committed only when the whole task is done, together with its files — never as a separate claim commit, so in a shared checkout the uncommitted `[~]` is what the other session sees. Do not start a claimed item. Finish: tick `[x]` and leave the line in place until the list is cleared. Stop: restore `[ ]` and say why in the final message. A `[~]` older than 24 hours with no active session may be taken over. The full rule is in `AGENTS.md`, "Working from TODO.md".

## Items

The November 13, 2026 audit starts with the live regressions in items 80–82. Item 71 measures the same flows. Items 83–86 and 89 cover the model-facing tool and conversation boundaries. Items 72–79, 87–88 and 90 cover movement, building, voice, execution, autonomy and runtime cost. The latest inspected game ran Friendai 0.212.1 on Minecraft 26.3 from 23:36 to 23:43 local time. Its owner log is `session-203624-7b4984.jsonl`. Sequence numbers below refer to that file. Reproduce the failures in sanitized fixtures with the same terrain and inventory constraints.

- [~] **105. Evaluate a real companion on unfamiliar orders and worlds.** `src/test/java/dev/friendai/agent/eval/LiveScenarioRunner.java:49` connects a provider, but `engine/src/testFixtures/java/dev/friendai/agent/eval/ScenarioRunner.java:163` still uses fake tools, a fixed position and a scripted planner at `:192`. `engine/src/testFixtures/java/dev/friendai/testutil/FakeToolExecutor.java:45` records calls and returns success for an unscripted tool. A model can choose a bad action and receive evidence that it worked. The live stop fixture at `ScenarioRunner.java:156` controls a pending response on the fake gateway even when the brain uses the live one. This harness is useful for checking turn mechanics. Product claims need an observed result in Minecraft.

- [~] **140. Require an affirmative owner instruction before opening a trade spending scope.** `engine/src/main/java/dev/friendai/agent/route/TurnRouter.java:37` detects trading verbs without evaluating their use in a quote, conditional or prohibition. At `engine/src/main/java/dev/friendai/agent/route/TurnRouter.java:60` a blocked plan can choose `RECOVERY` with all groups before the question checks run. `engine/src/main/java/dev/friendai/agent/turn/CompanionBrain.java:615` combines the resulting mode and groups with that verb check, and `engine/src/main/java/dev/friendai/features/trading/TradeAuthority.java:60` can open a scope for the owner. The later order and budget checks depend on this initial grant to distinguish authorized spending from a discussion.

  - [~] **140.1. Keep spending consent tied to the owner message that granted it.** Evaluate the affirmative instruction independently of recovery routing. Bind the existing authority scope to the permitted purchase or barter and stated limits, preserving order and reservation checks. Use a short question when consent or scope is unclear. **Done when** quoted, negated, hypothetical and advice-only trade requests cannot authorize payment, including when an unrelated blocked-plan event arrives in the same batch. Run a scenario with real inventory and a merchant or piglin. Verify stock and payment stay unchanged in refused cases and a valid bounded order still succeeds.

## Audit coverage

The static inventory covers every current registry name. `ToolInventoryTest` enumerates 152 names and checks unique names, nonempty descriptions and top-level parameter count. The nine configuration-dependent names were verified in the `MemoryTools` and `SelfTools` registrations and their runtime enablement. Exact tool-name literals appear for 159 tools in GameTest source and for 142 in JVM test source. `logs_search` and `reset_manner` appear only in the JVM tests. Each row below names existing test families as traceable starting points. A literal may come from a catalog assertion or scripted response. Individual tool execution is verified only where a test drives that entry point and checks its world outcome. The full 111-class server matrix and paid provider tests were outside this pass.

| Catalog group | Tools | Existing fixtures and backlog |
|---|---|---|
| ANIMALS (7) | `breed_animals`, `shear`, `milk`, `tame_animal`, `lead_animals`, `harvest_honey`, `fish` | AnimalTests, FireTests; items 93, 100 |
| AUTONOMY (6) | `start_free_time`, `end_free_time`, `choose_activity`, `set_routine`, `list_routines`, `cancel_routine` | FreeTimeTests, RoutineTests, SocialOpeningTests; items 74, 90 |
| BLUEPRINT (5) | `blueprint_help`, `preview_structure`, `build_structure`, `save_blueprint`, `list_blueprints` | BuildTests, StructureTests; items 73, 83 |
| BODY (11) | `look_at`, `point_at`, `turn`, `sneak`, `emote`, `sit`, `stand`, `wait`, `swap_hands`, `set_idle_behavior`, `set_follow_distance` | BodyLanguageTests, PaceTests, TaskFaceTests; item 83 |
| BUILDING (4) | `use_block`, `place_block`, `light_area`, `clear_area` | BuildTests, ClearTests, LightAreaTests, PlaceBlockTests, UseBlockTests; items 73, 95 |
| COMBAT (4) | `attack`, `attack_player`, `get_combat_settings`, `set_combat_settings` | CombatTests, CombatDefenseTests, CombatTacticsTests, CombatMechanicsTests, CombatCoordinationTests, CombatRangedTests, CombatPotionTests; item 83 |
| CONTROL (4) | `set_name`, `create_item`, `fly_to`, `execute_command` | CreativeModeTests, CreativeToolsTests, CreativeFlightTests, CreativeCommandTests, SkinRenameTests; items 83, 96 |
| COOKING (3) | `smelt_items`, `cook_food`, `collect_smelted` | CookingTests, HungerTests; item 83 |
| CORE (13) | `get_inventory`, `find_place`, `stop`, `queue_add`, `plan_help`, `queue_show`, `queue_edit`, `plan_patch`, `set_goal`, `update_goal`, `finish_goal`, `look_around`, `request_tools` | GoalTests, ConditionalPlansTests, QueueTests, ActiveTaskLifecycleTests, PerceptionTests, PlaceTests; items 81, 83, 88, 93, 97 |
| CRAFTING (4) | `brew_potion`, `plan_brew`, `craft_item`, `get_recipe` | CraftingTests, BrewingTests; items 83, 94 |
| ENCHANT (3) | `enchant_item`, `anvil_item`, `grindstone_item` | EnchantSkillTests, AnvilSkillTests, GrindstoneSkillTests; item 94 |
| FARMING (4) | `farm`, `plan_farm`, `tend_farm`, `use_bonemeal` | FarmingTests; item 83 |
| FIRE (1) | `extinguish_fire` | FireTests; items 93, 101 |
| GATHERING (5) | `fell_tree`, `mine_blocks`, `mine_block_at`, `excavate`, `pickup_items` | ExcavationTests, MineBlocksTests, MiningTests, TreeFellingTests, DropPickupTests; items 80–83 |
| INFO (11) | `find_target`, `find_structure`, `search_structure`, `search_hint`, `find_trees`, `find_blocks`, `describe_block`, `find_placed`, `replan_plan`, `player_view`, `survey_area` | TargetSearchTests, StructureTests, OwnerViewTests, PerceptionTests, PlacedBlockTests, CompanionCoordinationTests; items 81–83 |
| INTERACTION (4) | `interact_entity`, `mount`, `dismount`, `throw_item` | InteractTests, ItemTaskTests; items 83, 95 |
| INVENTORY (4) | `give_items`, `drop_items`, `equip_item`, `show_inventory` | ItemTaskTests, InventoryWindowTests, MenuHandsTests; items 87, 88, 92 |
| MEMORY (13) | `use_skill`, `list_skills`, `skill_help`, `manage_skill`, `remember`, `forget`, `recall`, `read_log`, `logs_search`, `recent_chat`, `memory_overview`, `compress_memory`, `reset_manner` | MemoryTests, SelfKnowledgeTests, SkillsTests, MemoryMenuTests, LoggingTests; items 86, 91, 98 |
| MOVEMENT (11) | `follow_player`, `stay`, `go_to`, `travel_through_portal`, `jump`, `set_pace`, `step`, `back_away`, `approach`, `circle`, `set_follow_mode` | PathingTests, NavigatorStateTests, PaceTests, CompanionChunkLoadingTests; items 72, 79, 102 |
| OBTAIN (3) | `obtain_item`, `obtain_items`, `plan_obtain` | ObtainTests, ResourceSearchTests; items 80, 87, 88, 92 |
| PLACE (4) | `remember_place`, `go_to_place`, `rename_place`, `forget_place` | PlaceTests, ProtectionTests; item 83 |
| PLANNING (1) | `plan_validate` | PlanTests, PlannerTests, ConditionalPlansTests; items 81, 83, 93 |
| RESOURCE (2) | `search_resource`, `known_resources` | ResourceToolsTests, ResourceSearchTests, ResourceObservationTests, ResourceJourneyTests; items 76–82, 88 |
| STORAGE (6) | `store_items`, `take_items`, `check_container`, `deposit_items`, `move_items`, `find_stored_item` | StorageTests, InventoryWindowTests; item 83 |
| STRUCTURE (2) | `build_special`, `plan_special` | StructureTests, ExploreTests; item 83 |
| SURVIVAL (10) | `eat`, `get_food`, `sleep`, `recover_drops`, `dropped_items`, `forget_drops`, `allow_food`, `forbid_food`, `reset_food_rule`, `get_food_rules` | SelfCareTests, HungerTests, CompanionDeathTests; item 83 |
| TRADING (9) | `find_traders`, `inspect_trader`, `refresh_trades`, `wait_for_restock`, `level_trader`, `barter`, `known_trades`, `plan_trade`, `trade_item` | TradingToolsTests, TradingAuthorityTests, TradingTransactionTests, TradingTaskTests, TradingBarterTaskTests, TradingRefreshTests, TradingMenuTests; item 83 |
| TRUST (7) | `request_help`, `accept_help`, `decline_help`, `offer_handoff`, `accept_handoff`, `trust_player`, `untrust_player` | TrustTests, GuestTests, CompanionCoordinationTests, ProtocolAccessTests; items 83, 99 |

| Runtime or project surface | Evidence and follow-up |
|---|---|
| Engine policy, foundations and persistence | All engine JVM tests ran. Covers turn, queue, providers, memory, schema migration, protocol codecs and persistence tests. Items 71, 76, 81, 83–93 and 97. |
| Common and server adapters | All root JVM tests ran. Server GameTest registration was checked across 111 classes, and the filtered brewing suite booted Minecraft and passed its three fixtures. The table names other available world tests. |
| Client runtime, voice, rendering and UI | Root client tests ran. The isolated client run passed the eight locale/layout checks and timed out on the non-operator snapshot scenario. Native inference, device timing, remote-server use and appearance races have separate acceptance in items 75, 89, 96, 98 and 103. |
| Protocol, commands and access | Reviewed the dispatch, tool caller/visibility, negotiation and menu paths. `ProtocolAccessTests`, `TrustTests`, `CreativeCommandTests` and the feature suites are available for world-level verification. Items 91, 93, 96, 98, 99 and 103. |
| Build, localization, release and site | Shard inventory and JVM build wiring checked. The capability site JavaScript passed syntax checks and its tool cards were compared against the registry. The site comparison and client gate are tracked in items 91 and 98. |
