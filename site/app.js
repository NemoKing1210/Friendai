"use strict";

/* ================= DATA ================= */
// items: "name|description" is a tool the LLM can call; "~name|description" is a built-in behaviour.
const COLORS = {
  grass: "#5d9b3a", iron: "#a9b4bd", oak: "#b8864b", copper: "#d2754a", redstone: "#d23b2e",
  gold: "#f1b91c", emerald: "#1fb85a", amethyst: "#9b6ad6", diamond: "#35c9c9", lapis: "#3d62d8",
  quartz: "#e9e4d8", nether: "#a63a3a", sand: "#d8c37a", water: "#4a8fe0"
};

const DOMAINS = [
  { id: "hands", name: "Hands", icon: "✋", color: COLORS.grass, desc: "World skills: everything the companion physically does." },
  { id: "mind", name: "Mind", icon: "✨", color: COLORS.amethyst, desc: "Goals, plans, memory and places: how it decides and remembers." },
  { id: "senses", name: "Senses & Voice", icon: "👂", color: COLORS.water, desc: "How it perceives the world, hears you and talks back." }
];

const CATS = [
  { id: "movement", domain: "hands", name: "Movement", icon: "👟", color: COLORS.grass,
    desc: "Block-grid A* pathfinding that walks, falls, swims, climbs, digs, pillars and bridges within a per-tick time budget.",
    items: [
      "follow_player|Follow the owner at a chosen distance.", "stay|Hold the current position.", "go_to|Pathfind to coordinates.", "travel_through_portal|Walk through a lit portal, or build and light one from carried obsidian and flint; it records a return anchor.",
      "jump|Jump once.", "set_pace|Auto, walk or run pace.", "set_follow_mode|Choose whether it follows when idle.",
      "step|Move by a relative offset.", "back_away|Retreat from a target.", "approach|Walk up to a player, mob or block.",
      "circle|Circle around a point.", "set_follow_distance|How close it stays to the owner.",
      "~A* pathfinder|Incremental, resumable search that spreads work over ticks.", "~Dig, pillar, bridge|Breaks and places blocks when the way is blocked, and walks only a complete stair or bridge: it never stops half way up.",
      "~Stuck recovery|Re-plans when the world disagrees with the path." ] },
  { id: "mining", domain: "hands", name: "Mining & Gathering", icon: "⛏️", color: COLORS.iron,
    desc: "Collects raw materials: trees, ores, drops. Tool choice comes from item data components, never hard-coded tiers.",
    items: [
      "fell_tree|Fell a whole tree from the base when one stance reaches it, or from the crown; a partly felled tree reports the logs left standing.", "mine_blocks|Mine up to 64 blocks of a type in one call; wood comes from whole felled trees; searches once for a target you have not seen.", "mine_block_at|Mine the block you point at, follow its vein, and report what was taken.", "excavate|Mine a box, tunnel, stair, shaft or strip mine in one call, with drops and safety; a stair is carved as a walkable passage, fallback descents stay within the target depth, and uncleared solid cells are reported.",
      "pickup_items|Collect every drop in the radius in one call.", "find_trees|Scan for trees.", "find_blocks|Scan for blocks and ore veins.",
      "find_placed|List blocks the companion placed earlier.",
      "~Vein follower|Mines a whole vein, not one block.", "~Tool selection|Picks the best tool from the stack's tool component.", "~Safe digging|Checks support and hazards before approaching.",
      "~Catch platform|Builds a block under a valuable drop over lava, a hole or the void, mines, then takes it back." ] },
  { id: "building", domain: "hands", name: "Building", icon: "🧱", color: COLORS.oak,
    desc: "Places blocks through a fake player so vanilla rules and protection apply. Blueprints up to 32x32x32.",
    items: [
      "place_block|Place one block.", "use_block|Right-click a block like a player.", "light_area|Place torches to stop mob spawns.",
      "clear_area|Clear vegetation, snow and stray blocks, up to 256 cells per call.", "preview_structure|Show a ghost preview.", "build_structure|Build from a generated or saved layout.",
      "save_blueprint|Store a layout as JSON.", "list_blueprints|List saved blueprints.", "blueprint_help|Explain the blueprint format.",
      "build_special|Build an iron golem, snow golem or nether portal.", "plan_special|Dry-run a special structure.",
      "~Blueprint parser|Palette, ops and ASCII layers; reports every error at once.", "~Resumable builds|Progress survives restarts.", "~Fake-player hands|Respects world protection." ] },
  { id: "crafting", domain: "hands", name: "Crafting & Cooking", icon: "🔨", color: COLORS.copper,
    desc: "Recursive crafting plans (depth up to 5), furnace jobs and enchanting-station work through real menus.",
    items: [
      "craft_item|Craft an item, placing a table if needed.", "get_recipe|Look up how something is made.", "smelt_items|Smelt up to 256 items in one call with fuel.",
      "cook_food|Cook raw food.", "collect_smelted|Pick up finished furnace output.", "enchant_item|Use an enchanting table.",
      "anvil_item|Repair or combine at an anvil.", "grindstone_item|Strip enchantments at a grindstone.",
      "brew_potion|Brew a supported potion at a brewing stand.", "plan_brew|Show the brewing steps without doing them.",
      "~Craft planner|Turns a goal item into ordered steps.", "~Station leases|Remembers a table it placed for 600 ticks." ] },
  { id: "combat", domain: "hands", name: "Combat", icon: "⚔️", color: COLORS.redstone,
    desc: "Reflex combat ticked before everything else, with a single arbiter for movement, shields, bow, potions and retreat.",
    items: [
      "attack|Attack a chosen mob.", "attack_player|Attack a player (owner approval).",
      "get_combat_settings|Read the current style.", "set_combat_settings|Fighting style and retreat preference.",
      "~Shield timing|Raises on a draw or arrow, lowers to swing.", "~Bow aim with lead|Bounded predictive aim.", "~Potion use|Drinks the right potion at the right time.",
      "~Weapon choice|Scores weapons by DPS and durability.", "~Group pressure|Sidesteps away from a second attacker.", "~Hostiles target it|Mobs treat it like a player." ] },
  { id: "survival", domain: "hands", name: "Survival", icon: "🍖", color: COLORS.gold,
    desc: "A vanilla-like hunger model, food rules you control, sleep, and fire handling. It keeps what it carries when it dies.",
    items: [
      "eat|Eat suitable food.", "get_food|Gather food.", "allow_food|Allow an item as food.", "forbid_food|Never eat an item.",
      "reset_food_rule|Forget a food rule.", "get_food_rules|Show food rules.", "sleep|Sleep in a bed at night.", "extinguish_fire|Put fires out with a water bucket.",
      "~Hunger model|Food, saturation, exhaustion, regeneration, starvation.", "~Fire reflex|Reacts to nearby flames automatically.",
      "~Self-care reflex|Spends its own water bucket, milk, golden apples and potions to stay alive.",
      "~Death and respawn|Inventory and queue return with the new body." ] },
  { id: "farming", domain: "hands", name: "Farming & Animals", icon: "🌾", color: COLORS.emerald,
    desc: "Plans a field, plants, tends and harvests; works with animals and fishing.",
    items: [
      "farm|Plant and harvest a whole field in one pass.", "plan_farm|Preview what a farm would need.", "tend_farm|Keep an existing farm going.", "use_bonemeal|Speed up growth.",
      "breed_animals|Breed a species.", "shear|Shear sheep.", "milk|Milk up to 16 cows in one call.", "tame_animal|Tame an animal.", "lead_animals|Lead animals with a lead.",
      "harvest_honey|Collect honey.", "fish|Go fishing.", "~Farm planner|Cells, soil, crops, food need.", "~Re-plans on mismatch|Adjusts when the world differs from the plan." ] },
  { id: "inventory", domain: "hands", name: "Inventory & Storage", icon: "🎒", color: COLORS.amethyst,
    desc: "A 27-slot inventory plus six gear slots, shared container logic, and a bounded memory of containers the companion has opened.",
    items: [
      "get_inventory|List what it carries.", "show_inventory|Open the inventory view.", "equip_item|Equip gear.", "give_items|Hand items to a player; strict orders can reserve the companion's share and wait for a safe handover.",
      "drop_items|Drop items.", "throw_item|Throw an item.", "store_items|Store up to two stacks in one call.", "take_items|Take items out of a container.",
      "check_container|Look inside a container.", "deposit_items|Deposit into the nearest storage.",
      "find_stored_item|Report where you last saw an item in a recorded container.", "move_items|Move a known quantity between two containers in one call.",
      "~Equipment picker|Armor and weapon loadout.", "~Owner-only window|Within 8 blocks, same dimension." ] },
  { id: "acquire", domain: "hands", name: "Resource Acquisition", icon: "🧭", color: COLORS.diamond,
    desc: "Ask for an item and it works out how: inventory, chests, craft, smelt, mine, hunt, harvest, shear, milk or explore.",
    items: [
      "obtain_item|Get an item by any means; wood is felled as whole trees.", "obtain_items|Gather shared materials once for a set of items, then craft them all; accepts item/count aliases; mines a mineable item instead of crafting it from a rarer one; wood is felled as whole trees.", "plan_obtain|Show the plan without doing it.", "search_resource|Explore to find a resource.",
      "known_resources|Show up to three remembered resource sites with their age and uncertainty.", "search_hint|Give it a hint where to look.", "find_target|Resolve a search target.", "find_structure|Find a village, fortress or stronghold by markers.", "search_structure|Travel to a verified structure instance.",
      "~Obtain planner|Depth up to 8, nine step kinds.", "~Resource ranking|Prioritizes verified approaches, usable tools and lower estimated trip cost.", "~Safe return|Checks a route and support reserve before automatic descent.", "~Measured results|Reports carried, delivered and remaining item counts separately.", "~Frontier search|Biome edges, water, hazards and trails.", "~Search budget|Gives up sensibly." ] },
  { id: "trading", domain: "hands", name: "Trading", icon: "💎", color: COLORS.lapis,
    desc: "Villager and piglin trading with a pure policy and an authority check on spending.",
    items: [
      "find_traders|Locate villagers.", "inspect_trader|Read a trader's offers.", "refresh_trades|Re-check offers.", "wait_for_restock|Wait until stock returns.",
      "known_trades|List remembered trades.", "plan_trade|Plan a trade.", "trade_item|Execute a trade.", "level_trader|Level a villager up.", "barter|Barter with piglins.",
      "~Spend authority|Ephemeral spending rights are revalidated.", "~Idle refresh|Server refreshes traders while idle." ] },
  { id: "body", domain: "hands", name: "Body & Interaction", icon: "🙂", color: COLORS.quartz,
    desc: "Expression and contact: looking, gesturing, sitting, riding and using entities.",
    items: [
      "look_at|Look at a target.", "point_at|Point at something.", "turn|Turn the body.", "sneak|Sneak.", "emote|Play an emote.", "sit|Sit down.",
      "stand|Stand up.", "wait|Wait some ticks.", "swap_hands|Swap main and off hand.", "interact_entity|Use an entity.", "mount|Ride a mount.",
      "dismount|Get off.", "set_idle_behavior|What to do when idle.", "~Single facing policy|One writer for body yaw, head and pitch." ] },
  { id: "creative", domain: "hands", name: "Creative Mode", icon: "🌟", color: COLORS.sand,
    desc: "Gated abilities for creative worlds. The owner decides; the mode revision is checked on every action.",
    items: [
      "create_item|Spawn items (creative).", "fly_to|Fly to a point (creative).", "execute_command|Run a command from the allowlist.",
      "~Command allowlist|Only vetted commands run.", "~Owner-gated|Initiator and mode revision are checked.", "~Bounded counts|Safe arithmetic for item requests." ] },

  { id: "planning", domain: "mind", name: "Goals & Planning", icon: "🎯", color: COLORS.nether,
    desc: "The cognitive layer above the task queue: goals, a conditional plan queue, and free time when nobody is giving orders.",
    items: [
      "set_goal|Set the main goal (owner).", "update_goal|Update progress.", "finish_goal|Close a goal.", "queue_add|Add a step.",
      "queue_show|Show the queue.", "queue_edit|Edit queued steps.", "plan_help|Explain plan syntax.", "plan_validate|Dry-run a plan.",
      "request_tools|Unlock a tool group for this turn.", "stop|Stop everything.", "start_free_time|Begin autonomous time.", "end_free_time|End it.", "choose_activity|Pick what to do in free time.",
      "~LLM planner|Preliminary planning for complex orders; recovery for troubled plans, unmet goals and free time.", "~Conditional plans|If, repeat and guards over world facts.", "~Outcome checker|Compares before and after snapshots.",
      "~Recovery advisor|Classifies a failure and suggests a new remedy.", "~Quiet outcomes|Learns without narrating repeated status.", "~Shift reports|One line per blocker (missing support, an own place, a tree it cannot climb); a repeat is silent.",
      "~Speech gate|Irrelevant status stays silent; a new blocker gets one line; a player question is always answered." ] },
  { id: "memory", domain: "mind", name: "Memory & Trust", icon: "🧠", color: COLORS.amethyst,
    desc: "Per-owner memory with strict visibility: guests only see facts about themselves, and trust changes need the owner's confirmation.",
    items: [
      "remember|Store a fact.", "recall|Read facts back.", "forget|Delete a fact.", "trust_player|Raise a player to trusted (needs confirmation).", "request_help|Ask another owner's companion for bounded help.", "accept_help|Accept the named help only.", "decline_help|Decline with no transfer.", "offer_handoff|Offer an exact item quantity at a meeting point.", "accept_handoff|Accept only the named transfer.", "replan_plan|Ask the planner to repair one stalled plan.",
      "untrust_player|Lower trust.", "set_name|Rename the companion.", "use_skill|Load an instruction skill once.", "list_skills|List the owner's skills with ids, versions and on/off state.", "skill_help|Read the skill-authoring checklist and its limits.", "manage_skill|Propose a skill after a finished goal.",
      "read_log|Read your recent activity log (owner only).", "logs_search|Search the durable log files by level, category, event, turn, task, trace, execution or request (owner only).", "recent_chat|Read recent conversations with a player.", "memory_overview|See facts with ids and how full each book is.", "compress_memory|Merge duplicate facts safely, dry run first (owner only).", "reset_manner|Drop a manner the owner dislikes and never drift back into it (owner only).",
      "~Access levels|GUEST < TRUSTED < OWNER on every tool.", "~Language learning|Learns your language after a few samples.", "~Skills advise only|They never change permissions.",
      "~Opt-in retrieval|Default off; caller-visible facts selected by a local word match within a bounded budget." ] },
  { id: "places", domain: "mind", name: "Places", icon: "📍", color: COLORS.diamond,
    desc: "A book of named places. Autonomous work never breaks inside your places and takes only from containers in one; dig sites it registered itself are work areas.",
    items: [
      "remember_place|Save the current spot.", "go_to_place|Walk to a named place.", "rename_place|Rename it.", "forget_place|Delete it.", "find_place|Look one up.",
      "~Auto registration|Notes beds, bases and farms.", "~Place guard|Protects your places during free time; a farm it tends and its own dig sites are work areas.", "~Mine box|A remembered mine covers the blocks it dug, not the radius it searched.", "~Same dimension|go_to_place never crosses dimensions." ] },

  { id: "perception", domain: "senses", name: "Perception", icon: "👁️", color: COLORS.quartz,
    desc: "Turns the world into prompt text: surroundings, threats, landmarks and what the owner is looking at.",
    items: [
      "look_around|Describe the surroundings.", "player_view|What the owner sees, holds and wears.", "describe_block|Inspect one block.", "survey_area|Survey a region.",
      "~Brief radius 8|Cheap default perception.", "~Detailed radius 16|When more detail is needed.", "~Owner focus|Where the owner stands and looks, what both hands hold and which armor they wear.", "~Landmarks and area map|Relative directions and a compact map." ] },
  { id: "chat", domain: "senses", name: "Chat & Addressing", icon: "💬", color: COLORS.water,
    desc: "Decides who is being spoken to and which companion hears it.",
    items: [
      "~Address detection|Name or direct address.", "~Companion picker|Own within hearing range, else nearest other.", "~Reply splitter|Splits long replies into lines.",
      "~Trust confirmations|Owner confirms yes or no in chat.", "~/friendai commands|summon, dismiss, stop, trust, memory, skills, reload.", "~English and Russian|Interface and intent vocabularies." ] },
  { id: "voicein", domain: "senses", name: "Voice Input", icon: "🎤", color: COLORS.redstone,
    desc: "Push-to-talk speech recognition. Local audio never leaves the machine unless you pick a cloud provider.",
    items: [
      "~Push-to-talk|16 kHz, 16-bit, mono capture.", "~Local Whisper|tiny, base or small, size and SHA-256 checked.",
      "~Wake word|Off by default; an explicitly armed local detector, with bounded pre-wake audio.",
      "~Parakeet V3|25 European languages, offline.", "~Nemotron 3.5|Streaming partials, 19 locales.", "~GigaAM v3|Russian with punctuation, offline.",
      "~Verified bundles|Pinned revision, size and SHA-256, atomic install.", "~OpenAI STT|Cloud recognition.",
      "~Groq STT|Cloud recognition.", "~Yandex STT|Cloud recognition.", "~Key in headers|Keys never go in URLs or logs." ] },
  { id: "voiceout", domain: "senses", name: "Speech Output", icon: "🔊", color: COLORS.gold,
    desc: "Replies can be spoken locally or through a cloud voice, panned and attenuated by where the companion is.",
    items: [
      "~Streamed sentences|Ordered per stream and session, with one cut-off report.", "~Early sentence|The first safe sentence speaks before the provider finishes; the full reply stays authoritative.", "~Local Piper voices|Five catalogued voices via sherpa-onnx.", "~OpenAI TTS|Cloud speech.", "~ElevenLabs|Cloud speech.", "~Yandex SpeechKit|Cloud speech.",
      "~Spatial audio|Equal-power pan and distance level.", "~Stop speaking key|Cuts playback instantly.", "~Off by default|Opt in from settings." ] },
  { id: "menu", domain: "senses", name: "Companion Menu", icon: "🖥️", color: COLORS.iron,
    desc: "A tabbed control screen that pauses the companion while open. Operator-only tabs are filtered on the server.",
    items: [
      "~Overview|Status, health, hunger, task.", "~Toggles|Per-companion switches.", "~Tasks|Queue and history.", "~Memory|Browse and forget.", "~Skills|Instruction skills.",
      "~Places|Rename or forget places.", "~Map|Explored cells, sites and roads, never the full grid.", "~Plan controls|Pause, resume or defer the first plan from the menu.", "~Appearance|Name and custom skin.", "~Traders|Known trades.", "~Log and Debug|Operator-only brain log with trace and execution prefixes, plus the incident bundle.", "~Settings screen|YACL, optional." ] }
];

