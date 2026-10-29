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
      "follow_player|Follow the owner at a chosen distance.", "stay|Hold the current position.", "go_to|Pathfind to coordinates.",
      "jump|Jump once.", "set_pace|Walk, sprint or sneak pace.", "set_follow_mode|Choose whether it follows when idle.",
      "step|Move by a relative offset.", "back_away|Retreat from a target.", "approach|Walk up to a player, mob or block.",
      "circle|Circle around a point.", "set_follow_distance|How close it stays to the owner.",
      "~A* pathfinder|Incremental, resumable search that spreads work over ticks.", "~Dig, pillar, bridge|Breaks and places blocks when the way is blocked.",
      "~Stuck recovery|Re-plans when the world disagrees with the path." ] },
  { id: "mining", domain: "hands", name: "Mining & Gathering", icon: "⛏️", color: COLORS.iron,
    desc: "Collects raw materials: trees, ores, drops. Tool choice comes from item data components, never hard-coded tiers.",
    items: [
      "fell_tree|Fell a whole tree top-down using scaffold.", "mine_blocks|Mine a number of blocks of a type.", "mine_block_at|Break one specific block.",
      "pickup_items|Collect dropped items nearby.", "find_trees|Scan for trees.", "find_blocks|Scan for blocks and ore veins.",
      "find_placed|List blocks the companion placed earlier.",
      "~Vein follower|Mines a whole vein, not one block.", "~Tool selection|Picks the best tool from the stack's tool component.", "~Safe digging|Checks support and hazards before approaching." ] },
  { id: "building", domain: "hands", name: "Building", icon: "🧱", color: COLORS.oak,
    desc: "Places blocks through a fake player so vanilla rules and protection apply. Blueprints up to 32x32x32.",
    items: [
      "place_block|Place one block.", "use_block|Right-click a block like a player.", "light_area|Place torches to stop mob spawns.",
      "clear_area|Clear a volume safely.", "preview_structure|Show a ghost preview.", "build_structure|Build from a generated or saved layout.",
      "save_blueprint|Store a layout as JSON.", "list_blueprints|List saved blueprints.", "blueprint_help|Explain the blueprint format.",
      "build_special|Build an iron golem, snow golem or nether portal.", "plan_special|Dry-run a special structure.",
      "~Blueprint parser|Palette, ops and ASCII layers; reports every error at once.", "~Resumable builds|Progress survives restarts.", "~Fake-player hands|Respects world protection." ] },
  { id: "crafting", domain: "hands", name: "Crafting & Cooking", icon: "🔨", color: COLORS.copper,
    desc: "Recursive crafting plans (depth up to 5), furnace jobs and enchanting-station work through real menus.",
    items: [
      "craft_item|Craft an item, placing a table if needed.", "get_recipe|Look up how something is made.", "smelt_items|Run furnace jobs with fuel.",
      "cook_food|Cook raw food.", "collect_smelted|Pick up finished furnace output.", "enchant_item|Use an enchanting table.",
      "anvil_item|Repair or combine at an anvil.", "grindstone_item|Strip enchantments at a grindstone.",
      "~Craft planner|Turns a goal item into ordered steps.", "~Station leases|Remembers a table it placed for 600 ticks." ] },
  { id: "combat", domain: "hands", name: "Combat", icon: "⚔️", color: COLORS.redstone,
    desc: "Reflex combat ticked before everything else, with a single arbiter for movement, shields, bow, potions and retreat.",
    items: [
      "attack|Attack a chosen mob.", "attack_player|Attack a player (owner approval).", "set_combat_mode|Self only, bodyguard, guard or avoid combat.",
      "get_combat_settings|Read the current style.", "set_combat_settings|Fighting style and retreat preference.",
      "~Shield timing|Raises on a draw or arrow, lowers to swing.", "~Bow aim with lead|Bounded predictive aim.", "~Potion use|Drinks the right potion at the right time.",
      "~Weapon choice|Scores weapons by DPS and durability.", "~Group pressure|Sidesteps away from a second attacker.", "~Hostiles target it|Mobs treat it like a player." ] },
  { id: "survival", domain: "hands", name: "Survival", icon: "🍖", color: COLORS.gold,
    desc: "A vanilla-like hunger model, food rules you control, sleep, and fire handling. It keeps what it carries when it dies.",
    items: [
      "eat|Eat suitable food.", "get_food|Gather food.", "allow_food|Allow an item as food.", "forbid_food|Never eat an item.",
      "reset_food_rule|Forget a food rule.", "get_food_rules|Show food rules.", "sleep|Sleep in a bed at night.", "extinguish_fire|Put fires out with a water bucket.",
      "~Hunger model|Food, saturation, exhaustion, regeneration, starvation.", "~Fire reflex|Reacts to nearby flames automatically.", "~Death and respawn|Inventory and queue return with the new body." ] },
  { id: "farming", domain: "hands", name: "Farming & Animals", icon: "🌾", color: COLORS.emerald,
    desc: "Plans a field, plants, tends and harvests; works with animals and fishing.",
    items: [
      "farm|Plant and harvest crops.", "plan_farm|Preview what a farm would need.", "tend_farm|Keep an existing farm going.", "use_bonemeal|Speed up growth.",
      "breed_animals|Breed a species.", "shear|Shear sheep.", "milk|Milk cows.", "tame_animal|Tame an animal.", "lead_animals|Lead animals with a lead.",
      "harvest_honey|Collect honey.", "fish|Go fishing.", "~Farm planner|Cells, soil, crops, food need.", "~Re-plans on mismatch|Adjusts when the world differs from the plan." ] },
  { id: "inventory", domain: "hands", name: "Inventory & Storage", icon: "🎒", color: COLORS.amethyst,
    desc: "A 27-slot inventory plus six gear slots, and shared container logic for chests and barrels.",
    items: [
      "get_inventory|List what it carries.", "show_inventory|Open the inventory view.", "equip_item|Equip gear.", "give_items|Hand items to a player.",
      "drop_items|Drop items.", "throw_item|Throw an item.", "store_items|Put items into a container.", "take_items|Take items out of a container.",
      "check_container|Look inside a container.", "deposit_items|Deposit into the nearest storage.",
      "~Equipment picker|Armor and weapon loadout.", "~Owner-only window|Within 8 blocks, same dimension." ] },
  { id: "acquire", domain: "hands", name: "Resource Acquisition", icon: "🧭", color: COLORS.diamond,
    desc: "Ask for an item and it works out how: inventory, chests, craft, smelt, mine, hunt, harvest, shear, milk or explore.",
    items: [
      "obtain_item|Get an item by any means.", "plan_obtain|Show the plan without doing it.", "search_resource|Explore to find a resource.",
      "known_resources|What it already knows about.", "search_hint|Give it a hint where to look.", "find_target|Resolve a search target.",
      "~Obtain planner|Depth up to 8, nine step kinds.", "~Frontier search|Biome edges, water, hazards and trails.", "~Search budget|Gives up sensibly." ] },
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
      "~LLM planner|1-4 attempts, JSON mode, repair prompts.", "~Conditional plans|If, repeat and guards over world facts.", "~Outcome checker|Compares before and after snapshots.",
      "~Recovery advisor|Classifies a failure and suggests a new remedy." ] },
  { id: "memory", domain: "mind", name: "Memory & Trust", icon: "🧠", color: COLORS.amethyst,
    desc: "Per-owner memory with strict visibility: guests only see facts about themselves, and trust changes need the owner's confirmation.",
    items: [
      "remember|Store a fact.", "recall|Read facts back.", "forget|Delete a fact.", "trust_player|Raise a player to trusted (needs confirmation).",
      "untrust_player|Lower trust.", "set_name|Rename the companion.", "use_skill|Load an instruction skill once.", "manage_skill|Propose a skill after a finished goal.",
      "~Access levels|GUEST < TRUSTED < OWNER on every tool.", "~Language learning|Learns your language after a few samples.", "~Skills advise only|They never change permissions." ] },
  { id: "places", domain: "mind", name: "Places", icon: "📍", color: COLORS.diamond,
    desc: "A book of named places. Autonomous work never breaks inside a known place and takes only from containers in one.",
    items: [
      "remember_place|Save the current spot.", "go_to_place|Walk to a named place.", "rename_place|Rename it.", "forget_place|Delete it.", "find_place|Look one up.",
      "~Auto registration|Notes beds, bases and farms.", "~Place guard|Protects known places during free time.", "~Same dimension|go_to_place never crosses dimensions." ] },

  { id: "perception", domain: "senses", name: "Perception", icon: "👁️", color: COLORS.quartz,
    desc: "Turns the world into prompt text: surroundings, threats, landmarks and what the owner is looking at.",
    items: [
      "look_around|Describe the surroundings.", "player_view|What the owner sees.", "describe_block|Inspect one block.", "survey_area|Survey a region.",
      "~Brief radius 8|Cheap default perception.", "~Detailed radius 16|When more detail is needed.", "~Owner focus|Block or entity the owner looks at.", "~Landmarks and area map|Relative directions and a compact map." ] },
  { id: "chat", domain: "senses", name: "Chat & Addressing", icon: "💬", color: COLORS.water,
    desc: "Decides who is being spoken to and which companion hears it.",
    items: [
      "~Address detection|Name or direct address.", "~Companion picker|Own within hearing range, else nearest other.", "~Reply splitter|Splits long replies into lines.",
      "~Trust confirmations|Owner confirms yes or no in chat.", "~/friendai commands|summon, dismiss, stop, trust, memory, skills, reload.", "~English and Russian|Interface and intent vocabularies." ] },
  { id: "voicein", domain: "senses", name: "Voice Input", icon: "🎤", color: COLORS.redstone,
    desc: "Push-to-talk speech recognition. Local audio never leaves the machine unless you pick a cloud provider.",
    items: [
      "~Push-to-talk|16 kHz, 16-bit, mono capture.", "~Local Whisper|tiny, base or small, size and SHA-256 checked.", "~OpenAI STT|Cloud recognition.",
      "~Groq STT|Cloud recognition.", "~Yandex STT|Cloud recognition.", "~Key in headers|Keys never go in URLs or logs." ] },
  { id: "voiceout", domain: "senses", name: "Speech Output", icon: "🔊", color: COLORS.gold,
    desc: "Replies can be spoken locally or through a cloud voice, panned and attenuated by where the companion is.",
    items: [
      "~Local Piper voices|Five catalogued voices via sherpa-onnx.", "~OpenAI TTS|Cloud speech.", "~ElevenLabs|Cloud speech.", "~Yandex SpeechKit|Cloud speech.",
      "~Spatial audio|Equal-power pan and distance level.", "~Stop speaking key|Cuts playback instantly.", "~Off by default|Opt in from settings." ] },
  { id: "menu", domain: "senses", name: "Companion Menu", icon: "🖥️", color: COLORS.iron,
    desc: "A tabbed control screen that pauses the companion while open. Operator-only tabs are filtered on the server.",
    items: [
      "~Overview|Status, health, hunger, task.", "~Toggles|Per-companion switches.", "~Tasks|Queue and history.", "~Memory|Browse and forget.", "~Skills|Instruction skills.",
      "~Places|Rename or forget places.", "~Appearance|Name and custom skin.", "~Traders|Known trades.", "~Log and Debug|Operator-only brain log and prompt.", "~Settings screen|YACL, optional." ] }
];

