package dev.friendai.core;

import dev.friendai.FriendaiMod;
import dev.friendai.brain.AccessResolver;
import dev.friendai.brain.BrainEvent;
import dev.friendai.brain.BrainSettings;
import dev.friendai.brain.CompanionBrain;
import dev.friendai.brain.LlmGateway;
import dev.friendai.brain.Persona;
import dev.friendai.brain.Speaker;
import dev.friendai.chat.CompanionSpeaker;
import dev.friendai.config.ConfigException;
import dev.friendai.config.ServerConfig;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.entity.CompanionManager;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.friendai.config.SecretResolver;
import dev.friendai.debug.DebugLog;
import dev.friendai.debug.DebugLogObserver;
import dev.friendai.debug.DebugService;
import dev.friendai.debug.LogKind;
import dev.friendai.debug.SecretRedactor;
import dev.friendai.debug.TaskRecord;
import dev.friendai.llm.LlmClient;
import dev.friendai.llm.model.ToolCall;
import dev.friendai.llm.model.ToolSpec;
import dev.friendai.memory.MemoryBook;
import dev.friendai.memory.MemoryLimits;
import dev.friendai.memory.MemoryPromptRenderer;
import dev.friendai.memory.MemoryStore;
import dev.friendai.tool.Caller;
import dev.friendai.tool.AccessLevel;
import dev.friendai.tool.ToolResult;
import dev.friendai.tool.builtin.MemoryAccess;
import java.util.ArrayList;
import java.util.List;
import dev.friendai.llm.http.HttpTransport;
import dev.friendai.llm.provider.ProviderRegistry;
import dev.friendai.task.Task;
import dev.friendai.task.TaskFactory;
import dev.friendai.task.TaskListener;
import dev.friendai.task.TaskSettings;
import dev.friendai.task.TaskStatus;
import dev.friendai.task.plan.StepFactory;
import dev.friendai.queue.Job;
import dev.friendai.queue.JobState;
import dev.friendai.queue.QueueSave;
import dev.friendai.task.TaskSettings;
import dev.friendai.task.plan.PlanEntry;
import dev.friendai.task.plan.PlanSave;
import dev.friendai.task.plan.SequenceTask;
import dev.friendai.tool.builtin.FriendaiTools;
import dev.friendai.tool.builtin.TaskToolDef;
import dev.friendai.tool.ToolRegistry;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.UnaryOperator;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

/** Owns one {@link CompanionBrain} per player and the LLM client they share. All methods run on the server thread. */
public final class BrainService {
    private record Entry(CompanionBrain brain) {}

    private final MinecraftServer server;
    private final ServerBrainScheduler scheduler;
    private final MemoryStore memory;
    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final Map<UUID, Entry> entries = new HashMap<>();
    private final Map<UUID, LlmGateway> gatewayOverrides = new HashMap<>();
    private final Map<UUID, UnaryOperator<Speaker>> speakerWrappers = new HashMap<>();
    private final ToolRegistry<CompanionEntity> tools;
    /** The plan-step tools by name, kept so a saved plan can be rebuilt after a reload. */
    private final Map<String, TaskToolDef> planTools;
    private final Map<String, TaskToolDef> taskTools;
    private final DebugService debug = new DebugService(SecretRedactor.of(this::configuredSecrets), System::currentTimeMillis);

    private ServerConfig config;
    private LlmGateway current;
    private String activeModel = "";
    private String activeProvider = "";

    public BrainService(MinecraftServer server, ServerConfig initial, MemoryStore memory) {
        this.server = server;
        this.scheduler = new ServerBrainScheduler(server);
        this.memory = memory;
        FriendaiTools.Builtins builtins = FriendaiTools.build(() -> this.config.tasks.mineSearchRadius, () -> this.config.tasks.treeSearchRadius, new MemoryAccess() {
            @Override
            public boolean enabled() {
                return config != null && config.memory.enabled;
            }

            @Override
            public MemoryLimits limits() {
                return config == null ? MemoryLimits.DEFAULTS : config.memory.limits();
            }

            @Override
            public MemoryBook book(UUID owner) {
                return memory.book(owner);
            }
        });
        this.tools = builtins.registry();
        this.planTools = builtins.plannable();
        this.taskTools = builtins.taskTools();
        reload(initial);
    }

    /** Rebuilds the LLM client from the config. A broken profile gives a gateway that reports the problem. */
    public void reload(ServerConfig cfg) {
        this.config = cfg;
        ServerConfig.ProfileConfig profile = cfg.profiles.get(cfg.activeProfile);
        activeProvider = profile == null ? "?" : profile.provider;
        activeModel = profile == null ? "?" : profile.model;
        HttpTransport transport = new HttpTransport(
                httpClient, Duration.ofSeconds(cfg.brain.requestTimeoutSec), 2, Duration.ofMillis(500));
        try {
            current = new LlmClient(ProviderRegistry.withBuiltins(transport), cfg.toLlmProfile(System::getenv));
        } catch (ConfigException e) {
            current = (system, messages, toolSpecs) -> CompletableFuture.failedFuture(e);
        }
    }

