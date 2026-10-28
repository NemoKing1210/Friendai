package dev.friendai.config;

import com.google.gson.JsonObject;
import dev.friendai.agent.autonomy.AutonomySettings;
import dev.friendai.agent.llm.provider.LlmProfile;
import dev.friendai.agent.llm.provider.RoutingRule;
import dev.friendai.foundation.diagnostics.ConfigException;
import dev.friendai.foundation.text.UserMessage;
import dev.friendai.memory.MemoryLimits;
import dev.friendai.pathing.PathingConfig;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** friendai-server.json. */
public class ServerConfig {
    public static final int VERSION = 16;

    public int version = VERSION;
    public String activeProfile = "main";
    public Map<String, ProfileConfig> profiles = new LinkedHashMap<>();
    public CompanionSection companion = new CompanionSection();
    public ChatSection chat = new ChatSection();
    public BrainSection brain = new BrainSection();
    public TasksSection tasks = new TasksSection();
    public MemorySection memory = new MemorySection();
    public PathingSection pathing = new PathingSection();
    public PlacesSection places = new PlacesSection();
    public StructuresSection structures = new StructuresSection();
    public AgentSection agent = new AgentSection();
    public AutonomySection autonomy = new AutonomySection();
    public SkillsSection skills = new SkillsSection();

    public static class SkillsSection {
        public boolean enabled = true;
        public int promptBudget = 2400;
        public boolean requireApproval = true;
        public int modelWritesPerHour = 6;
        public int writesPerOutcome = 2;
        public int ownerCount = 64;
        public int serverCount = 32;
        public int totalChars = 96_000;
    }

    public static class ProfileConfig {
        public String provider = "anthropic";
        public String model = "claude-sonnet-5-5";
        public String apiKey = "env:ANTHROPIC_API_KEY";
        public String baseUrl = null;
        public List<RoutingRule> routing = null;
        public Double temperature = 0.7;
        public int maxTokens = 1024;
    }

    public static class CompanionSection {
        public String name = "Friendai";
        public String description = "A friendly and resourceful Minecraft companion. Keeps answers short and helpful.";
        public String language = "auto";
        public ChunkLoadingSection chunkLoading = new ChunkLoadingSection();
    }

    /** Whether the companion keeps its chunk loaded, and how many chunks around it tick. */
    public static class ChunkLoadingSection {
        public boolean enabled = true;
        public int radius = 1;
    }

    /** Special structures (golems, portals). */
    public static class StructuresSection {
        /** How many mobs of a structure's kind may already stand near the site before another is built. */
        public int maxNearbySpawned = 4;
    }

    /** The agent layer above the brain: goals, tool routing, the planner and initiative. */
    public static class AgentSection {
        /** Send only the tool groups a turn needs; the model can unlock more with request_tools. */
        public boolean toolRouting = true;
        /** passive (never starts on its own), assistive (nudges toward an active goal) or autonomous. */
        public String autonomy = "assistive";
        /** How often a goal may be replanned before the companion asks the player for help. */
        public int maxReplans = 3;
        /** Seconds between nudges toward an unfinished goal while the queue is idle. */
        public int goalTickSec = 30;
        /** Character budget of the system prompt; 0 means unlimited. */
        public int promptBudget = 24_000;
        /** Name of the profile used for the planner call; null uses the active profile. */
        public String planner = null;
        /** Total planner attempts, including the first request. */
        public int plannerRetries = 2;

        public int plannerTimeoutSec = 60;
    }

    public static class ChatSection {
        public String prefix = "@";
        public boolean respondToName = true;
        public boolean listenAll = false;
        public int maxReplyChars = 256;
        public boolean guestsEnabled = true;
        public int hearingRadius = 32;
        public int guestCooldownMs = 5000;
    }

    public static class BrainSection {
        public int maxToolStepsPerTurn = 8;
        public int historyMessages = 30;
        public int requestTimeoutSec = 60;
        public int cooldownMs = 500;
    }

    public static class TasksSection {
        public boolean defaultFollow = true;
        public int mineSearchRadius = 32;
        public int taskTimeoutSec = 300;
        public int treeSearchRadius = 32;
        public boolean fellCollectDrops = true;
        public boolean mineCollectDrops = true;
        public boolean treePerception = true;
        public int treePerceptionRadius = 12;
        public int queueMaxJobs = 64;
        public int queueMaxPlans = 8;
        public int queueBlockedNoticeSec = 30;
        public int queueMaxStepsPerAdd = 32;
        public int freeTimeWatchdogSec = 120;
        public ResourceSection resources = new ResourceSection();
        public TradingSection trading = new TradingSection();
    }