/* ---- turn flow (manual layout) ---- */
const FLOW = {
  w: 1160, h: 600,
  nodes: [
    { id: "player", x: 90, y: 300, label: "Player", icon: "🧍", color: COLORS.quartz, kind: "cat", desc: "Types in chat or holds push-to-talk." },
    { id: "voice", x: 270, y: 120, label: "Voice STT", icon: "🎤", color: COLORS.redstone, kind: "cat", desc: "Local Whisper or a cloud recogniser; the transcript is sent to the server." },
    { id: "chat", x: 270, y: 300, label: "ChatRouter", icon: "💬", color: COLORS.water, kind: "cat", desc: "Detects whether the companion is addressed." },
    { id: "picker", x: 270, y: 480, label: "Companion picker", icon: "👥", color: COLORS.water, kind: "cat", desc: "Own companion in hearing range, else the nearest other whose owner is online." },
    { id: "brain", x: 500, y: 300, label: "CompanionBrain", icon: "🧠", color: COLORS.amethyst, kind: "hub", desc: "The per-owner agent loop. One principal per turn; late replies are dropped by a generation counter." },
    { id: "router", x: 500, y: 110, label: "TurnRouter", icon: "🚦", color: COLORS.gold, kind: "cat", desc: "Picks chat, action, goal, recovery or event, and which tool groups are visible." },
    { id: "planner", x: 500, y: 500, label: "Planner", icon: "📋", color: COLORS.nether, kind: "cat", desc: "For complex requests: asks the LLM for a JSON plan and queues it with the requester's access." },
    { id: "prompt", x: 730, y: 110, label: "PromptBuilder", icon: "📜", color: COLORS.gold, kind: "cat", desc: "Identity, memory, perception, skills and mission, cut to a character budget." },
    { id: "llm", x: 730, y: 300, label: "LLM gateway", icon: "☁️", color: COLORS.water, kind: "cat", desc: "OpenAI chat and responses, Anthropic, Gemini, Ollama. Retries only retryable errors; keys stay in headers." },
    { id: "tools", x: 730, y: 500, label: "ToolRegistry", icon: "🧰", color: COLORS.iron, kind: "cat", desc: "Validates arguments and access. Never throws: bad input becomes an error result." },
    { id: "speaker", x: 990, y: 110, label: "Speaker", icon: "🗣️", color: COLORS.gold, kind: "cat", desc: "Posts the chat line and sends the speech packet to the client for TTS." },
    { id: "tasks", x: 990, y: 300, label: "Task queue", icon: "⏱️", color: COLORS.copper, kind: "cat", desc: "TaskRunner ticks one task at a time; guards, retries and plans live here." },
    { id: "world", x: 1010, y: 500, label: "Minecraft world", icon: "🌍", color: COLORS.grass, kind: "hub", desc: "Changes happen only on the server thread, through the fake player." }
  ],
  edges: [
    ["player", "voice", "mic"], ["player", "chat", "message"], ["voice", "chat", "text"], ["chat", "picker"], ["picker", "brain"],
    ["brain", "router"], ["router", "prompt"], ["prompt", "llm", "system + history"], ["brain", "planner", "complex?"],
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
      "~Memory|Facts about players, trust, learned language, food rules.", "~Companion profile|Name, description, language and address.", "~Goals|One primary and three background goals, lessons, replan budget.",
      "~Free time|Shifts, history, cooldowns, away summaries.", "~Instruction skills|Builtin, global and owner records.", "~Places|Named places with aliases, dimension and visits.",
      "~Placed blocks|Ledger of blocks it placed (cap 512).", "~Knowledge|Observed resources.", "~Trades|Known villager trades.", "~Blueprints|Saved layouts as JSON." ] },
  { title: "In the world save", sub: "Companion entity + SavedData",
    hub: { label: "Companion entity", icon: "🧍", color: COLORS.grass, desc: "Saved with the world; versioned by Mojang's entity data." },
    color: COLORS.grass, items: [
      "~Inventory 27 slots|Plus six gear slots.", "~Hunger and XP|Food, saturation, exhaustion, level.", "~Combat and pace|Mode, style, pace.",
      "~Saved task queue|Bounded checkpoints, restored by stable keys.", "~Carry data|Body captured at death + last five deaths.", "~Anchors|Owner to chunk, used to load on startup." ] },
  { title: "Settings & caches", sub: "config/ and the game directory",
    hub: { label: "Settings", icon: "⚙️", color: COLORS.iron, desc: "API keys may be written as env:NAME so they never sit in a file." },
    color: COLORS.iron, items: [
      "~Server config v16|Tasks, agent, places, autonomy, chunk loading.", "~Client config v4|Voice recognition and synthesis, UI.", "~Whisper models|tiny, base, small.",
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
      "~Director shifts|LLM chooses, budgeted per hour.", "~Fallback ladder|Wood, stone, iron, diamond.", "~Idle watchdog|Closes stuck shifts.", "~Away summary|Tells you what happened." ] }
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