    public String activeProvider() {
        return activeProvider;
    }

    public String activeModel() {
        return activeModel;
    }

    /** Test hook: use this gateway instead of the configured one for one owner. */
    public void setGatewayOverride(UUID owner, LlmGateway gateway) {
        gatewayOverrides.put(owner, gateway);
    }

    /** Test hook: wrap the speaker of one owner's brain. Must be set before the brain is created. */
    public void setSpeakerWrapper(UUID owner, UnaryOperator<Speaker> wrapper) {
        speakerWrappers.put(owner, wrapper);
    }

    public Optional<CompanionBrain> brainOf(UUID owner) {
        return Optional.ofNullable(entries.get(owner)).map(Entry::brain);
    }

    public DebugService debug() {
        return debug;
    }

    public List<String> toolNames() {
        return tools.specs().stream().map(ToolSpec::name).toList();
    }

    /** The system prompt the owner's next LLM request would carry; empty if they have no companion. */
    public Optional<String> currentPrompt(UUID owner) {
        return entryFor(owner).map(e -> e.brain().currentSystemPrompt());
    }

    /** Runs a tool by hand, as if the model had called it. Never throws. */
    public ToolResult runTool(UUID owner, String name, String argsJson) {
        Optional<CompanionEntity> companion = CompanionManager.get(server).find(owner);
        if (companion.isEmpty()) {
            return ToolResult.error("No companion");
        }
        entryFor(owner);
        DebugLog log = debug.logOf(owner);
        log.add(LogKind.MANUAL, "run " + name + " " + argsJson);
        JsonObject args = null;
        try {
            JsonElement parsed = JsonParser.parseString(argsJson);
            args = parsed.isJsonObject() ? parsed.getAsJsonObject() : null;
        } catch (RuntimeException e) {
            // stays null: the registry reports invalid JSON arguments
        }
        ToolResult result = tools.bind(companion.get()).execute(new ToolCall("manual", name, args, argsJson, null));
        log.add(LogKind.MANUAL, "result: " + (result.error() ? "ERROR " : "") + result.content());
        return result;
    }

    /** Posts a chat-style message to the owner's brain. False if they have no companion. */
    public boolean sendMessage(UUID owner, String playerName, String text) {
        if (entryFor(owner).isEmpty()) {
            return false;
        }
        post(owner, new BrainEvent.PlayerSaid(owner, playerName, text, BrainEvent.Source.CHAT));
        return true;
    }

    public void post(UUID owner, BrainEvent event) {
        entryFor(owner).ifPresent(entry -> entry.brain().post(event));
    }

    private Optional<Entry> entryFor(UUID owner) {
        Entry entry = entries.get(owner);
        if (entry == null) {
            Optional<CompanionEntity> companion = CompanionManager.get(server).find(owner);
            if (companion.isEmpty()) {
                return Optional.empty();
            }
            entry = create(owner, companion.get());
        }
        return Optional.of(entry);
    }

    private List<String> configuredSecrets() {
        List<String> secrets = new ArrayList<>();
        for (ServerConfig.ProfileConfig profile : config.profiles.values()) {
            secrets.add(SecretResolver.resolve(profile.apiKey, System::getenv));
        }
        return secrets;
    }

    public void cancel(UUID owner) {
        Entry entry = entries.get(owner);
        if (entry != null) {
            entry.brain().cancel();
        }
    }

    /** The owner's companion is gone (death, dismissal, unload): drop its brain. */
    public void onCompanionRemoved(UUID owner) {
        Entry entry = entries.remove(owner);
        if (entry != null) {
            entry.brain().cancel();
        }
    }

    /** The entity instance changed during an explicit cross-dimension teleport; rebuild its bound brain on demand. */
    public void onCompanionReplaced(UUID owner) {
        onCompanionRemoved(owner);
    }

    /** The owner left the game: keep the task (a build can resume), but make no progress while they are away. */
    public void onOwnerLeft(UUID owner) {
        cancel(owner);
        CompanionManager.get(server).find(owner).ifPresent(c -> c.taskRunner().suspend());
    }

    /** The owner came back to a still-loaded companion. */
    public void onOwnerJoined(UUID owner) {
        CompanionManager.get(server).find(owner).ifPresent(c -> c.taskRunner().resume());
    }