    /** Local merchant observation, restock waits and bounded piglin order defaults. */
    public static class TradingSection {
        public boolean enabled = true;
        public boolean idleRefresh = true;
        public int observationRadius = 64;
        public int quoteFreshTicks = 1_200;
        public int locationFreshTicks = 12_000;
        public int idleRefreshIntervalTicks = 200;
        public int waitTicks = 2_400;
        public int maxWaitTicks = 12_000;
        public int maxBarterIngots = 64;
        public int barterDefaultIngots = 8;
    }

    public static class ResourceSection {
        public boolean allowExpeditions = true;
        public int localRadius = 64;
        public int localTicks = 2_400;
        public int expeditionRadius = 512;
        public int expeditionTicks = 12_000;
        public int observationRadius = 64;
        public boolean waitForOwner = true;
        public int ownerSightRadius = 32;
        public int ownerHoldDistance = 48;
        public int ownerResumeDistance = 24;

        /** A validated escort policy; a hand-edited config that breaks the ordering is repaired here. */
        public dev.friendai.knowledge.search.EscortPolicy.Config escort() {
            int sight = Math.max(8, Math.min(128, ownerSightRadius));
            int hold = Math.max(sight + 1, Math.min(512, ownerHoldDistance));
            int resume = Math.max(1, Math.min(hold - 1, ownerResumeDistance));
            return new dev.friendai.knowledge.search.EscortPolicy.Config(sight, hold, resume, waitForOwner);
        }
    }

    public static class MemorySection {
        public boolean enabled = true;
        public int maxFactChars = 160;
        public int maxFactsPerPlayer = 20;
        public int maxWorldFacts = 30;

        public MemoryLimits limits() {
            return new MemoryLimits(maxFactChars, maxFactsPerPlayer, maxWorldFacts);
        }
    }

    /** How the companion finds its way: what it may dig, place and gather on the way. */
    public static class PathingSection {
        public boolean allowBreak = true;
        public boolean allowPlace = true;
        public List<String> breakBlacklist = new ArrayList<>(PathingConfig.DEFAULTS.breakBlacklist());
        public List<String> placeBlacklist = new ArrayList<>(PathingConfig.DEFAULTS.placeBlacklist());
        public int searchBudgetMs = 2;
        public int maxNodes = 20_000;
        public int maxGatherBlocks = 64;

        public PathingConfig toPathingConfig() {
            return new PathingConfig(
                    allowBreak,
                    allowPlace,
                    breakBlacklist == null ? List.of() : breakBlacklist,
                    placeBlacklist == null ? List.of() : placeBlacklist,
                    searchBudgetMs,
                    maxNodes,
                    maxGatherBlocks);
        }
    }

    /** What the companion remembers about noteworthy places, and how many of them reach the model. */
    public static class PlacesSection {
        public boolean enabled = true;
        public boolean autoRemember = true;
        public int autoMineMinBlocks = 16;
        public int maxPlaces = 128;
        public int mergeRadius = 16;
        public int promptLimit = 6;
        public int promptBudget = 1_200;
        public int plannerLimit = 40;
    }

    /** Free time: when the companion may work on its own, how often it asks the model, and what it protects. */
    public static class AutonomySection {
        public boolean enabled = true;
        public boolean idleStart = true;
        public int idleMinutes = 5;
        public int resumeAfterSec = 60;
        public boolean whileOffline = true;
        public int shiftMinutes = 20;
        public int maxShiftsPerHour = 6;
        public int offlineShiftsPerHour = 2;
        public int failCooldownMin = 10;
        public boolean protectPlaces = true;
        public boolean allowBuilding = true;
        public List<String> keepItems = new ArrayList<>(AutonomySettings.DEFAULT_KEEP);

        public AutonomySettings toSettings() {
            return new AutonomySettings(
                    enabled,
                    idleStart,
                    idleMinutes,
                    resumeAfterSec,
                    whileOffline,
                    shiftMinutes,
                    maxShiftsPerHour,
                    offlineShiftsPerHour,
                    failCooldownMin,
                    protectPlaces,
                    allowBuilding,
                    keepItems);
        }
    }