const parseItem = (s) => {
  const trait = s.startsWith("~");
  const raw = trait ? s.slice(1) : s;
  const i = raw.indexOf("|");
  const label = raw.slice(0, i);
  return { label: trait ? t(label) : label, desc: t(raw.slice(i + 1)), kind: trait ? "trait" : "tool" };
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
  net.on("dragStart", () => { dragging = true; net.setOptions({ physics: { enabled: true } }); hideTip(); box.style.cursor = "grabbing"; });
  net.on("dragEnd", () => { dragging = false; box.style.cursor = "default"; setTimeout(freeze, 2500); });
  net.on("click", (p) => {
    if (p.nodes.length && byId[p.nodes[0]].onclick) byId[p.nodes[0]].onclick();
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
  nodes.push({ id: "hub", x: cx, y: cy, kind: "hub", label: t(hub.label), icon: hub.icon, color: hub.color || color, desc: t(hub.desc), typeLabel: t(hub.typeLabel || "Category") });
  const n = items.length;
  const rx = w / 2 - 95, ry = h / 2 - 40;
  const crowded = n > 8;
  items.forEach((it, i) => {
    const ang = -Math.PI / 2 + (i / n) * Math.PI * 2;
    const k = crowded && i % 2 ? 0.62 : 1;
    nodes.push({
      id: "n" + i, x: cx + Math.cos(ang) * rx * k, y: cy + Math.sin(ang) * ry * k,
      kind: it.kind, label: it.label, desc: it.desc, color, typeLabel: t(it.kind === "tool" ? "Tool - the LLM can call it" : "Built-in behaviour")
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
  const stats = [[tools.size + "+", "tools"], [CATS.length, "categories"], ["5", "LLM protocols"], ["3", "access levels"], ["2", "languages"], ["3", "STT + 4 TTS engines"]];
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
        icon: c.icon, color: c.color, typeLabel: t("Category - click to open"), desc: t(c.desc), onclick: () => openCategory(c.id)
      });
      edges.push({ a: hubId, b: c.id, color: d.color });
    });
    start += sector + gapDeg;
  });
  return { w, h, nodes, edges };
}