/* ================= CODE LOCATIONS ================= */
// Where a skill lives in the repository, keyed by tool name, "~trait" or a category id.
// Shown in the hover tooltip; paths are relative to the repository root.
const CODE = {
  "movement": ["src/main/java/dev/friendai/features/navigation/minecraft/tool/MovementTools.java", "src/main/java/dev/friendai/world/movement/Navigator.java"],
  "mining": ["src/main/java/dev/friendai/features/mining/minecraft/tool/GatheringTools.java", "src/main/java/dev/friendai/features/mining/minecraft/tool/ExcavationTools.java"],
  "building": ["src/main/java/dev/friendai/features/construction/minecraft/tool/BuildingTools.java"],
  "crafting": ["src/main/java/dev/friendai/features/crafting/minecraft/tool/CraftingTools.java", "src/main/java/dev/friendai/features/cooking/minecraft/tool/CookingTools.java"],
  "combat": ["src/main/java/dev/friendai/features/combat/minecraft/CombatController.java", "src/main/java/dev/friendai/features/combat/minecraft/tool/CombatTools.java"],
  "survival": ["src/main/java/dev/friendai/features/survival/minecraft/tool/SurvivalTools.java", "engine/src/main/java/dev/friendai/foundation/body/HungerModel.java"],
  "farming": ["src/main/java/dev/friendai/features/farming/minecraft/tool/FarmingTools.java"],
  "inventory": ["src/main/java/dev/friendai/features/inventory/minecraft/tool/InventoryTools.java", "src/main/java/dev/friendai/features/inventory/minecraft/tool/StorageTools.java"],
  "acquire": ["src/main/java/dev/friendai/features/acquisition/minecraft/tool/ObtainTools.java", "engine/src/main/java/dev/friendai/features/acquisition/ObtainPlanner.java"],
  "trading": ["src/main/java/dev/friendai/features/trading/minecraft/tool/TradingTools.java"],
  "body": ["src/main/java/dev/friendai/features/interaction/minecraft/tool/BodyTools.java"],
  "creative": ["src/main/java/dev/friendai/features/creative/minecraft/tool/CreativeTools.java"],
  "planning": ["engine/src/main/java/dev/friendai/agent/plan/LlmPlanner.java", "src/main/java/dev/friendai/server/execution/tools/QueueTools.java"],
  "memory": ["src/main/java/dev/friendai/features/memory/minecraft/tool/MemoryTools.java"],
  "places": ["src/main/java/dev/friendai/features/places/minecraft/tool/PlaceTools.java"],
  "perception": ["src/main/java/dev/friendai/server/agent/tools/PerceptionTools.java"],
  "chat": ["src/main/java/dev/friendai/server/agent/chat/ChatRouter.java"],
  "voicein": ["src/client/java/dev/friendai/client/voice/VoiceRuntime.java"],
  "voiceout": ["src/client/java/dev/friendai/client/tts/SpeechRuntime.java"],
  "menu": ["src/client/java/dev/friendai/client/ui/CompanionControlScreen.java"],
  "follow_player": ["src/main/java/dev/friendai/features/navigation/minecraft/tool/MovementTools.java", "src/main/java/dev/friendai/features/navigation/minecraft/task/FollowTask.java"],
  "stay": ["src/main/java/dev/friendai/features/navigation/minecraft/tool/MovementTools.java", "src/main/java/dev/friendai/features/navigation/minecraft/task/StayTask.java"],
  "go_to": ["src/main/java/dev/friendai/features/navigation/minecraft/tool/MovementTools.java", "src/main/java/dev/friendai/features/navigation/minecraft/task/GoToTask.java"],
  "jump": ["src/main/java/dev/friendai/features/navigation/minecraft/tool/MovementTools.java", "src/main/java/dev/friendai/features/navigation/minecraft/task/JumpTask.java"],
  "set_pace": ["src/main/java/dev/friendai/features/navigation/minecraft/tool/MovementTools.java"],
  "set_follow_mode": ["src/main/java/dev/friendai/server/execution/tools/ControlTools.java"],
  "step": ["src/main/java/dev/friendai/features/navigation/minecraft/tool/RelativeMovementTools.java", "src/main/java/dev/friendai/features/navigation/minecraft/task/RelativeMoveTask.java"],
  "back_away": ["src/main/java/dev/friendai/features/navigation/minecraft/tool/RelativeMovementTools.java", "src/main/java/dev/friendai/features/navigation/minecraft/task/RelativeMoveTask.java"],
  "approach": ["src/main/java/dev/friendai/features/navigation/minecraft/tool/RelativeMovementTools.java", "src/main/java/dev/friendai/features/navigation/minecraft/task/ApproachEntityTask.java"],
  "circle": ["src/main/java/dev/friendai/features/navigation/minecraft/tool/RelativeMovementTools.java", "src/main/java/dev/friendai/features/navigation/minecraft/task/CircleTask.java"],
  "set_follow_distance": ["src/main/java/dev/friendai/features/interaction/minecraft/tool/BodyTools.java"],
  "fell_tree": ["src/main/java/dev/friendai/features/mining/minecraft/tool/GatheringTools.java", "src/main/java/dev/friendai/features/mining/minecraft/task/FellTreeTask.java"],
  "mine_blocks": ["src/main/java/dev/friendai/features/mining/minecraft/tool/GatheringTools.java", "src/main/java/dev/friendai/features/mining/minecraft/task/MineBlocksTask.java"],
  "mine_block_at": ["src/main/java/dev/friendai/features/mining/minecraft/tool/GatheringTools.java", "src/main/java/dev/friendai/features/mining/minecraft/task/MineBlockAtTask.java"],
  "excavate": ["src/main/java/dev/friendai/features/mining/minecraft/tool/ExcavationTools.java", "src/main/java/dev/friendai/features/mining/minecraft/task/ExcavateTask.java", "engine/src/main/java/dev/friendai/features/mining/excavation/ExcavationGeometry.java"],
  "pickup_items": ["src/main/java/dev/friendai/features/inventory/minecraft/tool/InventoryTools.java", "src/main/java/dev/friendai/features/inventory/minecraft/task/PickupItemsTask.java"],
  "find_trees": ["src/main/java/dev/friendai/features/mining/minecraft/tool/BlockInfoTools.java"],
  "find_blocks": ["src/main/java/dev/friendai/features/mining/minecraft/tool/BlockInfoTools.java"],
  "find_placed": ["src/main/java/dev/friendai/features/placements/minecraft/tool/PlacedTools.java"],
  "place_block": ["src/main/java/dev/friendai/features/construction/minecraft/tool/BuildingTools.java", "src/main/java/dev/friendai/features/construction/minecraft/task/PlaceBlockTask.java"],
  "use_block": ["src/main/java/dev/friendai/features/interaction/minecraft/tool/InteractionTools.java", "src/main/java/dev/friendai/features/interaction/minecraft/task/UseBlockTask.java"],
  "light_area": ["src/main/java/dev/friendai/features/construction/minecraft/tool/BuildingTools.java", "src/main/java/dev/friendai/features/construction/minecraft/task/LightAreaTask.java"],
  "clear_area": ["src/main/java/dev/friendai/features/construction/minecraft/tool/BuildingTools.java", "src/main/java/dev/friendai/features/construction/minecraft/task/ClearAreaTask.java"],
  "preview_structure": ["src/main/java/dev/friendai/features/construction/minecraft/tool/BuildTools.java"],
  "build_structure": ["src/main/java/dev/friendai/features/construction/minecraft/tool/BuildTools.java", "src/main/java/dev/friendai/features/construction/minecraft/task/BuildTask.java"],
  "save_blueprint": ["src/main/java/dev/friendai/features/construction/minecraft/tool/BuildTools.java"],
  "list_blueprints": ["src/main/java/dev/friendai/features/construction/minecraft/tool/BuildTools.java"],
  "blueprint_help": ["src/main/java/dev/friendai/features/construction/minecraft/tool/BuildTools.java"],
  "build_special": ["src/main/java/dev/friendai/features/construction/minecraft/tool/StructureTools.java", "src/main/java/dev/friendai/features/construction/minecraft/task/SpecialStructureTask.java"],
  "plan_special": ["src/main/java/dev/friendai/features/construction/minecraft/tool/StructureTools.java"],
  "craft_item": ["src/main/java/dev/friendai/features/crafting/minecraft/tool/CraftingTools.java", "src/main/java/dev/friendai/features/crafting/minecraft/task/CraftTask.java"],
  "get_recipe": ["src/main/java/dev/friendai/features/crafting/minecraft/tool/CraftingTools.java"],
  "smelt_items": ["src/main/java/dev/friendai/features/cooking/minecraft/tool/CookingTools.java", "src/main/java/dev/friendai/features/cooking/minecraft/task/SmeltTask.java"],
  "cook_food": ["src/main/java/dev/friendai/features/cooking/minecraft/tool/CookingTools.java", "src/main/java/dev/friendai/features/cooking/minecraft/task/CookFoodTask.java"],
  "collect_smelted": ["src/main/java/dev/friendai/features/cooking/minecraft/tool/CookingTools.java", "src/main/java/dev/friendai/features/cooking/minecraft/task/CollectSmeltedTask.java"],
  "enchant_item": ["src/main/java/dev/friendai/features/enchanting/minecraft/tool/EnchantTools.java", "src/main/java/dev/friendai/features/enchanting/minecraft/task/EnchantItemTask.java"],
  "anvil_item": ["src/main/java/dev/friendai/features/enchanting/minecraft/tool/EnchantTools.java", "src/main/java/dev/friendai/features/enchanting/minecraft/task/AnvilItemTask.java"],
  "grindstone_item": ["src/main/java/dev/friendai/features/enchanting/minecraft/tool/EnchantTools.java", "src/main/java/dev/friendai/features/enchanting/minecraft/task/GrindstoneItemTask.java"],
  "attack": ["src/main/java/dev/friendai/features/combat/minecraft/tool/CombatTools.java", "src/main/java/dev/friendai/features/combat/minecraft/task/HuntTask.java"],
  "attack_player": ["src/main/java/dev/friendai/features/combat/minecraft/tool/CombatTools.java", "src/main/java/dev/friendai/features/combat/minecraft/task/AttackPlayerTask.java"],
  "get_combat_settings": ["src/main/java/dev/friendai/features/combat/minecraft/tool/CombatTools.java"],
  "set_combat_settings": ["src/main/java/dev/friendai/features/combat/minecraft/tool/CombatTools.java"],
  "eat": ["src/main/java/dev/friendai/features/survival/minecraft/tool/SurvivalTools.java", "src/main/java/dev/friendai/features/survival/minecraft/task/EatTask.java"],
  "get_food": ["src/main/java/dev/friendai/features/survival/minecraft/tool/SurvivalTools.java", "src/main/java/dev/friendai/features/survival/minecraft/task/GetFoodTask.java"],
  "allow_food": ["src/main/java/dev/friendai/features/survival/minecraft/tool/SurvivalTools.java"],
  "forbid_food": ["src/main/java/dev/friendai/features/survival/minecraft/tool/SurvivalTools.java"],
  "reset_food_rule": ["src/main/java/dev/friendai/features/survival/minecraft/tool/SurvivalTools.java"],
  "get_food_rules": ["src/main/java/dev/friendai/features/survival/minecraft/tool/SurvivalTools.java"],
  "sleep": ["src/main/java/dev/friendai/features/survival/minecraft/tool/SurvivalTools.java", "src/main/java/dev/friendai/features/survival/minecraft/task/SleepTask.java"],
  "extinguish_fire": ["src/main/java/dev/friendai/features/fire/minecraft/tool/FireTools.java", "src/main/java/dev/friendai/features/fire/minecraft/task/ExtinguishFireTask.java"],
  "farm": ["src/main/java/dev/friendai/features/farming/minecraft/tool/FarmingTools.java", "src/main/java/dev/friendai/features/farming/minecraft/task/FarmTask.java"],
  "plan_farm": ["src/main/java/dev/friendai/features/farming/minecraft/tool/FarmingTools.java"],
  "tend_farm": ["src/main/java/dev/friendai/features/farming/minecraft/tool/FarmingTools.java", "src/main/java/dev/friendai/features/farming/minecraft/task/TendFarmTask.java"],
  "use_bonemeal": ["src/main/java/dev/friendai/features/farming/minecraft/tool/FarmingTools.java", "src/main/java/dev/friendai/features/farming/minecraft/task/BonemealTask.java"],
  "breed_animals": ["src/main/java/dev/friendai/features/animals/minecraft/tool/AnimalTools.java", "src/main/java/dev/friendai/features/animals/minecraft/task/BreedTask.java"],
  "shear": ["src/main/java/dev/friendai/features/animals/minecraft/tool/AnimalTools.java", "src/main/java/dev/friendai/features/animals/minecraft/task/ShearTask.java"],
  "milk": ["src/main/java/dev/friendai/features/animals/minecraft/tool/AnimalTools.java", "src/main/java/dev/friendai/features/animals/minecraft/task/MilkTask.java"],
  "tame_animal": ["src/main/java/dev/friendai/features/animals/minecraft/tool/AnimalTools.java", "src/main/java/dev/friendai/features/animals/minecraft/task/TameTask.java"],
  "lead_animals": ["src/main/java/dev/friendai/features/animals/minecraft/tool/AnimalTools.java", "src/main/java/dev/friendai/features/animals/minecraft/task/LeadTask.java"],
  "harvest_honey": ["src/main/java/dev/friendai/features/animals/minecraft/tool/AnimalTools.java", "src/main/java/dev/friendai/features/animals/minecraft/task/HoneyTask.java"],
  "fish": ["src/main/java/dev/friendai/features/animals/minecraft/tool/AnimalTools.java", "src/main/java/dev/friendai/features/animals/minecraft/task/FishTask.java"],
  "get_inventory": ["src/main/java/dev/friendai/features/inventory/minecraft/tool/InventoryTools.java"],
  "show_inventory": ["src/main/java/dev/friendai/server/execution/tools/ControlTools.java"],
  "equip_item": ["src/main/java/dev/friendai/features/inventory/minecraft/tool/InventoryTools.java", "src/main/java/dev/friendai/features/inventory/minecraft/task/EquipTask.java"],
  "give_items": ["src/main/java/dev/friendai/features/inventory/minecraft/tool/InventoryTools.java", "src/main/java/dev/friendai/features/inventory/minecraft/task/GiveItemsTask.java"],
  "drop_items": ["src/main/java/dev/friendai/features/inventory/minecraft/tool/InventoryTools.java", "src/main/java/dev/friendai/features/inventory/minecraft/task/DropItemsTask.java"],
  "throw_item": ["src/main/java/dev/friendai/features/inventory/minecraft/tool/InventoryTools.java", "src/main/java/dev/friendai/features/inventory/minecraft/task/ThrowItemTask.java"],
  "store_items": ["src/main/java/dev/friendai/features/inventory/minecraft/tool/StorageTools.java", "src/main/java/dev/friendai/features/inventory/minecraft/task/StoreItemsTask.java"],
  "take_items": ["src/main/java/dev/friendai/features/inventory/minecraft/tool/StorageTools.java", "src/main/java/dev/friendai/features/inventory/minecraft/task/TakeItemsTask.java"],
  "check_container": ["src/main/java/dev/friendai/features/inventory/minecraft/tool/StorageTools.java", "src/main/java/dev/friendai/features/inventory/minecraft/task/CheckContainerTask.java"],
  "find_stored_item": ["src/main/java/dev/friendai/features/inventory/minecraft/tool/StorageTools.java", "src/main/java/dev/friendai/features/inventory/minecraft/storage/StorageIndexService.java"],
  "move_items": ["src/main/java/dev/friendai/features/inventory/minecraft/task/MoveItemsTask.java"],
  "deposit_items": ["src/main/java/dev/friendai/features/inventory/minecraft/tool/StorageTools.java", "src/main/java/dev/friendai/features/inventory/minecraft/task/DepositTask.java"],
  "obtain_item": ["src/main/java/dev/friendai/features/acquisition/minecraft/tool/ObtainTools.java", "src/main/java/dev/friendai/features/acquisition/minecraft/task/ObtainTask.java"],
  "obtain_items": ["src/main/java/dev/friendai/features/acquisition/minecraft/tool/ObtainTools.java", "src/main/java/dev/friendai/features/acquisition/minecraft/task/BatchObtainTask.java"],
  "plan_obtain": ["src/main/java/dev/friendai/features/acquisition/minecraft/tool/ObtainTools.java"],
  "search_resource": ["src/main/java/dev/friendai/features/acquisition/minecraft/tool/ResourceTools.java", "src/main/java/dev/friendai/features/acquisition/minecraft/task/SearchResourceTask.java"],
  "known_resources": ["src/main/java/dev/friendai/features/acquisition/minecraft/tool/ResourceTools.java"],
  "search_hint": ["src/main/java/dev/friendai/features/acquisition/minecraft/tool/ResourceTools.java"],
  "find_target": ["src/main/java/dev/friendai/features/acquisition/minecraft/tool/ResourceTools.java"],
  "find_structure": ["src/main/java/dev/friendai/features/acquisition/minecraft/tool/ResourceTools.java"],
  "search_structure": ["src/main/java/dev/friendai/features/acquisition/minecraft/tool/ResourceTools.java"],
  "find_traders": ["src/main/java/dev/friendai/features/trading/minecraft/tool/TradingTools.java"],
  "inspect_trader": ["src/main/java/dev/friendai/features/trading/minecraft/tool/TradingTools.java", "src/main/java/dev/friendai/features/trading/minecraft/task/InspectTraderTask.java"],
  "refresh_trades": ["src/main/java/dev/friendai/features/trading/minecraft/tool/TradingTools.java", "src/main/java/dev/friendai/features/trading/minecraft/task/RefreshTradesTask.java"],
  "wait_for_restock": ["src/main/java/dev/friendai/features/trading/minecraft/tool/TradingTools.java", "src/main/java/dev/friendai/features/trading/minecraft/task/WaitForRestockTask.java"],
  "known_trades": ["src/main/java/dev/friendai/features/trading/minecraft/tool/TradingTools.java"],
  "plan_trade": ["src/main/java/dev/friendai/features/trading/minecraft/tool/TradingTools.java"],
  "trade_item": ["src/main/java/dev/friendai/features/trading/minecraft/tool/TradingTools.java", "src/main/java/dev/friendai/features/trading/minecraft/task/TradeTask.java"],
  "level_trader": ["src/main/java/dev/friendai/features/trading/minecraft/tool/TradingTools.java", "src/main/java/dev/friendai/features/trading/minecraft/task/LevelTraderTask.java"],
  "barter": ["src/main/java/dev/friendai/features/trading/minecraft/tool/TradingTools.java", "src/main/java/dev/friendai/features/trading/minecraft/task/BarterTask.java"],
  "look_at": ["src/main/java/dev/friendai/features/interaction/minecraft/tool/BodyTools.java"],
  "point_at": ["src/main/java/dev/friendai/features/interaction/minecraft/tool/BodyTools.java"],
  "turn": ["src/main/java/dev/friendai/features/interaction/minecraft/tool/BodyTools.java", "src/main/java/dev/friendai/features/interaction/minecraft/task/TurnTask.java"],
  "sneak": ["src/main/java/dev/friendai/features/interaction/minecraft/tool/BodyTools.java"],
  "emote": ["src/main/java/dev/friendai/features/interaction/minecraft/tool/BodyTools.java", "src/main/java/dev/friendai/features/interaction/minecraft/task/EmoteTask.java"],
  "sit": ["src/main/java/dev/friendai/features/interaction/minecraft/tool/BodyTools.java", "src/main/java/dev/friendai/features/interaction/minecraft/task/SitTask.java"],
  "stand": ["src/main/java/dev/friendai/features/interaction/minecraft/tool/BodyTools.java"],
  "wait": ["src/main/java/dev/friendai/features/interaction/minecraft/tool/BodyTools.java", "src/main/java/dev/friendai/features/interaction/minecraft/task/WaitTask.java"],
  "swap_hands": ["src/main/java/dev/friendai/features/interaction/minecraft/tool/BodyTools.java"],
  "interact_entity": ["src/main/java/dev/friendai/features/interaction/minecraft/tool/InteractionTools.java", "src/main/java/dev/friendai/features/interaction/minecraft/task/InteractEntityTask.java"],
  "mount": ["src/main/java/dev/friendai/features/interaction/minecraft/tool/InteractionTools.java", "src/main/java/dev/friendai/features/interaction/minecraft/task/MountTask.java"],
  "dismount": ["src/main/java/dev/friendai/features/interaction/minecraft/tool/InteractionTools.java"],
  "set_idle_behavior": ["src/main/java/dev/friendai/features/interaction/minecraft/tool/BodyTools.java"],
  "create_item": ["src/main/java/dev/friendai/features/creative/minecraft/tool/CreativeTools.java", "src/main/java/dev/friendai/features/creative/minecraft/task/CreateItemsTask.java"],
  "fly_to": ["src/main/java/dev/friendai/features/navigation/minecraft/tool/FlightTools.java", "src/main/java/dev/friendai/features/navigation/minecraft/task/FlyToTask.java"],
  "execute_command": ["src/main/java/dev/friendai/features/interaction/minecraft/tool/CommandTools.java", "src/main/java/dev/friendai/features/interaction/minecraft/task/ExecuteCommandTask.java"],
  "set_goal": ["src/main/java/dev/friendai/server/agent/tools/GoalTools.java"],
  "update_goal": ["src/main/java/dev/friendai/server/agent/tools/GoalTools.java"],
  "finish_goal": ["src/main/java/dev/friendai/server/agent/tools/GoalTools.java"],
  "queue_add": ["src/main/java/dev/friendai/server/execution/tools/QueueTools.java"],
  "queue_show": ["src/main/java/dev/friendai/server/execution/tools/QueueTools.java"],
  "queue_edit": ["src/main/java/dev/friendai/server/execution/tools/QueueTools.java"],
  "plan_help": ["src/main/java/dev/friendai/server/execution/tools/QueueTools.java"],
  "plan_validate": ["src/main/java/dev/friendai/server/execution/tools/QueueTools.java"],
  "request_tools": ["src/main/java/dev/friendai/server/agent/tools/RoutingTools.java"],
  "stop": ["src/main/java/dev/friendai/server/execution/tools/ControlTools.java"],
  "start_free_time": ["src/main/java/dev/friendai/server/agent/tools/FreeTimeTools.java"],
  "end_free_time": ["src/main/java/dev/friendai/server/agent/tools/FreeTimeTools.java"],
  "choose_activity": ["src/main/java/dev/friendai/server/agent/tools/FreeTimeTools.java"],
  "remember": ["src/main/java/dev/friendai/features/memory/minecraft/tool/MemoryTools.java"],
  "recall": ["src/main/java/dev/friendai/features/memory/minecraft/tool/MemoryTools.java"],
  "forget": ["src/main/java/dev/friendai/features/memory/minecraft/tool/MemoryTools.java"],
  "trust_player": ["src/main/java/dev/friendai/features/memory/minecraft/tool/TrustTools.java"],
  "request_help": ["src/main/java/dev/friendai/features/coordination/minecraft/tool/CoordinationTools.java"],
  "accept_help": ["src/main/java/dev/friendai/features/coordination/minecraft/tool/CoordinationTools.java"],
  "decline_help": ["src/main/java/dev/friendai/features/coordination/minecraft/tool/CoordinationTools.java"],
  "offer_handoff": ["src/main/java/dev/friendai/features/coordination/minecraft/tool/CoordinationTools.java"],
  "accept_handoff": ["src/main/java/dev/friendai/features/coordination/minecraft/tool/CoordinationTools.java"],
  "replan_plan": ["src/main/java/dev/friendai/features/coordination/minecraft/tool/CoordinationTools.java"],
  "untrust_player": ["src/main/java/dev/friendai/features/memory/minecraft/tool/TrustTools.java"],
  "set_name": ["src/main/java/dev/friendai/server/execution/tools/ControlTools.java"],
  "use_skill": ["src/main/java/dev/friendai/features/instructions/minecraft/tool/SkillTools.java"],
  "list_skills": ["src/main/java/dev/friendai/features/instructions/minecraft/tool/SkillTools.java"],
  "skill_help": ["src/main/java/dev/friendai/features/instructions/minecraft/tool/SkillTools.java"],
  "manage_skill": ["src/main/java/dev/friendai/features/instructions/minecraft/tool/SkillTools.java"],
  "read_log": ["src/main/java/dev/friendai/features/memory/minecraft/tool/SelfTools.java"],
  "logs_search": ["engine/src/main/java/dev/friendai/agent/turn/LogSearch.java", "src/main/java/dev/friendai/features/memory/minecraft/tool/SelfTools.java"],
  "recent_chat": ["src/main/java/dev/friendai/features/memory/minecraft/tool/SelfTools.java"],
  "memory_overview": ["src/main/java/dev/friendai/features/memory/minecraft/tool/SelfTools.java"],
  "compress_memory": ["src/main/java/dev/friendai/features/memory/minecraft/tool/SelfTools.java"],
  "reset_manner": ["src/main/java/dev/friendai/features/memory/minecraft/tool/SelfTools.java"],
  "remember_place": ["src/main/java/dev/friendai/features/places/minecraft/tool/PlaceTools.java"],
  "go_to_place": ["src/main/java/dev/friendai/features/places/minecraft/tool/PlaceTools.java", "src/main/java/dev/friendai/features/places/minecraft/task/GoToPlaceTask.java"],
  "rename_place": ["src/main/java/dev/friendai/features/places/minecraft/tool/PlaceTools.java"],
  "forget_place": ["src/main/java/dev/friendai/features/places/minecraft/tool/PlaceTools.java"],
  "find_place": ["src/main/java/dev/friendai/features/places/minecraft/tool/PlaceTools.java"],
  "look_around": ["src/main/java/dev/friendai/server/agent/tools/PerceptionTools.java"],
  "player_view": ["src/main/java/dev/friendai/server/agent/tools/PerceptionTools.java"],
  "describe_block": ["src/main/java/dev/friendai/features/mining/minecraft/tool/BlockInfoTools.java"],
  "survey_area": ["src/main/java/dev/friendai/server/agent/tools/PerceptionTools.java"],
  "~A* pathfinder": ["engine/src/main/java/dev/friendai/foundation/spatial/PathSearch.java"],
  "~Dig, pillar, bridge": ["engine/src/main/java/dev/friendai/foundation/spatial/MoveGenerator.java"],
  "~Stuck recovery": ["src/main/java/dev/friendai/world/movement/PathExecutor.java"],
  "~Vein follower": ["engine/src/main/java/dev/friendai/features/mining/ClusterFill.java", "src/main/java/dev/friendai/features/mining/minecraft/task/MineBlockAtTask.java"],
  "~Tool selection": ["src/main/java/dev/friendai/world/block/MiningToolView.java"],
  "~Safe digging": ["engine/src/main/java/dev/friendai/features/mining/MiningSafety.java"],
  "~Catch platform": ["src/main/java/dev/friendai/features/mining/minecraft/mining/CatchPlatform.java"],
  "~Blueprint parser": ["engine/src/main/java/dev/friendai/features/construction/blueprint/BlueprintParser.java"],
  "~Resumable builds": ["src/main/java/dev/friendai/features/construction/minecraft/task/BuildTask.java"],
  "~Fake-player hands": ["src/main/java/dev/friendai/task/interact/CompanionHands.java"],
  "~Craft planner": ["engine/src/main/java/dev/friendai/features/crafting/CraftPlanner.java"],
  "~Station leases": ["engine/src/main/java/dev/friendai/features/crafting/StationLease.java"],
  "~Shield timing": ["engine/src/main/java/dev/friendai/features/combat/ShieldPolicy.java"],
  "~Bow aim with lead": ["engine/src/main/java/dev/friendai/features/combat/CombatPolicy.java"],
  "~Potion use": ["engine/src/main/java/dev/friendai/features/combat/PotionPolicy.java"],
  "~Weapon choice": ["engine/src/main/java/dev/friendai/features/combat/WeaponPolicy.java"],
  "~Group pressure": ["engine/src/main/java/dev/friendai/features/combat/GroupPressure.java"],
  "~Hostiles target it": ["src/main/java/dev/friendai/mixin/NearestAttackableTargetGoalMixin.java"],
  "~Hunger model": ["engine/src/main/java/dev/friendai/foundation/body/HungerModel.java"],
  "~Fire reflex": ["src/main/java/dev/friendai/features/fire/minecraft/FireReflex.java"],
  "~Self-care reflex": ["src/main/java/dev/friendai/features/survival/minecraft/selfcare/SelfCareReflex.java"],
  "~Death and respawn": ["src/main/java/dev/friendai/entity/CompanionManager.java"],
  "~Farm planner": ["engine/src/main/java/dev/friendai/features/farming/FarmPlanner.java"],
  "~Re-plans on mismatch": ["src/main/java/dev/friendai/features/farming/minecraft/FarmPass.java"],
  "~Equipment picker": ["src/main/java/dev/friendai/world/equipment/Equipment.java"],
  "~Owner-only window": ["src/main/java/dev/friendai/menu/FriendaiMenus.java"],
  "~Obtain planner": ["engine/src/main/java/dev/friendai/features/acquisition/ObtainPlanner.java"],
  "~Frontier search": ["src/main/java/dev/friendai/features/acquisition/minecraft/search/SearchFrontierFacts.java"],
  "~Search budget": ["src/main/java/dev/friendai/features/acquisition/minecraft/task/ResourceSearchDriver.java"],
  "~Spend authority": ["engine/src/main/java/dev/friendai/features/trading/TradeAuthority.java"],
  "~Idle refresh": ["src/main/java/dev/friendai/server/trading/TraderRefreshService.java"],
  "~Single facing policy": ["engine/src/main/java/dev/friendai/foundation/movement/Facing.java", "src/main/java/dev/friendai/entity/CompanionFacing.java"],
  "~Command allowlist": ["engine/src/main/java/dev/friendai/features/creative/CreativeCommandPolicy.java"],
  "~Owner-gated": ["engine/src/main/java/dev/friendai/features/creative/CreativePolicy.java"],
  "~Bounded counts": ["engine/src/main/java/dev/friendai/features/creative/CreativeItemCount.java"],
  "~LLM planner": ["engine/src/main/java/dev/friendai/agent/plan/LlmPlanner.java"],
  "~Conditional plans": ["engine/src/main/java/dev/friendai/execution/queue/PlanInterpreter.java"],
  "~Outcome checker": ["engine/src/main/java/dev/friendai/agent/verify/OutcomeChecker.java"],
  "~Recovery advisor": ["engine/src/main/java/dev/friendai/agent/recover/RecoveryAdvisor.java"],
  "~Access levels": ["engine/src/main/java/dev/friendai/execution/tool/AccessLevel.java"],
  "~Language learning": ["engine/src/main/java/dev/friendai/foundation/text/LanguageMemory.java"],
  "~Skills advise only": ["src/main/java/dev/friendai/server/instructions/SkillService.java"],
  "~Auto registration": ["engine/src/main/java/dev/friendai/features/places/PlaceAutoRegistration.java"],
  "~Place guard": ["src/main/java/dev/friendai/task/autonomy/PlaceGuard.java", "engine/src/main/java/dev/friendai/foundation/places/PlaceProtection.java"],
  "~Same dimension": ["src/main/java/dev/friendai/features/places/minecraft/task/GoToPlaceTask.java"],
  "~Mine box": ["engine/src/main/java/dev/friendai/features/places/PlaceAutoRegistration.java"],
  "~Shift reports": ["engine/src/main/java/dev/friendai/agent/autonomy/FreeTimeText.java", "engine/src/main/java/dev/friendai/agent/autonomy/ShiftBlocker.java"],
  "~Brief radius 8": ["src/main/java/dev/friendai/server/agent/perception/PerceptionBuilder.java"],
  "~Detailed radius 16": ["src/main/java/dev/friendai/server/agent/perception/PerceptionBuilder.java"],
  "~Owner focus": ["src/main/java/dev/friendai/server/agent/perception/OwnerFocusBuilder.java"],
  "~Landmarks and area map": ["src/main/java/dev/friendai/server/agent/perception/AreaMapBuilder.java", "src/main/java/dev/friendai/features/knowledge/minecraft/LandmarkScanner.java"],
  "~Address detection": ["engine/src/main/java/dev/friendai/features/conversation/AddressDetector.java"],
  "~Companion picker": ["engine/src/main/java/dev/friendai/features/conversation/CompanionPicker.java"],
  "~Reply splitter": ["engine/src/main/java/dev/friendai/features/conversation/ReplySplitter.java"],
  "~Trust confirmations": ["src/main/java/dev/friendai/server/memory/TrustConfirmations.java"],
  "~/friendai commands": ["src/main/java/dev/friendai/command/FriendaiCommands.java"],
  "~English and Russian": ["engine/src/main/resources/agent/intents_en.json", "engine/src/main/resources/agent/intents_ru.json"],
  "~Push-to-talk": ["src/client/java/dev/friendai/client/voice/PushToTalkHandler.java"],
  "~Local Whisper": ["src/client/java/dev/friendai/client/voice/WhisperLocalStt.java"],
  "~Parakeet V3": ["engine/src/main/java/dev/friendai/features/speech/stt/LocalSttCatalog.java", "src/client/java/dev/friendai/client/voice/SherpaStt.java"],
  "~Nemotron 3.5": ["engine/src/main/java/dev/friendai/features/speech/stt/LocalSttCatalog.java", "src/client/java/dev/friendai/client/voice/SherpaStreamingSession.java"],
  "~GigaAM v3": ["engine/src/main/java/dev/friendai/features/speech/stt/LocalSttCatalog.java", "src/client/java/dev/friendai/client/voice/SherpaStt.java"],
  "~Verified bundles": ["src/client/java/dev/friendai/client/audio/LocalSttBundleManager.java"],
  "~OpenAI STT": ["engine/src/main/java/dev/friendai/features/speech/stt/OpenAiSttProtocol.java"],
  "~Groq STT": ["engine/src/main/java/dev/friendai/features/speech/stt/SttPresets.java"],
  "~Yandex STT": ["engine/src/main/java/dev/friendai/features/speech/stt/YandexSttProtocol.java"],
  "~Key in headers": ["engine/src/main/java/dev/friendai/features/speech/PreparedRequest.java", "engine/src/main/java/dev/friendai/infrastructure/speech/VoiceHttp.java"],
  "~Local Piper voices": ["src/client/java/dev/friendai/client/audio/LocalTtsEngine.java"],
  "~OpenAI TTS": ["engine/src/main/java/dev/friendai/features/speech/tts/OpenAiTtsProtocol.java"],
  "~ElevenLabs": ["engine/src/main/java/dev/friendai/features/speech/tts/ElevenLabsTtsProtocol.java"],
  "~Yandex SpeechKit": ["engine/src/main/java/dev/friendai/features/speech/tts/YandexTtsProtocol.java"],
  "~Spatial audio": ["engine/src/main/java/dev/friendai/features/speech/SpatialAudio.java"],
  "~Stop speaking key": ["src/client/java/dev/friendai/client/tts/SpeechKeybind.java"],
  "~Off by default": ["engine/src/main/java/dev/friendai/infrastructure/config/ClientConfig.java"],
  "~Overview": ["src/client/java/dev/friendai/client/ui/ControlTab.java"],
  "~Toggles": ["src/client/java/dev/friendai/client/ui/SettingsTab.java"],
  "~Tasks": ["src/client/java/dev/friendai/client/ui/TasksTab.java"],
  "~Memory": ["src/client/java/dev/friendai/client/ui/MemoryTab.java"],
  "~Skills": ["src/client/java/dev/friendai/client/ui/SkillsTab.java"],
  "~Places": ["src/client/java/dev/friendai/client/ui/PlacesTab.java"],
  "~Appearance": ["src/client/java/dev/friendai/client/ui/AppearanceTab.java"],
  "~Traders": ["src/client/java/dev/friendai/client/ui/TradersTab.java"],
  "~Log and Debug": ["src/client/java/dev/friendai/client/ui/LogTab.java", "src/client/java/dev/friendai/client/ui/DebugTab.java"],
  "~Settings screen": ["src/client/java/dev/friendai/client/config/FriendaiConfigScreen.java"],
};