    public static ServerConfig defaults() {
        ServerConfig c = new ServerConfig();
        c.profiles.put("main", new ProfileConfig());
        return c;
    }

    /**
     * Version 8 enabled expeditions for existing servers once; later config edits retain their chosen value.
     * Version 9 added the {@code agent} section, whose field defaults fill in old files.
     * Version 10 enables resource observations out to 64 blocks by default.
     * Version 11 added the {@code places} section, whose field defaults fill in old files.
     * Version 12 added the {@code autonomy} section, whose field defaults fill in old files.
     * Trading settings use defaults when absent, including for older configurations.
     * Version 14 added the search escort fields, whose defaults fill in old files.
     * Version 15 added plannerRetries, whose field default fills in old files.
     * Version 16 added the free-time watchdog timeout, defaulting to 120 seconds.
     */
    public static JsonObject migrate(JsonObject json, int from) {
        if (from < 13 && json.has("companion") && json.get("companion").isJsonObject()) {
            JsonObject companion = json.getAsJsonObject("companion");
            if (!companion.has("description") && companion.has("persona")) {
                companion.add("description", companion.get("persona"));
            }
            companion.remove("persona");
        }
        if (from < 8) {
            JsonObject tasks = json.has("tasks") && json.get("tasks").isJsonObject()
                    ? json.getAsJsonObject("tasks")
                    : new JsonObject();
            JsonObject resources =
                    tasks.has("resources") && tasks.get("resources").isJsonObject()
                            ? tasks.getAsJsonObject("resources")
                            : new JsonObject();
            resources.addProperty("allowExpeditions", true);
            tasks.add("resources", resources);
            json.add("tasks", tasks);
        }
        if (from < 10) {
            JsonObject tasks = json.has("tasks") && json.get("tasks").isJsonObject()
                    ? json.getAsJsonObject("tasks")
                    : new JsonObject();
            JsonObject resources =
                    tasks.has("resources") && tasks.get("resources").isJsonObject()
                            ? tasks.getAsJsonObject("resources")
                            : new JsonObject();
            resources.addProperty("observationRadius", 64);
            tasks.add("resources", resources);
            json.add("tasks", tasks);
        }
        if (from < 16) {
            JsonObject tasks = json.has("tasks") && json.get("tasks").isJsonObject()
                    ? json.getAsJsonObject("tasks")
                    : new JsonObject();
            if (!tasks.has("freeTimeWatchdogSec")) tasks.addProperty("freeTimeWatchdogSec", 120);
            json.add("tasks", tasks);
        }
        // Version 11's trading section is filled by field defaults when older JSON is deserialized.
        return json;
    }