function flowGraph(src) {
  return {
    w: src.w, h: src.h, manual: true,
    nodes: src.nodes.map((n) => ({ ...n, label: t(n.label), desc: t(n.desc), typeLabel: t("Stage") })),
    edges: src.edges.map(([a, b, label, o = {}]) => ({ a, b, label: label && t(label), arrow: true, ...o }))
  };
}

let currentCat = CATS[0].id;
function openCategory(id) {
  currentCat = id;
  switchTab("categories");
  renderCategory();
  window.scrollTo({ top: $("#tabs").offsetTop, behavior: "smooth" });
}
function renderCategory() {
  const c = CATS.find((x) => x.id === currentCat);
  $("#cat-title").textContent = c.icon + "  " + t(c.name);
  $("#cat-desc").textContent = t(c.desc);
  document.querySelectorAll(".chip").forEach((b) => b.setAttribute("aria-pressed", String(b.dataset.id === c.id)));
  const items = c.items.map(parseItem);
  const h = items.length > 14 ? 780 : items.length > 10 ? 700 : 620;
  const g = radialGraph({ label: c.name, icon: c.icon, color: c.color, desc: c.desc }, items, c.color, 1120, h);
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
    const gb = document.createElement("div"); gb.className = "graphbox" + (c.wide ? "" : " sm");
    const canvas = document.createElement("div"); canvas.className = "vis"; canvas.id = prefix + i; canvas.setAttribute("role", "img"); canvas.setAttribute("aria-label", t(c.title));
    gb.append(canvas); sec.append(h, p, gb); box.append(sec);
    c._box = canvas;
  });
}
function renderCards(cards) {
  cards.forEach((c) => {
    const w = c.wide ? 1100 : 640, h = c.wide ? 640 : 520;
    renderGraph(c._box, radialGraph({ ...c.hub, typeLabel: "Store" }, c.items.map(parseItem), c.color, w, h));
  });
}