/* ---- turn flow (manual layout) ---- */
const FLOW = {
  w: 1160, h: 600,
  nodes: [
    { id: "player", x: 90, y: 300, label: "Player", icon: "🧍", color: COLORS.quartz, kind: "cat", desc: "Types in chat or holds push-to-talk." },
    { id: "voice", x: 270, y: 120, label: "Voice STT", icon: "🎤", color: COLORS.redstone, kind: "cat", desc: "Local Whisper or a cloud recogniser; the transcript is sent to the server." },
    { id: "chat", x: 270, y: 300, label: "ChatRouter", icon: "💬", color: COLORS.water, kind: "cat", desc: "Detects whether the companion is addressed." },
    { id: "picker", x: 270, y: 480, label: "Companion picker", icon: "👥", color: COLORS.water, kind: "cat", desc: "Own companion in hearing range, else the nearest other whose owner is online." },
    { id: "brain", x: 500, y: 300, label: "CompanionBrain", icon: "🧠", color: COLORS.amethyst, kind: "hub", desc: "The per-owner agent loop. One principal per turn; late replies are dropped by a generation counter." },
    { id: "router", x: 500, y: 110, label: "TurnRouter", icon: "🚦", color: COLORS.gold, kind: "cat", desc: "Picks chat, action, goal, recovery or event, and which tool groups are visible; PlanningPolicy then names what a plan-worthy turn is about." },
    { id: "planner", x: 500, y: 500, label: "Planner", icon: "📋", color: COLORS.nether, kind: "cat", desc: "Ordinary orders with enough context can answer and queue in one request. Complex orders retain preliminary planning; troubled plans, unmet goals and free time also use the planner. It runs once, before the model answers, never after a step. agent.plannerMode decides whether it runs at all: auto, off, or always for diagnosis." },
    { id: "prompt", x: 730, y: 110, label: "PromptBuilder", icon: "📜", color: COLORS.gold, kind: "cat", desc: "Identity, memory, perception, skills and mission, cut to a character budget." },
    { id: "llm", x: 730, y: 300, label: "LLM gateway", icon: "☁️", color: COLORS.water, kind: "cat", desc: "OpenAI chat and responses, Anthropic, Gemini, Ollama. Retries only retryable errors; keys stay in headers." },
    { id: "tools", x: 730, y: 500, label: "ToolRegistry", icon: "🧰", color: COLORS.iron, kind: "cat", desc: "Validates arguments and access. Never throws: bad input becomes an error result." },
    { id: "speaker", x: 990, y: 110, label: "Speaker", icon: "🗣️", color: COLORS.gold, kind: "cat", desc: "Posts the chat line and sends the speech packet to the client for TTS." },
    { id: "tasks", x: 990, y: 300, label: "Task queue", icon: "⏱️", color: COLORS.copper, kind: "cat", desc: "TaskRunner ticks one task at a time; guards, retries and plans live here." },
    { id: "world", x: 1010, y: 500, label: "Minecraft world", icon: "🌍", color: COLORS.grass, kind: "hub", desc: "Changes happen only on the server thread, through the fake player." }
  ],
  edges: [
    ["player", "voice", "mic"], ["player", "chat", "message"], ["voice", "chat", "text"], ["chat", "picker"], ["picker", "brain"],
    ["brain", "router"], ["router", "prompt"], ["prompt", "llm", "system + history"], ["brain", "planner", "plan-worthy?"],
    ["planner", "tools", "queue plan"], ["llm", "tools", "tool calls"], ["llm", "speaker", "reply text"],
    ["tools", "tasks", "start task"], ["tasks", "world", "act"],
    ["tasks", "brain", "TaskFinished", { curve: [-30, 0], flow: true }],
    ["world", "brain", "result facts", { curve: [-130, 40], flow: true }]
  ]
};