    /** Fills missing sections and clamps ranges. Returns English descriptions of what was fixed, for the log. */
    public List<String> validate() {
        List<String> problems = new ArrayList<>();
        if (skills == null) skills = new SkillsSection();
        skills.promptBudget = clamp(problems, "skills.promptBudget", skills.promptBudget, 200, 6000);
        skills.modelWritesPerHour = clamp(problems, "skills.modelWritesPerHour", skills.modelWritesPerHour, 0, 60);
        skills.writesPerOutcome = clamp(problems, "skills.writesPerOutcome", skills.writesPerOutcome, 0, 2);
        skills.ownerCount = clamp(problems, "skills.ownerCount", skills.ownerCount, 1, 64);
        skills.serverCount = clamp(problems, "skills.serverCount", skills.serverCount, 1, 32);
        skills.totalChars = clamp(problems, "skills.totalChars", skills.totalChars, 4000, 96_000);
        if (profiles == null) {
            profiles = new LinkedHashMap<>();
            problems.add("profiles was missing");
        }
        if (companion == null) {
            companion = new CompanionSection();
            problems.add("companion was missing");
        }
        if (companion.chunkLoading == null) {
            companion.chunkLoading = new ChunkLoadingSection();
            problems.add("companion.chunkLoading was missing");
        }
        companion.chunkLoading.radius =
                clamp(problems, "companion.chunkLoading.radius", companion.chunkLoading.radius, 0, 4);
        if (chat == null) {
            chat = new ChatSection();
            problems.add("chat was missing");
        }
        if (brain == null) {
            brain = new BrainSection();
            problems.add("brain was missing");
        }
        if (tasks == null) {
            tasks = new TasksSection();
            problems.add("tasks was missing");
        }
        if (tasks.resources == null) {
            tasks.resources = new ResourceSection();
            problems.add("tasks.resources was missing");
        }
        tasks.resources.localRadius =
                clamp(problems, "tasks.resources.localRadius", tasks.resources.localRadius, 16, 128);
        tasks.resources.expeditionRadius = clamp(
                problems,
                "tasks.resources.expeditionRadius",
                tasks.resources.expeditionRadius,
                tasks.resources.localRadius,
                2_048);
        tasks.resources.localTicks =
                clamp(problems, "tasks.resources.localTicks", tasks.resources.localTicks, 200, 12_000);
        tasks.resources.expeditionTicks = clamp(
                problems,
                "tasks.resources.expeditionTicks",
                tasks.resources.expeditionTicks,
                tasks.resources.localTicks,
                48_000);
        tasks.resources.observationRadius =
                clamp(problems, "tasks.resources.observationRadius", tasks.resources.observationRadius, 4, 64);
        tasks.resources.ownerSightRadius =
                clamp(problems, "tasks.resources.ownerSightRadius", tasks.resources.ownerSightRadius, 8, 128);
        tasks.resources.ownerHoldDistance = clamp(
                problems,
                "tasks.resources.ownerHoldDistance",
                tasks.resources.ownerHoldDistance,
                tasks.resources.ownerSightRadius + 1,
                512);
        tasks.resources.ownerResumeDistance = clamp(
                problems,
                "tasks.resources.ownerResumeDistance",
                tasks.resources.ownerResumeDistance,
                1,
                tasks.resources.ownerHoldDistance - 1);
        if (tasks.trading == null) {
            tasks.trading = new TradingSection();
            problems.add("tasks.trading was missing");
        }
        tasks.trading.observationRadius =
                clamp(problems, "tasks.trading.observationRadius", tasks.trading.observationRadius, 8, 64);
        tasks.trading.quoteFreshTicks =
                clamp(problems, "tasks.trading.quoteFreshTicks", tasks.trading.quoteFreshTicks, 20, 72_000);
        tasks.trading.locationFreshTicks =
                clamp(problems, "tasks.trading.locationFreshTicks", tasks.trading.locationFreshTicks, 20, 72_000);
        tasks.trading.idleRefreshIntervalTicks = clamp(
                problems,
                "tasks.trading.idleRefreshIntervalTicks",
                tasks.trading.idleRefreshIntervalTicks,
                200,
                12_000);
        tasks.trading.maxWaitTicks =
                clamp(problems, "tasks.trading.maxWaitTicks", tasks.trading.maxWaitTicks, 20, 12_000);
        tasks.trading.waitTicks =
                clamp(problems, "tasks.trading.waitTicks", tasks.trading.waitTicks, 20, tasks.trading.maxWaitTicks);
        tasks.trading.maxBarterIngots =
                clamp(problems, "tasks.trading.maxBarterIngots", tasks.trading.maxBarterIngots, 1, 64);
        tasks.trading.barterDefaultIngots = clamp(
                problems,
                "tasks.trading.barterDefaultIngots",
                tasks.trading.barterDefaultIngots,
                1,
                tasks.trading.maxBarterIngots);
        if (memory == null) {
            memory = new MemorySection();
            problems.add("memory was missing");
        }
        if (pathing == null) {
            pathing = new PathingSection();
            problems.add("pathing was missing");
        }
        if (places == null) {
            places = new PlacesSection();
            problems.add("places was missing");
        }
        places.autoMineMinBlocks = clamp(problems, "places.autoMineMinBlocks", places.autoMineMinBlocks, 1, 256);
        places.maxPlaces = clamp(problems, "places.maxPlaces", places.maxPlaces, 8, 1_024);
        places.mergeRadius = clamp(problems, "places.mergeRadius", places.mergeRadius, 1, 64);
        places.promptLimit = clamp(problems, "places.promptLimit", places.promptLimit, 1, 12);
        places.promptBudget = clamp(problems, "places.promptBudget", places.promptBudget, 200, 4_000);
        places.plannerLimit = clamp(problems, "places.plannerLimit", places.plannerLimit, 1, 128);
        if (structures == null) {
            structures = new StructuresSection();
            problems.add("structures was missing");
        }
        if (agent == null) {
            agent = new AgentSection();
            problems.add("agent was missing");
        }
        agent.maxReplans = clamp(problems, "agent.maxReplans", agent.maxReplans, 0, 10);
        agent.goalTickSec = clamp(problems, "agent.goalTickSec", agent.goalTickSec, 10, 600);
        agent.plannerTimeoutSec = clamp(problems, "agent.plannerTimeoutSec", agent.plannerTimeoutSec, 10, 300);
        agent.plannerRetries = clamp(problems, "agent.plannerRetries", agent.plannerRetries, 1, 4);
        if (agent.planner != null) {
            agent.planner = agent.planner.strip();
            if (agent.planner.isEmpty()) {
                agent.planner = null;
            } else if (!profiles.containsKey(agent.planner)) {
                problems.add("agent.planner " + agent.planner + " is not a configured profile, using active profile");
                agent.planner = null;
            }
        }
        if (agent.promptBudget != 0) {
            agent.promptBudget = clamp(problems, "agent.promptBudget", agent.promptBudget, 2_000, 60_000);
        }
        if (agent.autonomy == null
                || !List.of("passive", "assistive", "autonomous")
                        .contains(agent.autonomy.toLowerCase(java.util.Locale.ROOT))) {
            problems.add(
                    "agent.autonomy " + agent.autonomy + " is not passive, assistive or autonomous, using assistive");
            agent.autonomy = "assistive";
        } else {
            agent.autonomy = agent.autonomy.toLowerCase(java.util.Locale.ROOT);
        }
        if (autonomy == null) {
            autonomy = new AutonomySection();
            problems.add("autonomy was missing");
        }
        autonomy.idleMinutes = clamp(problems, "autonomy.idleMinutes", autonomy.idleMinutes, 1, 120);
        autonomy.resumeAfterSec = clamp(problems, "autonomy.resumeAfterSec", autonomy.resumeAfterSec, 10, 900);
        autonomy.shiftMinutes = clamp(problems, "autonomy.shiftMinutes", autonomy.shiftMinutes, 3, 120);
        autonomy.maxShiftsPerHour = clamp(problems, "autonomy.maxShiftsPerHour", autonomy.maxShiftsPerHour, 1, 60);
        autonomy.offlineShiftsPerHour =
                clamp(problems, "autonomy.offlineShiftsPerHour", autonomy.offlineShiftsPerHour, 0, 60);
        autonomy.failCooldownMin = clamp(problems, "autonomy.failCooldownMin", autonomy.failCooldownMin, 1, 240);
        autonomy.keepItems = keepItems(problems, autonomy.keepItems);
        structures.maxNearbySpawned =
                clamp(problems, "structures.maxNearbySpawned", structures.maxNearbySpawned, 1, 16);
        if (pathing.breakBlacklist == null) {
            pathing.breakBlacklist = new ArrayList<>(PathingConfig.DEFAULTS.breakBlacklist());
            problems.add("pathing.breakBlacklist was missing");
        }
        if (pathing.placeBlacklist == null) {
            pathing.placeBlacklist = new ArrayList<>(PathingConfig.DEFAULTS.placeBlacklist());
            problems.add("pathing.placeBlacklist was missing");
        }
        pathing.searchBudgetMs = clamp(problems, "pathing.searchBudgetMs", pathing.searchBudgetMs, 1, 10);
        pathing.maxNodes = clamp(problems, "pathing.maxNodes", pathing.maxNodes, 1_000, 100_000);
        pathing.maxGatherBlocks = clamp(problems, "pathing.maxGatherBlocks", pathing.maxGatherBlocks, 0, 256);
        brain.maxToolStepsPerTurn = clamp(problems, "brain.maxToolStepsPerTurn", brain.maxToolStepsPerTurn, 1, 32);
        brain.historyMessages = clamp(problems, "brain.historyMessages", brain.historyMessages, 6, 200);
        brain.requestTimeoutSec = clamp(problems, "brain.requestTimeoutSec", brain.requestTimeoutSec, 5, 600);
        brain.cooldownMs = clamp(problems, "brain.cooldownMs", brain.cooldownMs, 0, 60_000);
        chat.maxReplyChars = clamp(problems, "chat.maxReplyChars", chat.maxReplyChars, 32, 256);
        chat.hearingRadius = clamp(problems, "chat.hearingRadius", chat.hearingRadius, 4, 64);
        chat.guestCooldownMs = clamp(problems, "chat.guestCooldownMs", chat.guestCooldownMs, 0, 60_000);
        tasks.mineSearchRadius = clamp(problems, "tasks.mineSearchRadius", tasks.mineSearchRadius, 4, 64);
        tasks.taskTimeoutSec = clamp(problems, "tasks.taskTimeoutSec", tasks.taskTimeoutSec, 10, 3600);
        tasks.queueMaxJobs = clamp(problems, "tasks.queueMaxJobs", tasks.queueMaxJobs, 1, 128);
        tasks.queueMaxPlans = clamp(problems, "tasks.queueMaxPlans", tasks.queueMaxPlans, 1, 16);
        tasks.queueBlockedNoticeSec =
                clamp(problems, "tasks.queueBlockedNoticeSec", tasks.queueBlockedNoticeSec, 5, 600);
        tasks.queueMaxStepsPerAdd = clamp(problems, "tasks.queueMaxStepsPerAdd", tasks.queueMaxStepsPerAdd, 1, 64);
        tasks.freeTimeWatchdogSec = clamp(problems, "tasks.freeTimeWatchdogSec", tasks.freeTimeWatchdogSec, 1, 600);
        tasks.treeSearchRadius = clamp(problems, "tasks.treeSearchRadius", tasks.treeSearchRadius, 4, 64);
        tasks.treePerceptionRadius = clamp(problems, "tasks.treePerceptionRadius", tasks.treePerceptionRadius, 4, 24);
        memory.maxFactChars = clamp(problems, "memory.maxFactChars", memory.maxFactChars, 40, 500);
        memory.maxFactsPerPlayer = clamp(problems, "memory.maxFactsPerPlayer", memory.maxFactsPerPlayer, 1, 100);
        memory.maxWorldFacts = clamp(problems, "memory.maxWorldFacts", memory.maxWorldFacts, 1, 200);
        return problems;
    }