    /** Restore a durable task queue after the companion's saved owner and tools are available. */
    public void restoreQueue(CompanionEntity companion, String saved) {
        if (saved == null || companion.ownerUuid() == null) return;
        Optional<QueueSave.Decoded> decoded = QueueSave.decode(saved, TaskSettings.queueLimits());
        if (decoded.isEmpty()) {
            FriendaiMod.LOGGER.warn("Discarding malformed task queue for {}", companion.ownerUuid());
            return;
        }
        entryFor(companion.ownerUuid()); // Attach the listener before emitting restore notices.
        Map<Long, StepFactory> factories = new HashMap<>();
        for (Job job : decoded.get().queue().jobs()) {
            TaskToolDef def = taskTools.get(job.tool());
            if (def == null) {
                if (job.state() == JobState.PENDING || job.state() == JobState.RUNNING || job.state() == JobState.FAILED) {
                    decoded.get().queue().markMissingTool(job.id());
                    FriendaiMod.LOGGER.warn("Queued tool {} is unavailable after reload", job.tool());
                }
                continue;
            }
            JsonObject args = job.args().deepCopy();
            Caller owner = new Caller(companion.ownerUuid(), "", AccessLevel.OWNER);
            factories.put(job.id(), ctx -> def.callerFactory() == null
                    ? def.factory().create(ctx.companion(), args.deepCopy())
                    : def.callerFactory().create(ctx.companion(), owner, args.deepCopy()));
        }
        companion.taskRunner().restoreQueue(decoded.get(), factories);
        for (Job job : companion.taskRunner().queue().jobs()) {
            if (job.state() == JobState.CANCELLED && Job.MISSING_TOOL_RESULT.equals(job.result())) {
                companion.taskRunner().notice("Saved queue job #" + job.id() + " needs repair: tool " + job.tool() + " is no longer available");
            }
        }
    }

    /**
     * Rebuilds a plan saved with the world. The steps come from this server's tools, so a plan is only resumed when
     * every tool it used still exists and is still plannable. A build step that saved itself keeps its progress and
     * its scaffold; other steps start again.
     */
    public Optional<Task> restorePlan(CompanionEntity companion, String saved) {
        Optional<PlanSave.Saved> decoded = PlanSave.decode(saved);
        if (decoded.isEmpty()) {
            return Optional.empty();
        }
        PlanSave.Saved data = decoded.get();
        List<PlanEntry> entries = new ArrayList<>(data.steps().size());
        for (PlanSave.Step step : data.steps()) {
            TaskToolDef def = planTools.get(step.tool());
            if (def == null) {
                FriendaiMod.LOGGER.warn("Plan step {} is unavailable; the saved plan is not resumed", step.tool());
                return Optional.empty();
            }
            JsonObject args = step.args();
            entries.add(new PlanEntry(step.tool(), args, ctx -> def.factory().create(ctx.companion(), args)));
        }
        Task active = data.active() == null || !(companion.level() instanceof ServerLevel level)
                ? null
                : TaskFactory.restore(data.active(), level).orElse(null);
        return Optional.of(SequenceTask.resumed(data.goal(), entries, data.index(), data.done(), active,
                () -> TaskSettings.taskTimeoutSec * 20));
    }

    public void tick() {
        scheduler.tick();
    }

    /** Adds verified facts, a diagnosis and goal bookkeeping to the task and plan events of one companion. */
    private final class AgentEvents {
        private final UUID owner;
        private final CompanionEntity companion;
        private final dev.friendai.agent.recover.FailureHandler handler =
                new dev.friendai.agent.recover.FailureHandler(new dev.friendai.agent.recover.RecoveryAdvisor());
        private dev.friendai.agent.verify.WorldSnapshot last;

        AgentEvents(UUID owner, CompanionEntity companion) {
            this.owner = owner;
            this.companion = companion;
            this.last = OutcomeFacts.snapshot(companion);
        }

        /** The text to append to an event line; empty when there is nothing verified to add. */
        String extra(boolean success, long planId, String tool, String text) {
            try {
                dev.friendai.agent.verify.WorldSnapshot after = OutcomeFacts.snapshot(companion);
                String factsLine = dev.friendai.agent.verify.OutcomeChecker
                        .check(last, after, success, java.util.List.of(), new dev.friendai.task.queue.CompanionGuardFacts(companion))
                        .factsLine();
                last = after;
                var goals = FriendaiServerState.get(server).goals();
                var board = goals.board(owner);
                String extra;
                if (success) {
                    var facts = new dev.friendai.task.queue.CompanionGuardFacts(companion);
                    facts.refresh();
                    extra = handler.onSuccess(board, planId, factsLine, facts);
                } else {
                    extra = handler.onFailure(board, planId, tool, text, factsLine);
                }
                goals.save(owner);
                return extra;
            } catch (RuntimeException e) {
                FriendaiMod.LOGGER.warn("Could not build event facts", e);
                return "";
            }
        }
    }

