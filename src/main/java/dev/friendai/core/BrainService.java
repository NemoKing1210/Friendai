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
import dev.friendai.place.PlaceCandidate;
import dev.friendai.place.PlaceService;
import dev.friendai.place.PlaceText;
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

    private final Map<UUID, dev.friendai.agent.plan.Planner> plannerOverrides = new HashMap<>();
    private final Map<UUID, Long> lastGoalTick = new HashMap<>();
    /** Unanswered goal nudges per owner; reset when the owner speaks or the companion starts working. */
    private final Map<UUID, Integer> nudgeStreak = new HashMap<>();
    private long lastAgentCheck;
    private final UnaryOperator<String> plannerRedactor = SecretRedactor.of(this::configuredSecrets);
    private ServerConfig config;
    private LlmGateway current;
    /** The model that drafts plans when {@code agent.planner} names a profile; null means the main one. */
    private LlmGateway plannerGateway;
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
        plannerGateway = null;
        String plannerProfile = cfg.agent.planner;
        if (plannerProfile != null && !plannerProfile.isBlank()) {
            if (cfg.profiles.containsKey(plannerProfile)) {
                try {
                    plannerGateway = new LlmClient(
                            ProviderRegistry.withBuiltins(transport), cfg.toLlmProfile(plannerProfile, System::getenv));
                } catch (ConfigException e) {
                    FriendaiMod.LOGGER.warn("The planner profile is unusable; the main profile plans instead");
                }
            } else {
                FriendaiMod.LOGGER.warn("agent.planner names an unknown profile; the main profile plans instead");
            }
        }
    }

    /** Test hook: use this planner instead of the model-backed one for one owner. */
    public void setPlannerOverride(UUID owner, dev.friendai.agent.plan.Planner planner) {
        plannerOverrides.put(owner, planner);
    }

    private dev.friendai.agent.plan.Planner plannerFor(UUID owner) {
        dev.friendai.agent.plan.Planner override = plannerOverrides.get(owner);
        if (override != null) {
            return override;
        }
        return new dev.friendai.agent.plan.LlmPlanner(
                () -> plannerGateway != null ? plannerGateway : gatewayOverrides.getOrDefault(owner, current),
                plannerRedactor,
                config.agent.plannerTimeoutSec,
                Math.min(12, TaskSettings.queueMaxStepsPerAdd),
                8192,
                49152);
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
        if (event instanceof BrainEvent.PlayerSaid) {
            nudgeStreak.remove(owner);
        }
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
        agentTick();
    }

    /** Once a second: nudge idle companions toward an unfinished goal, as the autonomy setting allows. */
    private void agentTick() {
        long now = server.getTickCount() * 50L;
        if (now - lastAgentCheck < 1_000L) {
            return;
        }
        lastAgentCheck = now;
        dev.friendai.brain.AgentSettings.Autonomy autonomy =
                dev.friendai.brain.AgentSettings.Autonomy.parse(config.agent.autonomy);
        if (autonomy == dev.friendai.brain.AgentSettings.Autonomy.PASSIVE) {
            return;
        }
        var goals = FriendaiServerState.get(server).goals();
        for (Map.Entry<UUID, Entry> entry : new ArrayList<>(entries.entrySet())) {
            UUID owner = entry.getKey();
            CompanionEntity companion = CompanionManager.get(server).find(owner).orElse(null);
            if (companion == null) {
                continue;
            }
            var board = goals.board(owner);
            var primary = board.primary();
            boolean active = primary.isPresent()
                    && primary.get().status() == dev.friendai.agent.goal.GoalStatus.ACTIVE;
            long last = lastGoalTick.computeIfAbsent(owner, id -> now);
            boolean idle = queueIdleOrBlocked(companion);
            if (!idle) {
                nudgeStreak.remove(owner); // working again: the next idle spell starts with a fresh interval
            }
            int streak = nudgeStreak.getOrDefault(owner, 0);
            if (dev.friendai.agent.tick.NudgeBackoff.exhausted(streak)) {
                continue;
            }
            var facts = new dev.friendai.agent.tick.TickFacts(
                    active,
                    idle,
                    companion.owner().isPresent(),
                    companion.taskRunner().paused() || companion.taskRunner().suspended()
                            || companion.isSleeping() || companion.getTarget() != null,
                    entry.getValue().brain().isThinking(),
                    now,
                    last,
                    autonomy,
                    dev.friendai.agent.tick.NudgeBackoff.intervalSec(config.agent.goalTickSec, streak));
            if (dev.friendai.agent.tick.GoalTickPolicy.shouldAskWhatNext(facts)) {
                lastGoalTick.put(owner, now);
                nudgeStreak.merge(owner, 1, Integer::sum);
                post(owner, new BrainEvent.IdleNudge());
            } else if (dev.friendai.agent.tick.GoalTickPolicy.shouldTick(facts)) {
                lastGoalTick.put(owner, now);
                nudgeStreak.merge(owner, 1, Integer::sum);
                var guard = new dev.friendai.task.queue.CompanionGuardFacts(companion);
                guard.refresh();
                post(owner, new BrainEvent.GoalTick(primary.get().text(),
                        board.unmet(primary.get().id(), guard).stream()
                                .map(dev.friendai.agent.goal.Criterion::text).toList()));
            }
        }
    }

    /** Nothing runs and no plan moves by itself; a plan waiting for a decision counts as blocked. */
    private static boolean queueIdleOrBlocked(CompanionEntity companion) {
        var runner = companion.taskRunner();
        var queue = runner.queue();
        if (queue.jobCount() == 0) {
            return !runner.hasWork();
        }
        if (queue.active().isPresent()) {
            return false;
        }
        boolean directWork = queue.jobs().stream()
                .anyMatch(job -> job.planId() == 0 && !dev.friendai.queue.JobQueue.terminal(job));
        boolean planMoving = queue.plans().stream()
                .anyMatch(plan -> queue.planStatus(plan.id()) == dev.friendai.queue.PlanStatus.ACTIVE);
        return !directWork && !planMoving;
    }

    /** Lets one companion's brain consult the planner and queue what it proposes. */
    private final class GamePlannerPort implements dev.friendai.brain.PlannerPort {
        private final UUID owner;
        private final CompanionEntity companion;
        /** Plans the planner cancels itself; their end is not news and must not trigger another replan. */
        private final java.util.Set<Long> replaced;

        GamePlannerPort(UUID owner, CompanionEntity companion, java.util.Set<Long> replaced) {
            this.owner = owner;
            this.companion = companion;
            this.replaced = replaced;
        }

        @Override
        public boolean ownsPlan(long planId) {
            return FriendaiServerState.get(server).goals().board(owner).primary()
                    .map(goal -> goal.planId() == planId)
                    .orElse(false);
        }

        @Override
        public Optional<dev.friendai.agent.plan.PlannerInput> input(Caller caller, String failure) {
            var board = FriendaiServerState.get(server).goals().board(owner);
            var primary = board.primary();
            if (primary.isEmpty() || primary.get().status() != dev.friendai.agent.goal.GoalStatus.ACTIVE) {
                return Optional.empty();
            }
            Map<String, JsonObject> schemas = new java.util.LinkedHashMap<>();
            StringBuilder digest = new StringBuilder();
            taskTools.forEach((name, def) -> {
                if (caller.access().allows(def.minAccess())) {
                    schemas.put(name, def.schema());
                    digest.append("- ").append(describeTool(def)).append('\n');
                }
            });
            var facts = new dev.friendai.task.queue.CompanionGuardFacts(companion);
            facts.refresh();
            String queue = dev.friendai.queue.QueueText.show(companion.taskRunner().queue(), facts, null, 20, false);
            return Optional.of(new dev.friendai.agent.plan.PlannerInput(
                    primary.get(),
                    new CompanionPerception(companion).describe(caller),
                    digest.toString(),
                    PlaceText.planner(
                            config.places.enabled && caller.access().allows(AccessLevel.TRUSTED)
                                    ? FriendaiServerState.get(server).places().book(owner).all()
                                    : List.of(),
                            config.places.plannerLimit),
                    Optional.of(queue),
                    primary.get().lessons(),
                    failure,
                    schemas,
                    taskTools.keySet()));
        }

        @Override
        public CompletableFuture<dev.friendai.agent.plan.PlanProposal> propose(dev.friendai.agent.plan.PlannerInput input) {
            return plannerFor(owner).propose(input);
        }

        @Override
        public ToolResult apply(Caller caller, dev.friendai.agent.plan.PlanProposal proposal) {
            var goals = FriendaiServerState.get(server).goals();
            var board = goals.board(owner);
            var primary = board.primary();
            var executor = tools.bind(companion);
            if (primary.isPresent() && primary.get().planId() != 0
                    && companion.taskRunner().queue().plan(primary.get().planId()).isPresent()) {
                long oldPlan = primary.get().planId();
                // Unlink first and silence the plan's end event: cancelling must not count as a failure.
                board.linkPlan(primary.get().id(), 0);
                replaced.add(oldPlan);
                JsonObject cancel = new JsonObject();
                cancel.addProperty("action", "cancel");
                cancel.addProperty("plan", oldPlan);
                executor.execute(new ToolCall("planner", "queue_edit", cancel, cancel.toString(), null), caller);
            }
            // queue_add links a new plan only to a goal that has none.
            primary.ifPresent(goal -> board.linkPlan(goal.id(), 0));
            JsonObject add = new JsonObject();
            add.addProperty("goal", primary.map(dev.friendai.agent.goal.Goal::text).orElse("planned work"));
            add.add("steps", proposal.stepsJson());
            ToolResult result = executor.execute(new ToolCall("planner", "queue_add", add, add.toString(), null), caller);
            goals.save(owner);
            return result;
        }

        /** {@code name(arg*, arg): first sentence of the description}; a star marks a required argument. */
        private static String describeTool(TaskToolDef def) {
            JsonObject schema = def.schema();
            java.util.Set<String> required = new java.util.HashSet<>();
            if (schema.has("required")) {
                schema.getAsJsonArray("required").forEach(e -> required.add(e.getAsString()));
            }
            java.util.List<String> args = new ArrayList<>();
            if (schema.has("properties")) {
                for (String arg : schema.getAsJsonObject("properties").keySet()) {
                    args.add(arg + (required.contains(arg) ? "*" : ""));
                }
            }
            String description = def.description() == null ? "" : def.description();
            int stop = description.indexOf(". ");
            String first = stop > 0 ? description.substring(0, stop + 1) : description;
            return def.name() + "(" + String.join(", ", args) + "): "
                    + (first.length() > 200 ? first.substring(0, 200) : first);
        }
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
        java.util.Set<Long> replaced = new java.util.HashSet<>();
        brain.setPlannerPort(new GamePlannerPort(owner, companion, replaced));
        brain.setAgentSettings(() -> new dev.friendai.brain.AgentSettings(
                config.agent.toolRouting,
                dev.friendai.brain.AgentSettings.Autonomy.parse(config.agent.autonomy),
                config.agent.maxReplans,
                config.agent.goalTickSec,
                config.agent.promptBudget,
                config.agent.plannerTimeoutSec));
        brain.setPlaceSource(caller -> config.places.enabled
                ? PlaceService.promptBlock(companion, caller)
                : "");
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
                if (replaced.remove(planId)) {
                    return;
                }
                post(owner, new BrainEvent.PlanFinished(planId, goal, success, summary,
                        agent.extra(success, planId, goal, summary)));
            }

            @Override
            public void onPlanBlocked(long planId, String goal, long jobId, String toolName, String reason, int remaining) {
                if (replaced.contains(planId)) {
                    return;
                }
                post(owner, new BrainEvent.PlanBlocked(planId, goal, jobId, toolName, reason, remaining,
                        agent.extra(false, planId, toolName, reason)));
            }

            @Override
            public void onNotice(String text) {
                post(owner, new BrainEvent.WorldNotice(text));
            }

            @Override
            public void onPlace(PlaceCandidate candidate, String toolName) {
                // Not news for the model: a successful task quietly refreshes the owner's place book.
                PlaceService.autoRegister(companion, candidate);
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
