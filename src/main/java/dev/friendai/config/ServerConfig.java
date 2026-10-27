package dev.friendai.config;

import com.google.gson.JsonObject;
import dev.friendai.llm.provider.LlmProfile;
import dev.friendai.memory.MemoryLimits;
import dev.friendai.llm.provider.RoutingRule;
import dev.friendai.pathing.PathingConfig;
import dev.friendai.text.UserMessage;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** friendai-server.json. */
public class ServerConfig {
    public static final int VERSION = 10;

    public int version = VERSION;
    public String activeProfile = "main";
    public Map<String, ProfileConfig> profiles = new LinkedHashMap<>();
    public CompanionSection companion = new CompanionSection();
    public ChatSection chat = new ChatSection();
    public BrainSection brain = new BrainSection();
    public TasksSection tasks = new TasksSection();
    public MemorySection memory = new MemorySection();
    public PathingSection pathing = new PathingSection();
    public StructuresSection structures = new StructuresSection();
    public AgentSection agent = new AgentSection();

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
        public String persona = "A friendly and resourceful Minecraft companion. Keeps answers short and helpful.";
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
        public ResourceSection resources = new ResourceSection();
    }

    public static class ResourceSection {
        public boolean allowExpeditions = true;
        public int localRadius = 64;
        public int localTicks = 2_400;
        public int expeditionRadius = 512;
        public int expeditionTicks = 12_000;
        public int observationRadius = 64;
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

    public static ServerConfig defaults() {
        ServerConfig c = new ServerConfig();
        c.profiles.put("main", new ProfileConfig());
        return c;
    }

    /**
     * Version 8 enabled expeditions for existing servers once; later config edits retain their chosen value.
     * Version 9 added the {@code agent} section, whose field defaults fill in old files.
     * Version 10 enables resource observations out to 64 blocks by default.
     */
    public static JsonObject migrate(JsonObject json, int from) {
        if (from < 8) {
            JsonObject tasks = json.has("tasks") && json.get("tasks").isJsonObject()
                    ? json.getAsJsonObject("tasks")
                    : new JsonObject();
            JsonObject resources = tasks.has("resources") && tasks.get("resources").isJsonObject()
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
            JsonObject resources = tasks.has("resources") && tasks.get("resources").isJsonObject()
                    ? tasks.getAsJsonObject("resources")
                    : new JsonObject();
            resources.addProperty("observationRadius", 64);
            tasks.add("resources", resources);
            json.add("tasks", tasks);
        }
        return json;
    }

    /** Fills missing sections and clamps ranges. Returns English descriptions of what was fixed, for the log. */
    public List<String> validate() {
        List<String> problems = new ArrayList<>();
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
        companion.chunkLoading.radius = clamp(
                problems, "companion.chunkLoading.radius", companion.chunkLoading.radius, 0, 4);
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
        tasks.resources.localRadius = clamp(problems, "tasks.resources.localRadius", tasks.resources.localRadius, 16, 128);
        tasks.resources.expeditionRadius = clamp(problems, "tasks.resources.expeditionRadius",
                tasks.resources.expeditionRadius, tasks.resources.localRadius, 2_048);
        tasks.resources.localTicks = clamp(problems, "tasks.resources.localTicks", tasks.resources.localTicks, 200, 12_000);
        tasks.resources.expeditionTicks = clamp(problems, "tasks.resources.expeditionTicks",
                tasks.resources.expeditionTicks, tasks.resources.localTicks, 48_000);
        tasks.resources.observationRadius = clamp(problems, "tasks.resources.observationRadius",
                tasks.resources.observationRadius, 4, 64);
        if (memory == null) {
            memory = new MemorySection();
            problems.add("memory was missing");
        }
        if (pathing == null) {
            pathing = new PathingSection();
            problems.add("pathing was missing");
        }
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
        if (agent.promptBudget != 0) {
            agent.promptBudget = clamp(problems, "agent.promptBudget", agent.promptBudget, 2_000, 60_000);
        }
        if (agent.autonomy == null
                || !List.of("passive", "assistive", "autonomous").contains(agent.autonomy.toLowerCase(java.util.Locale.ROOT))) {
            problems.add("agent.autonomy " + agent.autonomy + " is not passive, assistive or autonomous, using assistive");
            agent.autonomy = "assistive";
        } else {
            agent.autonomy = agent.autonomy.toLowerCase(java.util.Locale.ROOT);
        }
        structures.maxNearbySpawned = clamp(problems, "structures.maxNearbySpawned", structures.maxNearbySpawned, 1, 16);
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
        tasks.queueBlockedNoticeSec = clamp(problems, "tasks.queueBlockedNoticeSec", tasks.queueBlockedNoticeSec, 5, 600);
        tasks.queueMaxStepsPerAdd = clamp(problems, "tasks.queueMaxStepsPerAdd", tasks.queueMaxStepsPerAdd, 1, 64);
        tasks.treeSearchRadius = clamp(problems, "tasks.treeSearchRadius", tasks.treeSearchRadius, 4, 64);
        tasks.treePerceptionRadius =
                clamp(problems, "tasks.treePerceptionRadius", tasks.treePerceptionRadius, 4, 24);
        memory.maxFactChars = clamp(problems, "memory.maxFactChars", memory.maxFactChars, 40, 500);
        memory.maxFactsPerPlayer = clamp(problems, "memory.maxFactsPerPlayer", memory.maxFactsPerPlayer, 1, 100);
        memory.maxWorldFacts = clamp(problems, "memory.maxWorldFacts", memory.maxWorldFacts, 1, 200);
        return problems;
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