/* ---- memory & data + architecture cards ---- */
const MEM_CARDS = [
  { title: "Per-owner files", sub: "<world>/friendai/ - one file per owner UUID, atomic writes, .bak recovery", wide: true,
    hub: { label: "Owner data", icon: "🗂️", color: COLORS.amethyst, desc: "Memory, profile, goals and free-time stores share one bounded runtime writer; it flushes latest-revision snapshots on a 5 s server-tick cadence. Unsupported versions are never overwritten." },
    color: COLORS.amethyst, items: [
      "~Memory|Facts about players, trust, learned language, food rules.", "~Conversation transcript|Both sides of every chat, redacted, capped by count and age.", "~Companion profile|Name, description, language and address.", "~Goals|One primary and three background goals, lessons, replan budget.",
      "~Free time|Shifts, history, cooldowns, away summaries, and shift.start/end/report records in the durable log.", "~Instruction skills|19 builtins, global and owner records; list_skills and skill_help read, manage_skill writes.", "~Places|Named places with aliases, dimension and visits.",
      "~Placed blocks|Ledger of blocks it placed (cap 512).", "~Knowledge|Observed resources.", "~Trades|Known villager trades.", "~Blueprints|Saved layouts as JSON." ] },
  { title: "In the world save", sub: "Companion entity + SavedData",
    hub: { label: "Companion entity", icon: "🧍", color: COLORS.grass, desc: "Saved with the world; versioned by Mojang's entity data." },
    color: COLORS.grass, items: [
      "~Inventory 27 slots|Plus six gear slots.", "~Hunger and XP|Food, saturation, exhaustion, level.", "~Combat and pace|Mode, style, pace.",
      "~Saved task queue|Bounded checkpoints, restored by stable keys.", "~Carry data|Body captured at death + last five deaths.", "~Anchors|Owner to chunk, used to load on startup." ] },
  { title: "Settings & caches", sub: "config/ and the game directory",
    hub: { label: "Settings", icon: "⚙️", color: COLORS.iron, desc: "API keys may be written as env:NAME so they never sit in a file." },
    color: COLORS.iron, items: [
      "~Server config v22|Tasks, agent, places, autonomy, chunk loading, build pace.", "~Client config v9|Voice recognition and synthesis, UI.",
      "~Automatic config upgrades|Validated upgrades with unique backups; unknown and future formats stay read-only.", "~Speech models|Whisper plus Parakeet, Nemotron and GigaAM; pinned hashes.",
      "~Piper voices|Five catalogued voices.", "~Skins|Fetched and cached by hash.", "~Atomic save|Temp file then move." ] },
  { title: "The memory book", sub: "What it remembers and who may see it",
    hub: { label: "Memory", icon: "🧠", color: COLORS.amethyst, desc: "Rights are checked per subject on every read, write and forget." },
    color: COLORS.amethyst, items: [
      "~Facts|Per subject, five categories.", "~Player records|Trust level per player.", "~Learned language|First confident sample, switches after three.",
      "~Food rules|Allow and forbid lists.", "~Guest privacy|A guest sees only facts about themself.", "~Trust confirmation|Owner confirms changes." ] },
  { title: "Goals & free time", sub: "What it does when nobody gives orders",
    hub: { label: "Autonomy", icon: "🎯", color: COLORS.nether, desc: "Owner work, an active owner goal and the quiet period after you speak always come first." },
    color: COLORS.nether, items: [
      "~Primary goal|With criteria and a replan budget.", "~Background goals|Up to three more.", "~Lessons|Up to five short notes.", "~Reflex shifts|Hunger, sleep, shelter, unload, farm.",
      "~Director shifts|LLM chooses, budgeted per hour.", "~Fallback ladder|Wood, stone, iron, diamond.", "~Idle watchdog|Closes stuck shifts.", "~Away summary|Tells you what happened.",
      "~Routines|One owner-set timetable with a phase or interval, saved across restarts." ] }
];