    private static final int MAX_KEEP_ITEMS = 32;
    private static final java.util.regex.Pattern ITEM_OR_TAG =
            java.util.regex.Pattern.compile("#?[a-z0-9_.-]+:[a-z0-9_./-]+");

    /** Drops entries that are not an item id or a {@code #tag}, and keeps at most {@value #MAX_KEEP_ITEMS}. */
    private static List<String> keepItems(List<String> problems, List<String> items) {
        if (items == null) {
            problems.add("autonomy.keepItems was missing");
            return new ArrayList<>(AutonomySettings.DEFAULT_KEEP);
        }
        List<String> valid = new ArrayList<>();
        for (String item : items) {
            if (item == null || !ITEM_OR_TAG.matcher(item).matches()) {
                problems.add("autonomy.keepItems entry " + item + " is not an item id or tag, dropped");
            } else if (valid.size() < MAX_KEEP_ITEMS) {
                valid.add(item);
            }
        }
        if (items.size() > valid.size() && valid.size() == MAX_KEEP_ITEMS) {
            problems.add("autonomy.keepItems keeps only the first " + MAX_KEEP_ITEMS + " entries");
        }
        return valid;
    }

    private static int clamp(List<String> problems, String name, int value, int min, int max) {
        int clamped = Math.max(min, Math.min(max, value));
        if (clamped != value) {
            problems.add(name + " " + value + " is outside " + min + ".." + max + ", using " + clamped);
        }
        return clamped;
    }

    public LlmProfile toLlmProfile(Function<String, String> env) {
        return toLlmProfile(activeProfile, env);
    }

    /** The named profile as an LLM profile; the planner may use a different one from the main brain. */
    public LlmProfile toLlmProfile(String profileName, Function<String, String> env) {
        ProfileConfig p = profiles == null ? null : profiles.get(profileName);
        if (p == null) {
            throw new ConfigException(UserMessage.of("friendai.error.config.profile_missing", profileName));
        }
        return new LlmProfile(
                p.provider,
                p.model,
                SecretResolver.resolve(p.apiKey, env),
                p.baseUrl,
                p.routing,
                p.temperature,
                p.maxTokens);
    }
}