    private Entry create(UUID owner, CompanionEntity companion) {
        CompanionSpeaker speaker = new CompanionSpeaker(
                server,
                owner,
                () -> companion.getName().getString(),
                () -> config.chat.maxReplyChars,
                companion::setThinking,
                companion::getUUID,
                () -> config.chat.hearingRadius);
        Speaker effective = speakerWrappers.getOrDefault(owner, s -> s).apply(speaker);
        CompanionBrain brain = new CompanionBrain(
                () -> new Persona(
                        companion.getName().getString(),
                        memory.book(owner).persona().orElse(config.companion.persona),
                        config.companion.language),
                () -> new BrainSettings(
                        config.brain.maxToolStepsPerTurn,
                        config.brain.historyMessages,
                        config.brain.cooldownMs,
                        config.chat.guestCooldownMs),
                () -> gatewayOverrides.getOrDefault(owner, current),
                tools.bind(companion),
                new CompanionPerception(companion),
                effective,
                scheduler,
                AccessResolver.of(owner, p -> memory.book(owner).isTrusted(p)),
                caller -> config.memory.enabled
                        ? MemoryPromptRenderer.render(memory.book(owner), owner, caller)
                        : "");
        brain.setObserver(new DebugLogObserver(debug.logOf(owner)));
        brain.setAgentSettings(() -> new dev.friendai.brain.AgentSettings(
                config.agent.toolRouting,
                dev.friendai.brain.AgentSettings.Autonomy.parse(config.agent.autonomy),
                config.agent.maxReplans,
                config.agent.goalTickSec,
                config.agent.promptBudget,
                config.agent.plannerTimeoutSec));
        brain.setGoalSource(caller -> {
            var board = FriendaiServerState.get(server).goals().board(owner);
            var primary = board.primary();
            String plan = "";
            if (primary.isPresent() && primary.get().planId() != 0) {
                long planId = primary.get().planId();
                plan = companion.taskRunner().queue().plan(planId).isPresent()
                        ? "plan #" + planId + " " + companion.taskRunner().queue().planStatus(planId).name()
                                .toLowerCase(java.util.Locale.ROOT).replace('_', ' ')
                        : "plan #" + planId + " is no longer queued";
            }
            var facts = new dev.friendai.task.queue.CompanionGuardFacts(companion);
            facts.refresh();
            return board.view(plan, facts);
        });
        AgentEvents agent = new AgentEvents(owner, companion);
        companion.taskRunner().setListener(new TaskListener() {
            @Override
            public void onFinished(int id, String toolName, TaskStatus status, String summary) {
                debug.historyOf(owner).add(new TaskRecord(
                        id, toolName, status == TaskStatus.SUCCESS ? "SUCCESS" : "FAILED", summary));
                boolean ok = status == TaskStatus.SUCCESS;
                post(owner, new BrainEvent.TaskFinished(id, toolName, ok, summary,
                        agent.extra(ok, 0, toolName, summary)));
            }

            @Override
            public void onCancelled(int id, String toolName, String reason) {
                debug.historyOf(owner).add(new TaskRecord(id, toolName, "CANCELLED", reason));
                post(owner, new BrainEvent.TaskCancelled(id, toolName, reason));
            }

            @Override
            public void onJobFinished(long jobId, long planId, String toolName, TaskStatus status, String summary) {
                if (planId != 0) { // direct jobs are recorded through onFinished/onCancelled
                    debug.historyOf(owner).add(new TaskRecord((int) jobId, toolName,
                            status == TaskStatus.SUCCESS ? "SUCCESS" : "FAILED", summary));
                }
            }

            @Override
            public void onPlanFinished(long planId, String goal, boolean success, String summary) {
                post(owner, new BrainEvent.PlanFinished(planId, goal, success, summary,
                        agent.extra(success, planId, goal, summary)));
            }

            @Override
            public void onPlanBlocked(long planId, String goal, long jobId, String toolName, String reason, int remaining) {
                post(owner, new BrainEvent.PlanBlocked(planId, goal, jobId, toolName, reason, remaining,
                        agent.extra(false, planId, toolName, reason)));
            }

            @Override
            public void onNotice(String text) {
                post(owner, new BrainEvent.WorldNotice(text));
            }

            @Override
            public void onDebug(String text) {
                debug.logOf(owner).add(LogKind.TASK, text);
            }
        });
        Entry entry = new Entry(brain);
        entries.put(owner, entry);
        FriendaiMod.LOGGER.debug("Created brain for {}", owner);
        return entry;
    }
}