const ARCH_FLOW = {
  w: 1000, h: 300,
  nodes: [
    { id: "client", x: 150, y: 120, label: "Client", icon: "🖥️", color: COLORS.water, kind: "hub", desc: "Renderer, screens, HUD, voice capture and speech output." },
    { id: "server", x: 500, y: 120, label: "Server mod", icon: "⚙️", color: COLORS.grass, kind: "hub", desc: "Owns the entity, brain, tasks and memory. The integrated server counts." },
    { id: "engine", x: 850, y: 120, label: "Engine", icon: "🧩", color: COLORS.amethyst, kind: "hub", desc: "Pure Java: no Minecraft, no Fabric, no native audio." },
    { id: "cloud", x: 500, y: 250, label: "LLM / STT / TTS providers", icon: "☁️", color: COLORS.gold, kind: "cat", desc: "Optional cloud endpoints. Keys travel in headers only." }
  ],
  edges: [
    ["client", "server", "payloads", { two: true }], ["server", "engine", "embeds + ports", { two: true }],
    ["engine", "cloud", "LLM HTTP", { curve: [60, 60] }], ["client", "cloud", "STT / TTS", { curve: [-30, 60] }]
  ]
};

const ARCH_CARDS = [
  { title: "Engine module", sub: "engine/src/main/java/dev/friendai",
    hub: { label: "Engine", icon: "🧩", color: COLORS.amethyst, desc: "Reusable pure Java library, embedded once in the mod jar." },
    color: COLORS.amethyst, items: [
      "~foundation|Text, limits, diagnostics, persistence contracts.", "~execution|Queue, tool and history contracts.", "~agent|Turn loop, planner, prompt, LLM protocols.",
      "~features|Pure policies per skill.", "~infrastructure|Config, speech HTTP, persistence." ] },
  { title: "Server side", sub: "src/main/java",
    hub: { label: "Server", icon: "⚙️", color: COLORS.grass, desc: "The only place that touches the world, and only on the server thread." },
    color: COLORS.grass, items: [
      "~server/runtime|One lifecycle per server.", "~server/agent|Sessions, gateways, planner adapter.", "~server/execution|Active task, queue driver, persistence.",
      "~world|Minecraft kernel: movement, blocks, food, targeting, placement.",
      "~features/*/minecraft|Tasks and tools per skill.", "~entity|CompanionEntity and manager.", "~net, chat, command|Protocol and commands.", "~mixin|Two, so hostiles target it." ] },
  { title: "Client side", sub: "src/client/java",
    hub: { label: "Client", icon: "🖥️", color: COLORS.water, desc: "Reads only data the server syncs." },
    color: COLORS.water, items: [
      "~render|Humanoid model, skin, armour.", "~ui|Control screen and tabs.", "~voice|Mic, push-to-talk, STT runtime.", "~tts|Speech playback, spatial mix.",
      "~audio|Local models and native binding.", "~config|YACL screen, optional." ] },
  { title: "Rules that keep it working", sub: "Checked by tests and reviews",
    hub: { label: "Rules", icon: "📜", color: COLORS.gold, desc: "Violating any of these breaks the mod in subtle ways." },
    color: COLORS.gold, items: [
      "~Server thread only|World access never leaves it.", "~Late replies dropped|Generation counter after stop or logout.", "~Bad tool args|Become an error result, never an exception.",
      "~Ordered tool calls|Anthropic and Gemini get one merged result message.", "~Keys never leak|Not in logs, URLs, toasts or toString.", "~Layer boundary|Pure code never imports Minecraft.",
      "~Cheap per tick|Bounded work, resumable state." ] }
];

/* ---- navigation: the map, then the internals ---- */
const GROUPS = [
  { id: "map", label: "The map" },
  { id: "internals", label: "Internals" }
];

const SECTIONS = [
  { id: "overview", group: "map", icon: "🗺️", label: "Overview" },
  { id: "categories", group: "map", icon: "🧩", label: "Skills & Systems" },
  { id: "flow", group: "map", icon: "➡️", label: "One Turn" },
  { id: "memory", group: "map", icon: "🗂️", label: "Memory & Data" },
  { id: "arch", group: "map", icon: "🏗️", label: "Architecture" },
  { id: "tests", group: "internals", icon: "🧪", label: "Testing" },
  { id: "logging", group: "internals", icon: "📝", label: "Logging" },
  { id: "debug", group: "internals", icon: "🔍", label: "Debug & Diagnostics" },
  { id: "protocol", group: "internals", icon: "📡", label: "Network Protocol" },
  { id: "config", group: "internals", icon: "⚙️", label: "Config & Persistence" },
  { id: "ci", group: "internals", icon: "🛠️", label: "Build, CI & Release" }
];