/* ---- tabs ---- */
let currentTab = "overview";
function renderTab(name) {
  if (name === "overview") renderGraph($("#g-overview"), overviewGraph());
  if (name === "categories") renderCategory();
  if (name === "flow") renderGraph($("#g-flow"), flowGraph(FLOW));
  if (name === "memory") renderCards(MEM_CARDS);
  if (name === "arch") { renderGraph($("#g-arch"), flowGraph(ARCH_FLOW)); renderCards(ARCH_CARDS); }
}
function switchTab(name) {
  currentTab = name;
  document.querySelectorAll(".tab").forEach((t) => t.setAttribute("aria-selected", String(t.dataset.tab === name)));
  ["overview", "categories", "flow", "memory", "arch"].forEach((id) => $("#tab-" + id).classList.toggle("hidden", id !== name));
  hideTip();
  renderTab(name);
  try { history.replaceState(null, "", "#" + name); } catch (e) { /* file:// or sandbox */ }
}

/* ---- language switch ---- */
const STATIC_TEXT = ".tab, #tab-overview h2, #tab-overview .lead, #tab-flow h2, #tab-flow .lead, #tab-arch > .panel h2, #tab-arch > .panel .lead, .legend span, footer";
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
}
function buildDynamic() {
  buildStats();
  $("#chips").textContent = "";
  buildChips();
  ["#memory-grid", "#arch-grid"].forEach((id) => { $(id).textContent = ""; });
  buildCards("#memory-grid", MEM_CARDS, "m-");
  buildCards("#arch-grid", ARCH_CARDS, "a-");
}
function setLang(lang) {
  if (lang === LANG) return;
  LANG = lang;
  try { localStorage.setItem(LANG_KEY, lang); } catch (e) { /* not persisted */ }
  translateStatic();
  buildDynamic();
  hideTip();
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
  document.querySelectorAll(".tab").forEach((t) => t.addEventListener("click", () => switchTab(t.dataset.tab)));
  const initial = (location.hash || "").slice(1);
  switchTab(["overview", "categories", "flow", "memory", "arch"].includes(initial) ? initial : "overview");
}
// measure text with the real fonts, but never wait forever for them
let started = false;
const go = () => { if (!started) { started = true; boot(); } };
if (document.fonts && document.fonts.load) {
  Promise.race([Promise.all([document.fonts.load("20px VT323"), document.fonts.load("11px 'Press Start 2P'")]), new Promise((r) => setTimeout(r, 1500))]).then(go, go);
} else go();
