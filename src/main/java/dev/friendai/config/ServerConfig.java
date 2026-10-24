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
    public static final int VERSION = 4;

    public int version = VERSION;
    public String activeProfile = "main";
    public Map<String, ProfileConfig> profiles = new LinkedHashMap<>();
    public CompanionSection companion = new CompanionSection();
    public ChatSection chat = new ChatSection();
    public BrainSection brain = new BrainSection();
    public TasksSection tasks = new TasksSection();
    public MemorySection memory = new MemorySection();
    public PathingSection pathing = new PathingSection();

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
    }

    public static class ChatSection {
        public String prefix = "@";
        public boolean respondToName = true;
        public boolean listenAll = false;
        public int maxReplyChars = 256;
        public boolean guestsEnabled = true;
        public int hearingRadius = 16;
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

    /** Older files keep their values; missing fields keep their field-initializer defaults. */
    public static JsonObject migrate(JsonObject json, int from) {
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
        if (memory == null) {
            memory = new MemorySection();
            problems.add("memory was missing");
        }
        if (pathing == null) {
            pathing = new PathingSection();
            problems.add("pathing was missing");
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
        ProfileConfig p = profiles == null ? null : profiles.get(activeProfile);
        if (p == null) {
            throw new ConfigException(UserMessage.of("friendai.error.config.profile_missing", activeProfile));
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