/* ---- reference pages: same card shape as MEM_CARDS, plus an optional pipeline graph ---- */
const DOC = {
  tests: {
    title: "Two suites, one gate",
    lead: "Pure logic is proven by unit tests, world behaviour by in-game GameTests, and both run in CI as one required check. While working, run only the group that covers your change; the full suite belongs to CI.",
    graph: { w: 1200, h: 460, nodes: [
      { id: "engine", x: 110, y: 230, label: "Engine unit tests", icon: "🧪", color: COLORS.amethyst, kind: "hub", desc: "`engine/src/test`: queue, planner, policies, LLM protocols, config migration. No Minecraft, no Fabric." },
      { id: "root", x: 410, y: 230, label: "Root unit tests", icon: "🧱", color: COLORS.grass, kind: "hub", desc: "`src/test`: server adapters, tools and features, run without booting a game." },
      { id: "game", x: 710, y: 230, label: "GameTests", icon: "🌍", color: COLORS.copper, kind: "hub", desc: "111 classes in the separate `friendai-gametest` mod, in one shared world, six CI shards." },
      { id: "client", x: 1010, y: 350, label: "Client UI test", icon: "🖥️", color: COLORS.water, kind: "cat", desc: "`runClientGameTest` drives the real YACL screen and checks both UI languages." },
      { id: "gate", x: 1010, y: 90, label: "CI gate", icon: "🚦", color: COLORS.gold, kind: "cat", desc: "The required check: unit suites, GameTest shards, formatting, packaging, shard map and docs links." }
    ], edges: [
      ["engine", "root"], ["root", "game"], ["game", "client", "display"],
      ["game", "gate", "required", { curve: [40, -40] }], ["client", "gate", "required", { curve: [60, 0] }]
    ] },
    cards: [
      { title: "Unit suites", sub: "JUnit 5, hand-written fakes, no mocking framework", color: COLORS.amethyst,
        hub: { label: "Unit tests", icon: "🧪", color: COLORS.amethyst, desc: "Fast tests that pin behaviour, boundaries, state transitions and failure paths." },
        items: [
          "~Engine suite|`engine/src/test` proves pure logic: plan queue, pathfinding, combat policy, protocols, migrations.",
          "~Root suite|`src/test` proves Minecraft-side adapters and features without starting a game.",
          "~Test fixtures|`engine/src/testFixtures` holds reusable fakes; they never enter the shipped jar.",
          "~Reusable fakes|`MockHttpServer`, `FakeGateway`, `FakeToolExecutor`, `ManualScheduler`, `GridCellView`, `RecordingSpeaker`.",
          "~Package rules|`PackageDependencyRules` fails the build when the engine imports Minecraft.",
          "~Scenario baselines|`ScenarioBaselineTest` replays JSON scenarios through the real turn loop with fakes."
        ] },
      { title: "GameTests", sub: "A separate mod, one shared world, six CI shards", color: COLORS.copper,
        hub: { label: "GameTests", icon: "🌍", color: COLORS.copper, desc: "In-game tests that drive the real server thread, entity, tasks and world." },
        items: [
          "~Separate mod|`friendai-gametest` registers 111 classes in `src/gametest/resources/fabric.mod.json`.",
          "~Shards|`.github/gametest-shards.json` splits them into six CI shards, one class per server boot.",
          "~Shard check|`verifyGametestShards` fails a class that is missing, listed twice or unknown.",
          "~Shared world|Each test owns its block type and arena; counts are filtered by type.",
          "~GameTestSupport|`platform`, `platformAt`, `enclose` and `mockPlayerAt` build safe arenas and players.",
          "~TaskTrace|Records one line per tick; a hang prints its last ticks and CI uploads the log.",
          "~Filtered runs|One glob: `-Dfabric-api.gametest.filter=friendai-gametest:<class>_tests_*`."
        ] },
      { title: "What each kind proves", sub: "Golden files, language files, the tool catalog", color: COLORS.emerald,
        hub: { label: "Proof", icon: "✅", color: COLORS.emerald, desc: "Each check guards a different failure, and each one can fail." },
        items: [
          "~Prompt goldens|`PromptGoldenTest` pins the system prompt; `updatePromptGolden` rewrites it on purpose.",
          "~Language files|`LangFilesTest` compares every locale, every key and every placeholder, and flags unknown keys.",
          "~Tool catalog|A GameTest fails until every registered tool has a catalog entry.",
          "~Client UI|`runClientGameTest` checks fields, transitions, save/cancel/reset and all eight shipped languages.",
          "~Break it once|A test written beside its code is proven by breaking the guarded line and watching the red.",
          "~A green first run|Proves nothing on its own: that is why the break-it step is part of the batch."
        ] },
      { title: "The gates", sub: "What CI requires before anything merges", color: COLORS.gold,
        hub: { label: "Gates", icon: "🚦", color: COLORS.gold, desc: "Nothing merges on a red or pending gate." },
        items: [
          "~CI gate|Unit suites, all GameTest shards, `spotlessCheck`, packaging, the shard map and docs links.",
          "~Commit lint gate|Conventional commits and a conventional PR title.",
          "~Docs links|`scripts/check-docs.sh` checks relative links, anchors and table cell counts.",
          "~Workflow lint|`actionlint` and `shellcheck` over the workflows and scripts.",
          "~Nightly|The whole suite in one server, the only cross-class run.",
          "~Release|A tag must match `mod_version`, sit on `origin/main` and have a green CI run."
        ] }
    ]
  },

  logging: {
    title: "One record per thing that happened",
    lead: "Every brain turn, tool call, task and shift writes a durable JSONL record under the current world's `friendai/logs/`. The same record carries a `cause` link, so an error can be walked back to the turn that started it.",
    graph: { w: 1200, h: 460, nodes: [
      { id: "event", x: 110, y: 230, label: "Brain, tool, task, shift", icon: "📝", color: COLORS.grass, kind: "cat", desc: "Callers emit typed records: identity keys, level, category, event, bounded data." },
      { id: "pipeline", x: 410, y: 230, label: "LogPipeline", icon: "🛡️", color: COLORS.amethyst, kind: "hub", desc: "Redacts secrets first, then truncates, then fans out to every sink." },
      { id: "ring", x: 710, y: 90, label: "DebugLog ring", icon: "🔄", color: COLORS.iron, kind: "cat", desc: "Bounded live buffer behind the Log tab and `read_log`." },
      { id: "files", x: 710, y: 230, label: "JSONL files", icon: "🗄️", color: COLORS.lapis, kind: "cat", desc: "Server, per-owner and client trees with rotation, retention and a generated digest." },
      { id: "digest", x: 710, y: 370, label: "Digest .md", icon: "📄", color: COLORS.quartz, kind: "cat", desc: "A closed file's chain rendered as prose; safe to delete, regenerated on demand." },
      { id: "read", x: 1010, y: 230, label: "Search and diagnose", icon: "🔎", color: COLORS.water, kind: "cat", desc: "`logs_search`, `/friendai diagnose`, the Log tab and the digest read the same records." }
    ], edges: [
      ["event", "pipeline"], ["pipeline", "ring"], ["pipeline", "files"], ["pipeline", "digest"],
      ["ring", "read", "live", { curve: [-40, 0] }], ["files", "read"], ["digest", "read", "prose", { curve: [40, 0] }]
    ] },
    cards: [
      { title: "Where the files are", sub: "`<world>/friendai/logs/` plus `<gameDir>/logs/friendai/`", color: COLORS.lapis,
        hub: { label: "Log tree", icon: "🗄️", color: COLORS.lapis, desc: "Each world owns its server tree and the shared root keeps the client records and the world index; `/friendai logs path` and the Open log folder button point at the current world." },
        items: [
          "~server/<date>/boot-N.jsonl|Lifecycle records and every warn or error from `friendai*` loggers, inside the world.",
          "~owners/<uuid>/<date>/session-*.jsonl|One owner's brain, tool, task and queue records, inside the world.",
          "~client/<date>/client-N.jsonl|Client and speech records, written on the client only.",
          "~index.jsonl|One line per world load and close: time, world, path, boot and versions.",
          "~diagnostics/diagnose-<UTC>/|The `/friendai diagnose` bundle, inside the world.",
          "~README.md|A generated one-page schema, event table and jq recipes.",
          "~One world, one tree|A companion can only search the world it lives in; a busy world cannot prune another's history.",
          "~Before 0.203.0|`server/` and `owners/` left at the shared root stay untouched and unread."
        ] },
      { title: "What a record holds", sub: "Fields worth knowing when you are reading", color: COLORS.amethyst,
        hub: { label: "Record", icon: "🧾", color: COLORS.amethyst, desc: "One JSON object per line, with a header on line one." },
        items: [
          "~seq and cause|`cause` is the seq of the record that caused this one, so a chain is walkable backwards.",
          "~Identity keys|`turn`, `plan`, `job`, `task`, `toolCall`, `trace`, `execution`, `request`.",
          "~category and event|`brain`, `tool`, `task`, `queue`, `speech`, `log` and their event names.",
          "~level|debug, info, warn, error.",
          "~durationMs and data|Bounded values, redacted before they are truncated.",
          "~Spans and timings|`TaskLogSpan`, `ExecutionTiming`, holds versus gaps, bounded stall snapshots."
        ] },
      { title: "Levels, retention and privacy", sub: "What is written, how much, and what never is", color: COLORS.redstone,
        hub: { label: "Policy", icon: "🛡️", color: COLORS.redstone, desc: "Configured keys become `***` before any sink, on the pipeline and on the log4j bridge." },
        items: [
          "~off / normal / detailed / trace|`normal` records metadata only; `detailed` adds prompt and reply bodies after redaction.",
          "~Rotation|8 MiB per file, then `-2` and `-3` parts.",
          "~Retention|50 MiB per world, 7 days, 20 sessions per owner by default.",
          "~Never block|The writer drops records instead of stalling the server and writes one `log.dropped` line.",
          "~Torn last line|A crash-truncated line is skipped by readers instead of breaking the file.",
          "~Detailed is a decision|Do not raise it on a server carrying other people's chat without their knowledge."
        ] },
      { title: "Reading it back", sub: "Four ways in, one source of truth", color: COLORS.gold,
        hub: { label: "Readers", icon: "🔎", color: COLORS.gold, desc: "The ring, the files and the bundle all project the same records." },
        items: [
          "~Log tab|Operator-only live tail with trace and execution prefixes; Pause freezes it.",
          "~read_log|The companion reads its own recent ring, owner only.",
          "~logs_search|Owner-only search by level, category, event, turn, task, trace, execution or request.",
          "~Digest|A `.md` beside each closed file renders the same chain as prose.",
          "~Commands|`/friendai logs path`, `level`, `prune`, `export`.",
          "~Never paste unread|A bundle can hold coordinates and, at `detailed`, prompt text."
        ] }
    ]
  },

  debug: {
    title: "Three answers to 'what just happened'" ,
    lead: "The live ring answers now, the files answer after the fact, and a bundle answers a report. All three carry the same identity keys, so a menu row, a log line and an incident timeline line up.",
    graph: { w: 1200, h: 460, nodes: [
      { id: "q", x: 100, y: 230, label: "Something looks wrong", icon: "❓", color: COLORS.nether, kind: "cat", desc: "The companion did not do what you asked, stopped, or repeated itself." },
      { id: "ring", x: 370, y: 90, label: "DebugLog ring", icon: "🔄", color: COLORS.iron, kind: "cat", desc: "Bounded live buffer, redacted then truncated; the Log tab tails it." },
      { id: "files", x: 370, y: 230, label: "JSONL files", icon: "🗄️", color: COLORS.lapis, kind: "cat", desc: "The durable tree with `cause` links between records." },
      { id: "trace", x: 370, y: 370, label: "TaskTrace", icon: "⏱️", color: COLORS.copper, kind: "cat", desc: "A tick-by-tick timeline written by a GameTest; a hang leaves it in the shard log." },
      { id: "tab", x: 700, y: 90, label: "Log tab", icon: "📜", color: COLORS.gold, kind: "cat", desc: "Operator-only live view with pause." },
      { id: "diag", x: 700, y: 230, label: "diagnose bundle", icon: "📦", color: COLORS.amethyst, kind: "hub", desc: "Manifest, event excerpt and incident timeline, bounded and partial-aware." },
      { id: "artifact", x: 700, y: 370, label: "CI artifact", icon: "📎", color: COLORS.quartz, kind: "cat", desc: "A failing shard uploads `build/gametest-shards/<class>.log`." },
      { id: "report", x: 1010, y: 230, label: "A report someone can act on", icon: "🐞", color: COLORS.water, kind: "cat", desc: "Evidence first: the failing record, its chain, and what the task was doing." }
    ], edges: [
      ["q", "ring"], ["q", "files"], ["q", "trace"], ["ring", "tab"], ["files", "diag"],
      ["trace", "artifact"], ["tab", "report", "", { curve: [-30, 20] }], ["diag", "report"], ["artifact", "report", "", { curve: [30, 20] }]
    ] },
    cards: [
      { title: "The live ring", sub: "What the operator sees while it happens", color: COLORS.iron,
        hub: { label: "Live", icon: "🔄", color: COLORS.iron, desc: "A bounded buffer, not a file: it is fast, lossy and redacted." },
        items: [
          "~DebugLog|Bounded ring, redacted then truncated, and it never reuses the file sequence.",
          "~Log tab|Operator tail with trace and execution prefixes; Pause freezes it.",
          "~Debug tab|Incident bundle, session facts and the per-connection movement overlay switch.",
          "~BrainObserver|`onReply` reports the text a player actually heard, not the draft.",
          "~DebugLogObserver|Writes every prompt line, tool call, result, provider error and token usage."
        ] },
      { title: "The incident bundle", sub: "`/friendai diagnose [chat] [player]`", color: COLORS.amethyst,
        hub: { label: "Bundle", icon: "📦", color: COLORS.amethyst, desc: "Written atomically into its own bounded directory with retention." },
        items: [
          "~manifest.json|Versions, masked config, session identity.",
          "~events.jsonl|A record-boundary excerpt around the incident.",
          "~incident.md|A bounded, partial-aware timeline of the same records.",
          "~summary.txt and queue.txt|The queue, task history and the first thing to read.",
          "~Two-second barrier|Waits at most two seconds for the writer, then says what is missing instead of lying.",
          "~Redacted|Config masks and credential-free URLs, on every file in the bundle."
        ] },
      { title: "Finding the cause", sub: "From a symptom to the record that explains it", color: COLORS.copper,
        hub: { label: "Method", icon: "🔎", color: COLORS.copper, desc: "Read the failing line, then walk `cause` backwards until it is zero." },
        items: [
          "~Find the error|`grep -h '\"level\":\"error\"' owners/<uuid>/*/*.jsonl | tail`.",
          "~Follow the chain|`jq -c 'select(.seq==N or .cause==N)'` until `cause` is 0.",
          "~Correlate|`turn` groups one chat turn; `task`, `job` and `plan` group execution.",
          "~Widen only when needed|Then `/friendai diagnose`, not before.",
          "~A hanging test|`TaskTrace` prints the last twelve ticks every six hundred failed ticks, at most twice.",
          "~A stall|`TaskDiagnosticEpisode` records what the task was doing when it stalled."
        ] },
      { title: "Privacy when sharing", sub: "A bundle is evidence, not a transcript dump", color: COLORS.redstone,
        hub: { label: "Sharing", icon: "🛡️", color: COLORS.redstone, desc: "Operator-gated on the server; an owner sees only their own subtree." },
        items: [
          "~Operator only|Brain log, prompt, perception text and debug tools are gated by `DebugAccess` on the server.",
          "~Owner scope|`/friendai logs path` prints an owner their own subtree, an operator the root.",
          "~Masks|Config values are masked; URLs carry no credentials.",
          "~Keys never leak|Not in logs, chat, exception messages or `toString()`.",
          "~Read before pasting|A `detailed` file can contain prompt and reply text."
        ] }
    ]
  },

  protocol: {
    title: "One versioned protocol",
    lead: "Client and server talk through one family and operation protocol over Fabric payloads. The codecs are pure, bounded and live in the engine; the Fabric wrappers only move bytes.",
    graph: { w: 1400, h: 460, nodes: [
      { id: "join", x: 90, y: 230, label: "Client joins", icon: "🚪", color: COLORS.quartz, kind: "cat", desc: "The server creates a pending session for the player." },
      { id: "hello", x: 330, y: 230, label: "Hello S2C", icon: "📨", color: COLORS.water, kind: "cat", desc: "Bounded version and capability offer." },
      { id: "accept", x: 570, y: 230, label: "Accept C2S", icon: "✅", color: COLORS.emerald, kind: "cat", desc: "Requests stay closed until the server accepts it; a pending handshake expires after 100 ticks." },
      { id: "req", x: 810, y: 230, label: "Request C2S", icon: "📤", color: COLORS.gold, kind: "cat", desc: "Family and operation IDs, a monotonic request ID, a typed body, bounded." },
      { id: "srv", x: 1050, y: 230, label: "Server dispatch", icon: "⚙️", color: COLORS.grass, kind: "hub", desc: "Validates the connection, family, sequence and bounds, then runs on the server thread." },
      { id: "view", x: 1270, y: 90, label: "View S2C", icon: "📥", color: COLORS.amethyst, kind: "cat", desc: "One typed view frame with request correlation." },
      { id: "apply", x: 1270, y: 370, label: "Client applies", icon: "🖥️", color: COLORS.water, kind: "cat", desc: "Hops to the client thread, verifies the captured session, then applies the matching view." }
    ], edges: [
      ["join", "hello"], ["hello", "accept"], ["accept", "req"], ["req", "srv"], ["srv", "view"], ["view", "apply"],
      ["apply", "req", "next request", { flow: true, curve: [-60, 60] }]
    ] },
    cards: [
      { title: "Handshake", sub: "Nothing is accepted before it completes", color: COLORS.emerald,
        hub: { label: "Handshake", icon: "🤝", color: COLORS.emerald, desc: "A failed bootstrap disconnects the peer." },
        items: [
          "~ProtocolHelloS2CPayload|Bounded version and capability offer after join.",
          "~ProtocolAcceptC2SPayload|Accepts the negotiated version and capability set.",
          "~100 ticks|A pending handshake expires and its work is rejected.",
          "~Capabilities|At most 32 unique entries, each name at most 64 bytes.",
          "~Fail closed|Unknown versions, malformed frames, stale sessions and out-of-sequence requests are dropped."
        ] },
      { title: "Frames", sub: "Explicit families and operations, every one bounded", color: COLORS.gold,
        hub: { label: "Frames", icon: "📦", color: COLORS.gold, desc: "Body formats and bounds live in the pure engine `protocol/` package." },
        items: [
          "~ProtocolRequestC2SPayload|Version, family, operation, monotonic request ID, typed body.",
          "~ProtocolViewS2CPayload|One typed view with metadata and request correlation.",
          "~Families|control, memory, places, instructions, trading, debug, speech.",
          "~Threads|Dispatch hops onto the server thread; receivers hop to the client thread.",
          "~Stale views|A frame from an old connection session is ignored, never applied."
        ] },
      { title: "What the client may see", sub: "Synced data only, filtered on the server", color: COLORS.water,
        hub: { label: "Client data", icon: "🖥️", color: COLORS.water, desc: "A screen, HUD or renderer never reads server-only state." },
        items: [
          "~Synced entity data|Food, XP level and creative mode arrive as `EntityDataAccessor`s.",
          "~Menu snapshot|`SnapshotBuilder` builds the state for the requester's own companion.",
          "~Operator tabs|The brain log, prompt and perception text are filtered on the server.",
          "~Map view|At most 128 cells, 32 sites and 32 roads, never the full grid.",
          "~Inventory window|A vanilla container menu, not a custom payload."
        ] },
      { title: "Debug stream", sub: "Live views for the menu and the movement overlay", color: COLORS.iron,
        hub: { label: "Stream", icon: "📡", color: COLORS.iron, desc: "A rejected frame closes that viewer's menu with a warning instead of escaping into the tick." },
        items: [
          "~10-tick cadence|Framed control views while the menu is open.",
          "~Subscribe and close|One typed request, and the same path a disconnect and a ten-tick sweep take.",
          "~Movement overlay|The active task's work snapshot plus independent route obstacle cells.",
          "~Off by default|The overlay switch resets per connection and is never saved.",
          "~Bounded|Cells are capped, and an optional codec section keeps true per-status counts."
        ] }
    ]
  },

  config: {
    title: "Settings and saves that survive",
    lead: "Configuration and owner records are versioned, validated and protected. A failed write never destroys the previous file, and a format nobody can read stays read-only instead of being guessed at.",
    cards: [
      { title: "Configuration", sub: "`config/friendai-server.json` and `config/friendai-client.json`", color: COLORS.gold,
        hub: { label: "Config", icon: "⚙️", color: COLORS.gold, desc: "A key may be written as `env:NAME` so it never sits in a file." },
        items: [
          "~Server schema 23|Tasks, agent, places, autonomy, chunk loading, build pace, logging.",
          "~Client schema 10|Voice recognition and synthesis, provider maps, UI preferences.",
          "~Adjacent migrations|`ConfigMigration` steps from every supported version; its tests are the source of truth.",
          "~Unknown and future|Left untouched; server startup uses in-memory defaults.",
          "~Unique backups|A byte-exact `.vN.<id>.bak` before an atomic replacement.",
          "~Extension fields|`ConfigJson` preserves unknown fields through later saves.",
          "~Keys|`env:NAME` is preferred; keys never appear in logs, URLs or toasts."
        ] },
      { title: "Owner files", sub: "`<world>/friendai/` — one file per owner", color: COLORS.amethyst,
        hub: { label: "Owner data", icon: "🗂️", color: COLORS.amethyst, desc: "Memory, profile, goals, free time, routines, places, placements, knowledge, trading and skills." },
        items: [
          "~OwnerFileStore|Lazy load, debounced flush, atomic replace, `.bak` on corruption.",
          "~One writer|A single bounded writer is borrowed by every store and closed once.",
          "~Typed status|Failure and read-only reasons carry no paths, values or exception text.",
          "~Unsupported versions|Read-only and never overwritten, even when the backup succeeds.",
          "~Independent revisions|Every record has its own format version and revision.",
          "~Corrupt file|Backed up, then the original stays read-only."
        ] },
      { title: "What lives where", sub: "World save, owner books, game directory", color: COLORS.grass,
        hub: { label: "Locations", icon: "📁", color: COLORS.grass, desc: "Diagnostics are not a store: each world's `friendai/logs/` is written, rotated and pruned." },
        items: [
          "~World save|The companion entity, its inventory and hunger, anchors and the carry record.",
          "~`<world>/friendai/`|Owner books and the conversation transcript.",
          "~`config/`|Server and client settings.",
          "~`<gameDir>/friendai/models/`|Speech models and voices, pinned by revision, size and SHA-256.",
          "~Blueprints|`<world>/friendai/blueprints/<name>.json`.",
          "~`<world>/friendai/logs/`|The world's diagnostics tree; client records and the world index stay in `logs/friendai/`. Described on the Logging page."
        ] },
      { title: "Rules for changing it", sub: "A schema change is a product decision", color: COLORS.redstone,
        hub: { label: "Rules", icon: "📜", color: COLORS.redstone, desc: "Existing saves must keep loading, or the maintainer decides otherwise." },
        items: [
          "~Bump the format|And the mod version, in the same change.",
          "~Test the chain|Every supported older version, plus defaults for new fields.",
          "~Never guess|Older and future record formats are unsupported and untouched.",
          "~Stay bounded|8 MiB read and write limits, capped collections, eviction.",
          "~Update the table|The persistence table in `ARCHITECTURE.md` moves with the code."
        ] }
    ]
  },

  ci: {
    title: "Build, gates and release",
    lead: "What runs on your machine, what runs on a pull request, and what a tag has to satisfy. Java 25 is required; the first build downloads Minecraft and takes minutes.",
    cards: [
      { title: "Local commands", sub: "The ones used while working", color: COLORS.copper,
        hub: { label: "Gradle", icon: "🛠️", color: COLORS.copper, desc: "Only one Gradle run per checkout at a time: builds share a lock and the `run/` directory." },
        items: [
          "~./gradlew test|Both unit suites; add `--tests '<pattern>'` for one group.",
          "~./gradlew assemble verifyModArtifact|Compile every source set, package and inspect the jar; refreshes `test-builds/`.",
          "~./gradlew runGameTest -D…filter=…|One class glob, one server boot, under a minute.",
          "~./gradlew spotlessApply -PspotlessFiles=…|Formats only your files; the bare form is forbidden in a shared checkout.",
          "~./gradlew exportTestJar and deployTestJar|Refresh the built jar, or copy it to `TEST_BUILD_PATH`.",
          "~./gradlew build|The full gate. CI runs it; agents run the covering group instead."
        ] },
      { title: "CI jobs", sub: "Every pull request and every push to main", color: COLORS.emerald,
        hub: { label: "CI", icon: "🚦", color: COLORS.emerald, desc: "The required check is `CI gate`, and it requires every job below." },
        items: [
          "~Workflow lint|`actionlint` and `shellcheck` over workflows and scripts.",
          "~Docs links|Links, anchors and table shapes in the guides.",
          "~Formatting|`spotlessCheck` over every Java file.",
          "~Unit tests|Both suites, with a counted summary.",
          "~Game tests|Six shards, one class per server, logs uploaded on failure.",
          "~Build mod jar|Assemble, verify and upload the artifact.",
          "~Commit lint gate|Conventional commits and a conventional PR title."
        ] },
      { title: "Release", sub: "A tag, a jar and a checksum", color: COLORS.gold,
        hub: { label: "Release", icon: "🏷️", color: COLORS.gold, desc: "Tagging, merging and publishing are the maintainer's decision, never an agent's." },
        items: [
          "~mod_version|The single source of truth in `gradle.properties`.",
          "~Tag|`vX.Y.Z` must equal `mod_version`.",
          "~Guard rails|The tag must sit on `origin/main` and point at a commit with a green CI run.",
          "~Artifact|One jar plus `friendai-X.Y.Z.jar.sha256`.",
          "~Dry run|Dispatch the workflow with `dry_run: true` to build and verify without publishing.",
          "~Check script|`scripts/check-release.sh vX.Y.Z` checks the version, tag, jar name and metadata."
        ] },
      { title: "Versioning", sub: "One bump per task, sized by the largest change", color: COLORS.lapis,
        hub: { label: "Versions", icon: "🔢", color: COLORS.lapis, desc: "While the major version is 0, a breaking change bumps MINOR instead of MAJOR." },
        items: [
          "~PATCH|A fix, a tweak, a translation or a dependency patch.",
          "~MINOR|A feature, a skill, a setting or a provider preset.",
          "~No bump|docs, ci, test, style, chore and pure refactor changes.",
          "~Changelog|One player-facing line per shipped change, in the same commit.",
          "~Pre-1.0|Moving to 1.0.0 is the maintainer's call."
        ] }
    ]
  }
};

/* ================= HELPERS ================= */
const $ = (s, r = document) => r.querySelector(s);

/* language: English text is the source; site/i18n.ru.js maps it to Russian, and a missing key falls back to English */
const LANG_KEY = "friendai-map-lang";
let LANG = "en";
try {
  const saved = localStorage.getItem(LANG_KEY);
  LANG = saved === "ru" || saved === "en" ? saved : (navigator.language || "").toLowerCase().startsWith("ru") ? "ru" : "en";
} catch (e) { /* storage blocked: keep the default */ }
const t = (s) => (LANG === "ru" && typeof RU !== "undefined" && RU[s]) || s;
const count = (n, key) => {
  if (LANG !== "ru" || typeof RU_PLURALS === "undefined") return n + " " + key;
  const f = RU_PLURALS[key], m10 = n % 10, m100 = n % 100;
  return n + " " + (m10 === 1 && m100 !== 11 ? f[0] : m10 >= 2 && m10 <= 4 && (m100 < 12 || m100 > 14) ? f[1] : f[2]);
};

/* ---- copy a name and its file paths (double click) ---- */
let toastTimer = 0;
function toast(msg) {
  const el = $("#toast");
  if (!el) return;
  el.textContent = msg;
  el.classList.add("show");
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => el.classList.remove("show"), 1800);
}
// file:// pages are not always a secure context, so keep the old textarea trick as a fallback
function legacyCopy(text) {
  const ta = document.createElement("textarea");
  ta.value = text;
  ta.setAttribute("readonly", "");
  ta.style.cssText = "position:fixed;top:0;left:-9999px;opacity:0";
  document.body.append(ta);
  ta.select();
  let ok = false;
  try { ok = document.execCommand("copy"); } catch (e) { ok = false; }
  ta.remove();
  return ok;
}
function copyLines(lines) {
  const text = lines.filter(Boolean).join("\n");
  const done = (ok) => toast(ok ? t("Copied") : t("Copy failed"));
  if (navigator.clipboard && navigator.clipboard.writeText) {
    navigator.clipboard.writeText(text).then(() => done(true), () => done(legacyCopy(text)));
  } else done(legacyCopy(text));
}

const parseItem = (s, withCode = true) => {
  const trait = s.startsWith("~");
  const raw = trait ? s.slice(1) : s;
  const i = raw.indexOf("|");
  const label = raw.slice(0, i);
  const key = trait ? "~" + label : label;
  return { label: trait ? t(label) : label, desc: t(raw.slice(i + 1)), code: withCode ? CODE[key] : undefined, kind: trait ? "trait" : "tool" };
};


function shade(hex, amt) {
  const n = parseInt(hex.slice(1), 16);
  const c = (v) => Math.max(0, Math.min(255, v + amt));
  return "#" + [(n >> 16) & 255, (n >> 8) & 255, n & 255].map(c).map((v) => v.toString(16).padStart(2, "0")).join("");
}

/* tooltip */
const tip = $("#tip");
function showTip(ev, n) {
  tip.textContent = "";
  const b = document.createElement("b"); b.textContent = n.label;
  const s = document.createElement("small"); s.textContent = n.typeLabel || "";
  const p = document.createElement("p"); p.textContent = n.desc || "";
  tip.append(b, s, p);
  if (n.code && n.code.length) {
    const src = document.createElement("div"); src.className = "src";
    const cap = document.createElement("span"); cap.textContent = t("Source code");
    src.append(cap);
    n.code.forEach((path) => { const line = document.createElement("code"); line.textContent = path; src.append(line); });
    tip.append(src);
  }
  const hint = document.createElement("em"); hint.className = "hint"; hint.textContent = t("Double-click to copy");
  tip.append(hint);
  tip.classList.add("show");
  moveTip(ev);
}
function moveTip(ev) {
  const pad = 16, r = tip.getBoundingClientRect();
  let x = ev.clientX + pad, y = ev.clientY + pad;
  if (x + r.width > innerWidth - 8) x = ev.clientX - r.width - pad;
  if (y + r.height > innerHeight - 8) y = ev.clientY - r.height - pad;
  tip.style.left = Math.max(8, x) + "px";
  tip.style.top = Math.max(8, y) + "px";
}
const hideTip = () => tip.classList.remove("show");

/* ================= GRAPH RENDERER (vis-network: drag, physics, collisions) ================= */
const FONT = "VT323, 'Courier New', monospace";
const lum = (hex) => { const n = parseInt(hex.slice(1), 16); return 0.3 * ((n >> 16) & 255) + 0.59 * ((n >> 8) & 255) + 0.11 * (n & 255); };
const networks = new Map();   // container id -> { net, g }
const DIM_FONT = "rgba(170,170,170,0.35)";

function visNode(n) {
  const label = (n.icon ? n.icon + " " : "") + n.label + (n.sub ? "\n" + n.sub : "");
  let bg, border, fontColor, size = 20, margin = 7, bw = 3;
  if (n.kind === "trait") { bg = "#373737"; border = n.color; fontColor = "#e6e6e6"; size = 19; }
  else if (n.kind === "tool") { bg = "#c6c6c6"; border = n.color; fontColor = "#1b1b1b"; }
  else if (n.kind === "hub") { bg = "#7a4e25"; border = n.color; fontColor = "#ffffff"; size = 26; margin = 14; bw = 5; }
  else { bg = n.color; border = shade(n.color, -80); fontColor = lum(n.color) > 150 ? "#1b1b1b" : "#ffffff"; size = 22; margin = 10; }
  if (n.pipeline) { size = Math.min(size, 18); margin = Math.min(margin, 8); }   // a pipeline sits at 100% zoom, so its labels stay compact
  const font = { face: FONT, size, color: fontColor };
  if (n.kind === "hub" || (n.kind === "cat" && fontColor === "#ffffff")) { font.strokeWidth = 3; font.strokeColor = "#000000"; }
  return {
    id: n.id, label, x: n.x, y: n.y, shape: "box", margin, borderWidth: bw, borderWidthSelected: bw,
    shapeProperties: { borderRadius: 0 }, shadow: { enabled: true, color: "#000000", size: 0, x: 3, y: 3 },
    color: { background: bg, border, highlight: { background: bg, border }, hover: { background: shade(bg, 22), border } },
    font, _font: font, _fontDim: { ...font, color: DIM_FONT, strokeWidth: 0 }
  };
}

function renderGraph(box, g) {
  const prev = networks.get(box.id);
  if (prev) { prev.ro?.disconnect(); prev.net.destroy(); }
  box.textContent = "";
  if (!box.classList.contains("main")) box.style.height = Math.round(g.h * 0.9) + "px";   // main graphs are sized by CSS to fill the screen
  if (typeof vis === "undefined") { box.textContent = t("The graph library failed to load (check your connection)."); return; }

  const byId = {};
  g.nodes.forEach((n) => { byId[n.id] = n; });
  const nodes = new vis.DataSet(g.nodes.map(visNode));
  const edges = new vis.DataSet(g.edges.map((e, i) => {
    const a = byId[e.a], b = byId[e.b];
    const col = e.color || b.color || a.color;
    return {
      id: "e" + i, from: e.a, to: e.b, width: 3, label: e.label || undefined, dashes: !!e.flow,
      length: Math.max(60, Math.hypot(a.x - b.x, a.y - b.y)),
      arrows: e.arrow ? { to: { enabled: true, scaleFactor: 0.7 }, from: { enabled: !!e.two, scaleFactor: 0.7 } } : undefined,
      smooth: e.curve ? { enabled: true, type: "curvedCW", roundness: 0.25 } : false,
      color: { color: col, highlight: col, hover: col, opacity: 0.9 },
      font: { face: FONT, size: 18, color: "#e8e8e8", strokeWidth: 4, strokeColor: "#1d1d1d", align: "middle" },
      selectionWidth: 0, hoverWidth: 0, _flow: !!e.flow
    };
  }));

  const net = new vis.Network(box, { nodes, edges }, {
    layout: { improvedLayout: false },
    physics: {
      solver: "forceAtlas2Based",
      enabled: !g.pipeline,
      forceAtlas2Based: g.manual
        ? { gravitationalConstant: -1, centralGravity: 0, springConstant: 0.15, damping: 0.6, avoidOverlap: 1 }   // springs at rest length: the hand-made layout holds until something is dragged
        : { gravitationalConstant: -60, centralGravity: 0.0005, springConstant: 0.15, damping: 0.6, avoidOverlap: 1 },
      minVelocity: 0.6,
      stabilization: { iterations: 250, updateInterval: 50 }
    },
    interaction: { hover: true, dragNodes: true, dragView: true, zoomView: true, tooltipDelay: 1e9, selectConnectedEdges: false },
    nodes: { chosen: true }, edges: { chosen: false }
  });
  networks.set(box.id, { net, g });
  // Settle off-screen, then reveal and freeze: physics only wakes up while a node is dragged, so nothing drifts or orbits on its own.
  let dragging = false, revealed = false;
  const freeze = () => { if (!dragging && networks.get(box.id)?.net === net) net.setOptions({ physics: { enabled: false } }); };
  const reveal = () => {
    if (revealed || networks.get(box.id)?.net !== net) return;
    revealed = true;
    freeze();
    net.fit({ animation: false });
    box.style.opacity = "1";
  };
  box.style.opacity = "0";
  net.once("stabilizationIterationsDone", reveal);
  net.on("stabilized", freeze);
  setTimeout(reveal, 3000);
  if (window.ResizeObserver) {
    let first = true;
    const ro = new ResizeObserver(() => { if (first) { first = false; return; } if (networks.get(box.id)?.net === net) { try { net.fit({ animation: false }); } catch (e) { /* destroyed mid-resize */ } } });
    ro.observe(box);
    networks.get(box.id).ro = ro;
  }

  let hovered = null;
  const focus = (id) => {
    const near = new Set([id]);
    g.edges.forEach((e) => { if (e.a === id) near.add(e.b); if (e.b === id) near.add(e.a); });
    nodes.update(nodes.get().map((n) => near.has(n.id) ? { id: n.id, opacity: 1, font: n._font } : { id: n.id, opacity: 0.25, font: n._fontDim }));
    edges.update(edges.get().map((e) => {
      const on = e.from === id || e.to === id;
      return { id: e.id, dashes: on || e._flow, width: on ? 4 : 3, color: { ...e.color, opacity: on ? 1 : 0.08 } };
    }));
  };
  const unfocus = () => {
    nodes.update(nodes.get().map((n) => ({ id: n.id, opacity: 1, font: n._font })));
    edges.update(edges.get().map((e) => ({ id: e.id, dashes: e._flow, width: 3, color: { ...e.color, opacity: 0.9 } })));
  };
  const at = (p) => { const r = box.getBoundingClientRect(); return { clientX: r.left + p.x, clientY: r.top + p.y }; };

  net.on("hoverNode", (p) => {
    hovered = p.node;
    focus(p.node);
    box.style.cursor = byId[p.node].onclick ? "pointer" : "grab";
    showTip(at(p.pointer.DOM), byId[p.node]);
  });
  net.on("blurNode", () => { hovered = null; unfocus(); box.style.cursor = "default"; hideTip(); });
  // Only a node drag wakes the layout. Panning the canvas also fires dragStart/dragEnd with an empty
  // selection, and enabling physics there made every node drift while the view was being moved.
  net.on("dragStart", (p) => {
    hideTip();
    box.style.cursor = "grabbing";
    if (!p.nodes || !p.nodes.length) return;
    dragging = true;
    if (!g.pipeline) net.setOptions({ physics: { enabled: true } });
  });
  net.on("dragEnd", (p) => {
    box.style.cursor = "default";
    if (!p.nodes || !p.nodes.length) return;
    dragging = false;
    setTimeout(freeze, 2500);
  });
  // A double click emits two clicks first, so a category only opens once the second click is known not to come.
  let clickTimer = 0;
  net.on("click", (p) => {
    if (!p.nodes.length) return;
    const n = byId[p.nodes[0]];
    if (!n.onclick) return;
    clearTimeout(clickTimer);
    clickTimer = setTimeout(() => { if (networks.get(box.id)?.net === net) n.onclick(); }, 220);
  });
  net.on("doubleClick", (p) => {
    clearTimeout(clickTimer);
    if (!p.nodes.length) return;
    const n = byId[p.nodes[0]];
    copyLines([n.label].concat(n.code || []));
  });
  box.addEventListener("mousemove", (ev) => { if (hovered) moveTip(ev); });

  // toolbar (once per box)
  const host = box.parentNode;
  const oldBar = host.querySelector(".tools");
  if (!oldBar || oldBar.dataset.lang !== LANG) {
    if (oldBar) oldBar.remove();
    const bar = document.createElement("div");
    bar.className = "tools";
    bar.dataset.lang = LANG;
    const mk = (text, title, fn) => {
      const b = document.createElement("button");
      b.type = "button"; b.textContent = text; b.title = title;
      b.addEventListener("click", fn); bar.append(b);
    };
    const zoom = (k) => () => { const n = networks.get(box.id).net; n.moveTo({ scale: n.getScale() * k, animation: { duration: 150 } }); };
    mk("+", t("Zoom in"), zoom(1.25));
    mk("−", t("Zoom out"), zoom(0.8));
    mk(t("Fit"), t("Fit the graph to the view"), () => networks.get(box.id).net.fit({ animation: { duration: 250 } }));
    mk(t("Reset"), t("Restore the original layout"), () => renderGraph(box, networks.get(box.id).g));
    host.append(bar);
  }
}

/* radial layout around a hub: items alternate between an outer and an inner ring when crowded */
function radialGraph(hub, items, color, w, h, hubKind = "cat") {
  const nodes = [], edges = [];
  const cx = w / 2, cy = h / 2;
  nodes.push({ id: "hub", x: cx, y: cy, kind: "hub", label: t(hub.label), icon: hub.icon, color: hub.color || color, desc: t(hub.desc), code: hub.code, typeLabel: t(hub.typeLabel || "Category") });
  const n = items.length;
  const rx = w / 2 - 95, ry = h / 2 - 40;
  const crowded = n > 8;
  items.forEach((it, i) => {
    const ang = -Math.PI / 2 + (i / n) * Math.PI * 2;
    const k = crowded && i % 2 ? 0.62 : 1;
    nodes.push({
      id: "n" + i, x: cx + Math.cos(ang) * rx * k, y: cy + Math.sin(ang) * ry * k,
      kind: it.kind, label: it.label, desc: it.desc, code: it.code, color, typeLabel: t(it.kind === "tool" ? "Tool - the LLM can call it" : "Built-in behaviour")
    });
    edges.push({ a: "hub", b: "n" + i, color });
  });
  return { w, h, nodes, edges };
}

/* ================= VIEWS ================= */
const toolCount = (c) => c.items.filter((s) => !s.startsWith("~")).length;

function buildStats() {
  const tools = new Set();
  CATS.forEach((c) => c.items.forEach((s) => { if (!s.startsWith("~")) tools.add(parseItem(s).label); }));
  const stats = [[tools.size + "+", "tools"], [CATS.length, "categories"], ["111", "GameTest classes"], ["6", "CI shards"], ["5", "LLM protocols"], ["3", "access levels"]];
  const box = $("#stats");
  box.textContent = "";
  stats.forEach(([v, l]) => {
    const d = document.createElement("div"); d.className = "stat";
    const b = document.createElement("b"); b.textContent = v;
    const s = document.createElement("span"); s.textContent = t(l);
    d.append(b, s); box.append(d);
  });
}

function overviewGraph() {
  const w = 1120, h = 780, cx = w / 2, cy = h / 2;
  const nodes = [], edges = [];
  nodes.push({ id: "me", x: cx, y: cy, kind: "hub", label: t("Companion"), icon: "🧍", color: COLORS.grass, typeLabel: t("The companion"), desc: t("A Minecraft NPC bound to one owner. It lives on the server and is driven by an LLM.") });
  const total = CATS.length, gapDeg = 14;
  const span = 360 - gapDeg * DOMAINS.length;
  let start = -90 + gapDeg / 2 - 10;
  const rx = 470, ry = 320;
  DOMAINS.forEach((d) => {
    const list = CATS.filter((c) => c.domain === d.id);
    const sector = span * (list.length / total);
    const mid = start + sector / 2;
    const hubAng = (mid * Math.PI) / 180;
    const hubId = "d-" + d.id;
    nodes.push({ id: hubId, x: cx + Math.cos(hubAng) * 135, y: cy + Math.sin(hubAng) * 105, kind: "cat", label: t(d.name), icon: d.icon, color: d.color, typeLabel: t("Branch"), desc: t(d.desc) });
    edges.push({ a: "me", b: hubId, color: d.color });
    list.forEach((c, i) => {
      const a = ((start + (sector * (i + 0.5)) / list.length) * Math.PI) / 180;
      const k = list.length > 5 && i % 2 ? 0.8 : 1;
      nodes.push({
        id: c.id, x: cx + Math.cos(a) * rx * k, y: cy + Math.sin(a) * ry * k, kind: "cat", label: t(c.name), sub: toolCount(c) ? count(toolCount(c), "tools") : count(c.items.length, "features"),
        icon: c.icon, color: c.color, typeLabel: t("Category - click to open"), desc: t(c.desc), code: CODE[c.id], onclick: () => openCategory(c.id)
      });
      edges.push({ a: hubId, b: c.id, color: d.color });
    });
    start += sector + gapDeg;
  });
  return { w, h, nodes, edges };
}

function flowGraph(src, pipeline) {
  return {
    w: src.w, h: src.h, manual: true, pipeline: !!pipeline,
    nodes: src.nodes.map((n) => ({ ...n, pipeline: !!pipeline, label: t(n.label), desc: t(n.desc), typeLabel: t("Stage") })),
    edges: src.edges.map(([a, b, label, o = {}]) => ({ a, b, label: label && t(label), arrow: true, ...o }))
  };
}

let currentCat = CATS[0].id;
function openCategory(id) {
  currentCat = id;
  switchTab("categories");
  renderCategory();
}
function renderCategory() {
  const c = CATS.find((x) => x.id === currentCat);
  $("#cat-title").textContent = c.icon + "  " + t(c.name);
  $("#cat-desc").textContent = t(c.desc);
  document.querySelectorAll(".chip").forEach((b) => b.setAttribute("aria-pressed", String(b.dataset.id === c.id)));
  const items = c.items.map((s) => parseItem(s));
  const h = items.length > 14 ? 780 : items.length > 10 ? 700 : 620;
  const g = radialGraph({ label: c.name, icon: c.icon, color: c.color, desc: c.desc, code: CODE[c.id] }, items, c.color, 1120, h);
  renderGraph($("#g-cat"), g);
}
function buildChips() {
  const box = $("#chips");
  CATS.forEach((c) => {
    const b = document.createElement("button");
    b.className = "chip"; b.dataset.id = c.id; b.setAttribute("aria-pressed", "false");
    const i = document.createElement("i"); i.textContent = c.icon;
    const label = document.createElement("span"); label.textContent = t(c.name);
    b.append(i, label);
    b.addEventListener("click", () => { currentCat = c.id; renderCategory(); });
    box.append(b);
  });
}

function buildCards(containerId, cards, prefix) {
  const box = $(containerId);
  cards.forEach((c, i) => {
    const sec = document.createElement("section");
    sec.className = "panel" + (c.wide ? " wide" : "");
    const h = document.createElement("h2"); h.textContent = t(c.title);
    const p = document.createElement("p"); p.className = "lead"; p.style.fontSize = "21px"; p.style.margin = "0 0 10px"; p.textContent = t(c.sub);
    const copyCard = () => copyLines([t(c.title), t(c.sub)]);
    [h, p].forEach((el) => { el.title = t("Double-click to copy"); el.addEventListener("dblclick", copyCard); });
    const gb = document.createElement("div"); gb.className = "graphbox" + (c.wide ? "" : " sm");
    const canvas = document.createElement("div"); canvas.className = "vis"; canvas.id = prefix + i; canvas.setAttribute("role", "img"); canvas.setAttribute("aria-label", t(c.title));
    gb.append(canvas); sec.append(h, p, gb); box.append(sec);
    c._box = canvas;
  });
}
function renderCards(cards, hubType = "Store") {
  cards.forEach((c) => {
    const w = c.wide ? 1100 : 640, h = c.wide ? 640 : 520;
    renderGraph(c._box, radialGraph({ ...c.hub, typeLabel: hubType }, c.items.map((s) => parseItem(s, false)), c.color, w, h));
  });
}

/* ---- reference pages ---- */
function buildDocSections() {
  const host = $("#doc-sections");
  host.textContent = "";
  Object.keys(DOC).forEach((key) => {
    const d = DOC[key];
    const sec = document.createElement("section");
    sec.id = "tab-" + key;
    sec.className = "hidden";
    const head = document.createElement("div");
    head.className = "panel";
    const h = document.createElement("h2"); h.textContent = t(d.title);
    const p = document.createElement("p"); p.className = "lead"; richText(p, t(d.lead));
    head.append(h, p);
    if (d.graph) {
      const gb = document.createElement("div"); gb.className = "graphbox";
      const canvas = document.createElement("div");
      canvas.className = "vis"; canvas.id = "g-" + key;
      canvas.setAttribute("role", "img"); canvas.setAttribute("aria-label", t(d.title));
      gb.append(canvas); head.append(gb);
    }
    sec.append(head);
    if (d.cards && d.cards.length) {
      const grid = document.createElement("div");
      grid.className = "grid"; grid.id = "doc-" + key;
      d.cards.forEach((c) => grid.append(docCard(c)));
      sec.append(grid);
    }
    host.append(sec);
  });
}

/* `code` spans are written with backticks in the data; render them as code, never as markup. */
function richText(el, str) {
  String(str).split("`").forEach((part, i) => {
    if (!part) return;
    if (i % 2) { const c = document.createElement("code"); c.textContent = part; el.append(c); }
    else el.append(document.createTextNode(part));
  });
}

/* A reference card is a scannable list: the map pages answer "what can it do", these answer "what is it called and what does it guard". */
function docCard(c) {
  const sec = document.createElement("section");
  sec.className = "panel";
  const h = document.createElement("h2"); h.textContent = t(c.title);
  const p = document.createElement("p"); p.className = "lead"; p.style.fontSize = "21px"; p.style.margin = "0 0 10px"; richText(p, t(c.sub));
  const hub = document.createElement("p"); hub.className = "hubnote"; richText(hub, t(c.hub.desc));
  const ul = document.createElement("ul"); ul.className = "facts";
  c.items.forEach((s) => {
    const raw = s.startsWith("~") ? s.slice(1) : s;
    const i = raw.indexOf("|");
    const li = document.createElement("li");
    const b = document.createElement("b"); richText(b, t(raw.slice(0, i)));
    const span = document.createElement("span"); richText(span, t(raw.slice(i + 1)));
    li.append(b, span);
    li.title = t("Double-click to copy");
    li.addEventListener("dblclick", () => copyLines([b.textContent, span.textContent]));
    ul.append(li);
  });
  const copyCard = () => copyLines([t(c.title), t(c.sub), t(c.hub.desc)]);
  [h, p, hub].forEach((el) => { el.title = t("Double-click to copy"); el.addEventListener("dblclick", copyCard); });
  sec.append(h, p, hub, ul);
  return sec;
}

function renderDoc(name) {
  const d = DOC[name];
  if (d.graph) renderGraph($("#g-" + name), flowGraph(d.graph, true));
}

/* ---- sidebar navigation ---- */
function buildNav() {
  const nav = $("#nav");
  nav.textContent = "";
  GROUPS.forEach((g) => {
    const head = document.createElement("p");
    head.className = "navgroup"; head.textContent = t(g.label); head.dataset.group = g.id;
    nav.append(head);
    SECTIONS.filter((s) => s.group === g.id).forEach((s) => {
      const b = document.createElement("button");
      b.type = "button"; b.className = "navitem"; b.dataset.tab = s.id;
      b.setAttribute("aria-current", "false");
      const i = document.createElement("i"); i.textContent = s.icon;
      const span = document.createElement("span"); span.textContent = t(s.label);
      b.append(i, span);
      b.addEventListener("click", () => { switchTab(s.id); closeNav(); });
      nav.append(b);
    });
  });
  filterNav();
}
function filterNav() {
  const q = ($("#navSearch").value || "").trim().toLowerCase();
  document.querySelectorAll(".navitem").forEach((b) => b.classList.toggle("hide", !!q && !b.textContent.toLowerCase().includes(q)));
  document.querySelectorAll(".navgroup").forEach((head) => {
    let next = head.nextElementSibling, visible = false;
    while (next && next.classList.contains("navitem")) { if (!next.classList.contains("hide")) visible = true; next = next.nextElementSibling; }
    head.classList.toggle("hide", !visible);
  });
}
function closeNav() {
  document.body.classList.remove("navopen");
  $("#backdrop").classList.add("hidden");
  $("#navToggle").setAttribute("aria-expanded", "false");
}
function toggleNav() {
  const open = !document.body.classList.contains("navopen");
  document.body.classList.toggle("navopen", open);
  $("#backdrop").classList.toggle("hidden", !open);
  $("#navToggle").setAttribute("aria-expanded", String(open));
  if (open) $("#navSearch").focus();
}

/* ---- sections ---- */
let currentTab = "overview";
function renderTab(name) {
  if (name === "overview") renderGraph($("#g-overview"), overviewGraph());
  else if (name === "categories") renderCategory();
  else if (name === "flow") renderGraph($("#g-flow"), flowGraph(FLOW));
  else if (name === "memory") renderCards(MEM_CARDS, "Store");
  else if (name === "arch") { renderGraph($("#g-arch"), flowGraph(ARCH_FLOW)); renderCards(ARCH_CARDS, "Part"); }
  else if (DOC[name]) renderDoc(name);
}
function switchTab(name) {
  currentTab = name;
  document.querySelectorAll(".navitem").forEach((b) => {
    const on = b.dataset.tab === name;
    b.setAttribute("aria-current", String(on));
    b.setAttribute("aria-selected", String(on));
  });
  document.querySelectorAll("#content > section, #doc-sections > section").forEach((s) => s.classList.toggle("hidden", s.id !== "tab-" + name));
  const sec = SECTIONS.find((s) => s.id === name) || SECTIONS[0];
  $("#crumb").textContent = t(sec.label);
  hideTip();
  renderTab(name);
  try { history.replaceState(null, "", "#" + name); } catch (e) { /* file:// or sandbox */ }
  window.scrollTo({ top: 0, behavior: "auto" });
}
function step(dir) {
  const i = Math.max(0, SECTIONS.findIndex((s) => s.id === currentTab));
  const next = SECTIONS[(i + dir + SECTIONS.length) % SECTIONS.length];
  switchTab(next.id);
  closeNav();
}

/* ---- language switch ---- */
const STATIC_TEXT = "#tab-overview h2, #tab-overview .lead, #tab-flow h2, #tab-flow .lead, #tab-arch > .panel h2, #tab-arch > .panel .lead, .legend span, .brand .sub, .sidefoot, footer";
function translateStatic() {
  document.querySelectorAll(STATIC_TEXT).forEach((el) => {
    el.childNodes.forEach((n) => {
      if (n.nodeType !== 3 || !n.nodeValue.trim()) return;
      if (n._en === undefined) n._en = n.nodeValue;
      const key = n._en.trim();
      n.nodeValue = n._en.replace(key, t(key));
    });
  });
  document.documentElement.lang = LANG;
  document.querySelectorAll("#lang button").forEach((b) => b.setAttribute("aria-pressed", String(b.dataset.lang === LANG)));
  $("#navSearch").placeholder = t("Filter sections");
  ["#prev", "#next"].forEach((id) => {
    const label = id === "#prev" ? t("Previous section") : t("Next section");
    $(id).title = label;
    $(id).setAttribute("aria-label", label);
  });
  $("#navToggle").setAttribute("aria-label", t("Sections"));
  $("#side").setAttribute("aria-label", t("Sections"));
  $("#nav").setAttribute("aria-label", t("Sections"));
}
function buildDynamic() {
  buildNav();
  buildStats();
  $("#chips").textContent = "";
  buildChips();
  ["#memory-grid", "#arch-grid"].forEach((id) => { $(id).textContent = ""; });
  buildCards("#memory-grid", MEM_CARDS, "m-");
  buildCards("#arch-grid", ARCH_CARDS, "a-");
  buildDocSections();
}
function setLang(lang) {
  if (lang === LANG) return;
  LANG = lang;
  try { localStorage.setItem(LANG_KEY, lang); } catch (e) { /* not persisted */ }
  translateStatic();
  buildDynamic();
  hideTip();
  $("#toast").classList.remove("show");
  const sec = SECTIONS.find((s) => s.id === currentTab) || SECTIONS[0];
  $("#crumb").textContent = t(sec.label);
  renderTab(currentTab);
}

/* ---- dirt background tile ---- */
function dirtBackground() {
  try {
    const c = document.createElement("canvas"); c.width = c.height = 16;
    const x = c.getContext("2d");
    let seed = 7; const rnd = () => ((seed = (seed * 1664525 + 1013904223) >>> 0) / 4294967296);
    const pal = ["#6b4423", "#7a4e25", "#5d3a1c", "#86572a", "#533318", "#6f6f6f"];
    for (let i = 0; i < 16; i++) for (let j = 0; j < 16; j++) {
      const r = rnd();
      x.fillStyle = r > 0.97 ? pal[5] : pal[Math.floor(r * 5)];
      x.fillRect(i, j, 1, 1);
    }
    document.body.style.backgroundImage = `url(${c.toDataURL()})`;
    document.body.style.backgroundSize = "96px 96px";
  } catch (e) { /* plain colour fallback */ }
}

/* ---- boot ---- */
function boot() {
  dirtBackground();
  translateStatic();
  buildDynamic();
  document.querySelectorAll("#lang button").forEach((b) => b.addEventListener("click", () => setLang(b.dataset.lang)));
  $("#prev").addEventListener("click", () => step(-1));
  $("#next").addEventListener("click", () => step(1));
  $("#navToggle").addEventListener("click", toggleNav);
  $("#backdrop").addEventListener("click", closeNav);
  $("#navSearch").addEventListener("input", filterNav);
  $("#navSearch").addEventListener("keydown", (ev) => { if (ev.key === "Escape") { ev.currentTarget.value = ""; filterNav(); ev.currentTarget.blur(); } });
  document.addEventListener("keydown", (ev) => {
    const typing = /^(INPUT|TEXTAREA|SELECT)$/.test(ev.target.tagName) || ev.target.isContentEditable;
    if (ev.key === "/" && !typing) { ev.preventDefault(); $("#navSearch").focus(); }
    else if (ev.key === "Escape" && !typing) closeNav();
  });
  const initial = (location.hash || "").slice(1);
  switchTab(SECTIONS.some((s) => s.id === initial) ? initial : "overview");
}
// measure text with the real fonts, but never wait forever for them
let started = false;
const go = () => { if (!started) { started = true; boot(); } };
if (document.fonts && document.fonts.load) {
  Promise.race([Promise.all([document.fonts.load("20px VT323"), document.fonts.load("11px 'Press Start 2P'")]), new Promise((r) => setTimeout(r, 1500))]).then(go, go);
} else go();
