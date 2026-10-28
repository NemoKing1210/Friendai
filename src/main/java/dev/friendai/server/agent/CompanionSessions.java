package dev.friendai.server.agent;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.friendai.FriendaiMod;
import dev.friendai.agent.goal.GoalService;
import dev.friendai.agent.llm.model.ToolCall;
import dev.friendai.agent.llm.model.ToolSpec;
import dev.friendai.agent.turn.AccessResolver;
import dev.friendai.agent.turn.BrainEvent;
import dev.friendai.agent.turn.BrainSettings;
import dev.friendai.agent.turn.CompanionBrain;
import dev.friendai.agent.turn.DebugLogObserver;
import dev.friendai.agent.turn.LlmGateway;
import dev.friendai.agent.turn.Persona;
import dev.friendai.agent.turn.Speaker;
import dev.friendai.chat.CompanionSpeaker;
import dev.friendai.core.OutcomeFacts;
import dev.friendai.core.SkillService;
import dev.friendai.debug.DebugService;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.entity.CompanionManager;
import dev.friendai.execution.history.TaskRecord;
import dev.friendai.execution.tool.AccessLevel;
import dev.friendai.execution.tool.Caller;
import dev.friendai.execution.tool.ToolRegistry;
import dev.friendai.execution.tool.ToolResult;
import dev.friendai.features.memory.MemoryBook;
import dev.friendai.features.memory.MemoryLimits;
import dev.friendai.features.memory.MemoryPromptRenderer;
import dev.friendai.features.places.PlaceCandidate;
import dev.friendai.features.places.PlaceText;
import dev.friendai.features.trading.TradeAuthority;
import dev.friendai.features.trading.TradePromptSummary;
import dev.friendai.foundation.diagnostics.DebugLog;
import dev.friendai.foundation.diagnostics.LogKind;
import dev.friendai.infrastructure.config.ServerConfig;
import dev.friendai.infrastructure.persistence.memory.MemoryStore;
import dev.friendai.infrastructure.persistence.places.PlaceStore;
import dev.friendai.infrastructure.persistence.trading.TradeStore;
import dev.friendai.perception.PerceptionContext;
import dev.friendai.perception.resource.ResourceCandidates;
import dev.friendai.place.PlaceService;
import dev.friendai.server.configuration.ServerSettings;
import dev.friendai.server.execution.persistence.TaskRestoreRegistry;
import dev.friendai.task.TaskListener;
import dev.friendai.task.TaskStatus;
import dev.friendai.tool.builtin.FriendaiTools;
import dev.friendai.tool.builtin.MemoryAccess;
import dev.friendai.tool.builtin.TaskToolDef;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

/** Owns one {@link CompanionBrain} per player and the LLM client they share. All methods run on the server thread. */
public final class CompanionSessions implements AutoCloseable {
    private final MinecraftServer server;
    private final ServerBrainScheduler scheduler;
    private final MemoryStore memory;
    private final Supplier<ServerSettings> settings;
    private final SkillService skills;
    private final GoalService goals;
    private final TradeStore trading;
    private final PlaceStore places;
    private final Supplier<PerceptionContext> perceptionContext;
    private final Map<UUID, CompanionSession> sessions = new HashMap<>();
    private boolean acceptingWork = true;
    private final ToolRegistry<CompanionEntity> tools;
    /** The plan-step tools by name, kept so a saved plan can be rebuilt after a reload. */
    private final Map<String, TaskToolDef> planTools;

    private final Map<String, TaskToolDef> taskTools;
    private final AgentGatewayFactory gateways = new AgentGatewayFactory();
    private final DebugService debug;
    private final PlannerAdapter planners;
    private final FreeTimeDriver freeTime;

    private long lastAgentCheck;
    private ServerConfig config;

    public CompanionSessions(
            MinecraftServer server,
            ServerConfig initial,
            MemoryStore memory,
            Supplier<ServerSettings> settings,
            SkillService skills,
            GoalService goals,
            TradeStore trading,
            PlaceStore places,
            ResourceCandidates resources,
            dev.friendai.agent.autonomy.FreeTimeService freeTimeService) {
        this.server = server;
        this.scheduler = new ServerBrainScheduler(server);
        this.memory = memory;
        this.settings = settings;
        this.skills = skills;
        this.goals = goals;
        this.trading = trading;
        this.places = places;
        this.perceptionContext = () -> new PerceptionContext(resources, settings.get(), freeTimeService);
        this.debug = new DebugService(gateways.redactor(), System::currentTimeMillis);
        this.planners = new PlannerAdapter(scheduler, debug, gateways, () -> this.config, settings);
        FriendaiTools.Builtins builtins = FriendaiTools.build(
                () -> settings.get().tasks().mineSearchRadius(),
                () -> settings.get().tasks().treeSearchRadius(),
                new MemoryAccess() {
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
                },
                perceptionContext,
                () -> settings.get().tasks().mineCollectDrops(),
                () -> settings.get().tasks().fellCollectDrops());
        this.tools = builtins.registry();
        this.planTools = builtins.plannable();
        this.taskTools = builtins.taskTools();
        this.freeTime = new FreeTimeDriver(
                server, this, () -> this.config, settings, freeTimeService, places, taskTools, planTools);
        reload(initial);
    }

    /** Free time: the companion working on its own. */
    public FreeTimeDriver freeTime() {
        return freeTime;
    }

    Supplier<PerceptionContext> perceptionContext() {
        return perceptionContext;
    }

    /** Asks the owner's planner (or its test override) for a plan; may complete on any thread. */
    CompletableFuture<dev.friendai.agent.plan.PlanProposal> propose(
            UUID owner, dev.friendai.agent.plan.PlannerInput input) {
        return propose(owner, input, false);
    }

    CompletableFuture<dev.friendai.agent.plan.PlanProposal> propose(
            UUID owner, dev.friendai.agent.plan.PlannerInput input, boolean autonomous) {
        var companion = CompanionManager.get(server).find(owner);
        String task =
                companion.map(c -> c.executionCoordinator().currentToolName()).orElse("");
        boolean combat = companion
                .map(c -> c.combatController().state() != dev.friendai.task.combat.CombatController.State.IDLE)
                .orElse(false);
        var skillSession = skills.begin(
                owner,
                new Caller(owner, "", AccessLevel.OWNER),
                input.goal().text(),
                dev.friendai.agent.turn.TurnKind.EVENT,
                task,
                autonomous,
                true,
                combat);
        return planners.propose(
                session(owner), input.withInstructions(skillSession.sections(), config.agent.promptBudget));
    }

    /** Creates the owner's brain if it does not exist yet, so its task listener is installed. */
    void ensureBrain(UUID owner) {
        entryFor(owner);
    }

    /** Runs on the server thread, the way late brain work hops back. */
    void onServerThread(Runnable task) {
        scheduler.execute(task);
    }

    /** Rebuilds the LLM client from the config. A broken profile gives a gateway that reports the problem. */
    public void reload(ServerConfig cfg) {
        this.config = cfg;
        gateways.reload(cfg);
    }

    /** Test hook: use this planner instead of the model-backed one for one owner. */
    public void setPlannerOverride(UUID owner, dev.friendai.agent.plan.Planner planner) {
        session(owner).plannerOverride(planner);
    }

    /** The effective model used for plan drafting, after unknown or unusable profiles fall back. */
    public String plannerModel() {
        return planners.model();
    }

    /** Adds an operator-only planner lifecycle entry. Call on the server thread. */
    public void plannerDebug(UUID owner, String text) {
        debug.logOf(owner).add(LogKind.PLANNER, text);
    }

    public String activeProvider() {
        return gateways.activeProvider();
    }

    public String activeModel() {
        return gateways.activeModel();
    }

    /** Test hook: use this gateway instead of the configured one for one owner. */
    public void setGatewayOverride(UUID owner, LlmGateway gateway) {
        session(owner).gatewayOverride(gateway);
    }

    /** Test hook: wrap the speaker of one owner's brain. Must be set before the brain is created. */
    public void setSpeakerWrapper(UUID owner, UnaryOperator<Speaker> wrapper) {
        session(owner).speakerWrapper(wrapper);
    }

    public Optional<CompanionBrain> brainOf(UUID owner) {
        return Optional.ofNullable(sessions.get(owner)).map(CompanionSession::brain);
    }

    public Optional<CompanionSession> find(UUID owner) {
        return Optional.ofNullable(sessions.get(owner));
    }

    public DebugService debug() {
        return debug;
    }

    public List<String> toolNames() {
        return tools.specs().stream().map(ToolSpec::name).toList();
    }

    /** The system prompt the owner's next LLM request would carry; empty if they have no companion. */
    public Optional<String> currentPrompt(UUID owner) {
        return entryFor(owner).map(session -> session.brain().currentSystemPrompt());
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
        if (event instanceof BrainEvent.PlayerSaid said) {
            session(owner).resetNudgeStreak();
            if (owner.equals(said.playerUuid())) {
                freeTime.ownerActivity(owner, FreeTimeDriver.now());
                session(owner)
                        .profileObserver()
                        .observe(
                                said.playerName(),
                                said.text(),
                                config != null && config.memory.enabled,
                                config == null ? MemoryLimits.DEFAULTS : config.memory.limits(),
                                System.currentTimeMillis());
            }
        }
        entryFor(owner).ifPresent(session -> session.brain().post(event));
    }

    private CompanionSession session(UUID owner) {
        return sessions.computeIfAbsent(owner, id -> new CompanionSession(id, memory.book(id)));
    }

    private Optional<CompanionSession> entryFor(UUID owner) {
        if (!acceptingWork) return Optional.empty();
        CompanionSession session = session(owner);
        Optional<CompanionEntity> companion = CompanionManager.get(server).find(owner);
        if (companion.isEmpty()) {
            return Optional.empty();
        }
        if (session.brain() == null || session.companion() != companion.get()) {
            if (session.brain() != null) session.invalidateBrain();
            create(session, companion.get());
        }
        return Optional.of(session);
    }

    public dev.friendai.agent.turn.SkillTurnSession skillSession(UUID owner) {
        CompanionSession session = sessions.get(owner);
        return session == null ? dev.friendai.agent.turn.SkillTurnSession.NONE : session.skillSession();
    }

    public dev.friendai.foundation.teaching.TeachingContext teachingContext(UUID owner) {
        CompanionSession session = sessions.get(owner);
        return session == null ? null : session.teachingContext();
    }

    public void skillOutcome(
            UUID owner, String id, dev.friendai.foundation.teaching.TeachingContext.OutcomeKind kind, String summary) {
        if (config.skills.enabled) post(owner, new BrainEvent.SkillOutcome(id, kind, summary));
    }

    public void cancel(UUID owner) {
        CompanionSession session = sessions.get(owner);
        if (session != null && session.brain() != null) session.brain().cancel();
    }

    /** The owner's companion is gone (death, dismissal, unload): drop its brain. */
    public void onCompanionRemoved(UUID owner) {
        CompanionSession session = sessions.get(owner);
        if (session != null) session.invalidateBrain();
    }

    /** Invalidates entity-bound work before a replacement companion receives a new session brain. */
    public void onEntityReplaced(UUID owner) {
        onCompanionRemoved(owner);
    }

    /** The owner left the game: keep the task (a build can resume), but make no progress while they are away. */
    public void onOwnerLeft(UUID owner) {
        cancel(owner);
        CompanionManager.get(server).find(owner).ifPresent(c -> {
            if (c.creativeMode()) c.executionCoordinator().cancelForCapabilityChange();
            c.executionCoordinator().suspend();
        });
    }

    /** The owner came back to a still-loaded companion. */
    public void onOwnerJoined(UUID owner) {
        CompanionManager.get(server)
                .find(owner)
                .ifPresent(c -> c.executionCoordinator().resume());
    }

    public TaskRestoreRegistry taskRestoreRegistry() {
        return new TaskRestoreRegistry(taskTools, planTools, settings, this::currentCaller);
    }

    /** Installs event routing before TaskPersistenceCoordinator publishes any restore notices. */
    public void prepareForTaskRestore(CompanionEntity companion) {
        if (companion.ownerUuid() != null) entryFor(companion.ownerUuid());
    }

    /** Reconstructs a caller from current owner/trust data; queue saves never carry an access level. */
    private Caller currentCaller(UUID owner, UUID principal) {
        if (principal == null) return new Caller(null, "", AccessLevel.GUEST);
        AccessLevel access = principal.equals(owner)
                ? AccessLevel.OWNER
                : owner != null && memory.book(owner).isTrusted(principal) ? AccessLevel.TRUSTED : AccessLevel.GUEST;
        return new Caller(principal, "", access);
    }

    public void tick() {
        scheduler.tick();
        agentTick();
    }

    /** Cancels in-flight model work while the server and its owner-thread state are still available. */
    public void stopAcceptingWork() {
        if (!acceptingWork) return;
        acceptingWork = false;
        for (CompanionSession session : List.copyOf(sessions.values())) session.invalidateBrain();
        invalidateTradingAuthorities("server stopping");
    }

    @Override
    public void close() {
        stopAcceptingWork();
        sessions.clear();
    }

    /** Once a second: nudge idle companions toward an unfinished goal, as the autonomy setting allows. */
    private void agentTick() {
        long now = server.getTickCount() * 50L;
        if (now - lastAgentCheck < 1_000L) {
            return;
        }
        lastAgentCheck = now;
        dev.friendai.agent.turn.AgentSettings.Autonomy autonomy =
                dev.friendai.agent.turn.AgentSettings.Autonomy.parse(config.agent.autonomy);
        if (autonomy == dev.friendai.agent.turn.AgentSettings.Autonomy.PASSIVE) {
            return;
        }
        long wall = FreeTimeDriver.now();
        for (CompanionEntity companion : CompanionManager.get(server).all()) {
            UUID owner = companion.ownerUuid();
            if (owner == null) {
                continue;
            }
            var primary = goals.board(owner).primary();
            // Free time needs the task listener a brain installs, even before the owner has said anything.
            CompanionSession brain =
                    freeTime.state(owner).on() ? entryFor(owner).orElse(null) : sessions.get(owner);
            try {
                freeTime.tick(
                        owner,
                        companion,
                        brain != null && brain.isThinking(),
                        primary.isPresent() && primary.get().status() == dev.friendai.agent.goal.GoalStatus.ACTIVE,
                        wall);
            } catch (RuntimeException e) {
                FriendaiMod.LOGGER.warn("Free time failed for {}", owner, e);
            }
        }
        for (Map.Entry<UUID, CompanionSession> entry : new ArrayList<>(sessions.entrySet())) {
            UUID owner = entry.getKey();
            if (entry.getValue().brain() == null) continue;
            CompanionEntity companion = CompanionManager.get(server).find(owner).orElse(null);
            if (companion == null) {
                continue;
            }
            var board = goals.board(owner);
            var primary = board.primary();
            boolean active = primary.isPresent() && primary.get().status() == dev.friendai.agent.goal.GoalStatus.ACTIVE;
            long last = entry.getValue().lastGoalTick();
            if (last == 0L) {
                last = now;
                entry.getValue().lastGoalTick(now);
            }
            boolean idle = queueIdleOrBlocked(companion);
            if (!idle) {
                entry.getValue().resetNudgeStreak(); // a new idle spell starts with a fresh interval
            }
            int streak = entry.getValue().nudgeStreak();
            if (dev.friendai.agent.tick.NudgeBackoff.exhausted(streak)) {
                continue;
            }
            var facts = new dev.friendai.agent.tick.TickFacts(
                    active,
                    idle,
                    companion.owner().isPresent(),
                    companion.executionCoordinator().paused()
                            || companion.executionCoordinator().suspended()
                            || companion.isSleeping()
                            || companion.getTarget() != null,
                    entry.getValue().isThinking(),
                    now,
                    last,
                    autonomy,
                    dev.friendai.agent.tick.NudgeBackoff.intervalSec(config.agent.goalTickSec, streak));
            if (dev.friendai.agent.tick.GoalTickPolicy.shouldAskWhatNext(facts)) {
                entry.getValue().lastGoalTick(now);
                entry.getValue().incrementNudgeStreak();
                post(owner, new BrainEvent.IdleNudge());
            } else if (dev.friendai.agent.tick.GoalTickPolicy.shouldTick(facts)) {
                entry.getValue().lastGoalTick(now);
                entry.getValue().incrementNudgeStreak();
                var guard = new dev.friendai.task.queue.CompanionGuardFacts(companion);
                guard.refresh();
                post(
                        owner,
                        new BrainEvent.GoalTick(
                                primary.get().text(),
                                board.unmet(primary.get().id(), guard).stream()
                                        .map(dev.friendai.agent.goal.Criterion::text)
                                        .toList()));
            }
        }
    }

    /** Nothing runs and no plan moves by itself; a plan waiting for a decision counts as blocked. */
    private static boolean queueIdleOrBlocked(CompanionEntity companion) {
        var runner = companion.executionCoordinator();
        var queue = runner.queue();
        if (queue.jobCount() == 0) {
            return !runner.hasWork();
        }
        if (queue.active().isPresent()) {
            return false;
        }
        boolean directWork = queue.jobs().stream()
                .anyMatch(job -> job.planId() == 0 && !dev.friendai.execution.queue.JobQueue.terminal(job));
        boolean planMoving = queue.plans().stream()
                .anyMatch(plan -> queue.planStatus(plan.id()) == dev.friendai.execution.queue.PlanStatus.ACTIVE);
        return !directWork && !planMoving;
    }

    /** Lets one companion's brain consult the planner and queue what it proposes. */
    private final class GamePlannerPort implements dev.friendai.agent.turn.PlannerPort {
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
            return goals.board(owner)
                    .primary()
                    .map(goal -> goal.planId() == planId)
                    .orElse(false);
        }

        @Override
        public Optional<dev.friendai.agent.plan.PlannerInput> input(Caller caller, String failure) {
            var board = goals.board(owner);
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
            String queue = dev.friendai.execution.queue.QueueText.show(
                    companion.executionCoordinator().queue(), facts, null, 20, false);
            return Optional.of(new dev.friendai.agent.plan.PlannerInput(
                    primary.get(),
                    new CompanionPerception(companion, perceptionContext).describe(caller),
                    digest.toString(),
                    PlaceText.planner(
                            config.places.enabled && caller.access().allows(AccessLevel.TRUSTED)
                                    ? places.book(owner).all()
                                    : List.of(),
                            config.places.plannerLimit),
                    Optional.of(queue),
                    primary.get().lessons(),
                    failure,
                    schemas,
                    taskTools.keySet()));
        }

        @Override
        public CompletableFuture<dev.friendai.agent.plan.PlanProposal> propose(
                dev.friendai.agent.plan.PlannerInput input) {
            return CompanionSessions.this.propose(owner, input);
        }

        @Override
        public ToolResult apply(Caller caller, dev.friendai.agent.plan.PlanProposal proposal) {
            var board = goals.board(owner);
            var primary = board.primary();
            var executor = tools.bind(companion);
            if (primary.isPresent()
                    && primary.get().planId() != 0
                    && companion
                            .executionCoordinator()
                            .queue()
                            .plan(primary.get().planId())
                            .isPresent()) {
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
            add.addProperty(
                    "goal", primary.map(dev.friendai.agent.goal.Goal::text).orElse("planned work"));
            // Preserve the normalized tree; queue_add and plan_validate share the same parser and tool allowlist.
            add.add("steps", proposal.stepsJson());
            ToolResult result =
                    executor.execute(new ToolCall("planner", "queue_add", add, add.toString(), null), caller);
            plannerDebug(owner, result.error() ? "planner disposition=upgrade refused" : "planner disposition=queued");
            goals.save(owner);
            return result;
        }
    }

    /** {@code name(arg*, arg): first sentence of the description}; a star marks a required argument. */
    static String describeTool(TaskToolDef def) {
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
                String factsLine = dev.friendai.agent.verify.OutcomeChecker.check(
                                last,
                                after,
                                success,
                                java.util.List.of(),
                                new dev.friendai.task.queue.CompanionGuardFacts(companion))
                        .factsLine();
                last = after;
                var board = goals.board(owner);
                var priorGoals = board.all();
                String extra;
                if (success) {
                    var facts = new dev.friendai.task.queue.CompanionGuardFacts(companion);
                    facts.refresh();
                    extra = handler.onSuccess(board, planId, factsLine, facts);
                } else {
                    extra = handler.onFailure(board, planId, tool, text, factsLine);
                }
                goals.save(owner);
                for (var prior : priorGoals) {
                    if (board.find(prior.id()).isEmpty())
                        skillOutcome(
                                owner,
                                "goal:" + prior.id(),
                                success
                                        ? dev.friendai.foundation.teaching.TeachingContext.OutcomeKind.GOAL_SUCCESS
                                        : dev.friendai.foundation.teaching.TeachingContext.OutcomeKind.GOAL_FAILURE,
                                text);
                }
                return extra;
            } catch (RuntimeException e) {
                FriendaiMod.LOGGER.warn("Could not build event facts", e);
                return "";
            }
        }
    }

    /** An owner's stop or follow order also ends free time: both mean "leave your own work". */
    private dev.friendai.execution.tool.ToolExecutor endsFreeTime(
            UUID owner, dev.friendai.execution.tool.ToolExecutor inner) {
        return new dev.friendai.execution.tool.ToolExecutor() {
            @Override
            public List<ToolSpec> specs(AccessLevel access) {
                return inner.specs(access);
            }

            @Override
            public ToolResult execute(ToolCall call, Caller caller) {
                Optional<String> refused = freeTime.refusalDuringShift(owner, call.name());
                if (refused.isEmpty()) {
                    refused = freeTime.refusalDuringChoice(owner, call.name());
                }
                if (refused.isPresent()) {
                    return ToolResult.error(refused.get());
                }
                if (caller != null
                        && caller.access() == AccessLevel.OWNER
                        && ("stop".equals(call.name()) || "follow_player".equals(call.name()))) {
                    // End free time first, so its plan is released, not recorded as a failed shift.
                    freeTime.stop(owner, "owner asked to " + call.name().replace('_', ' '));
                }
                return inner.execute(call, caller);
            }
        };
    }

    private CompanionSession create(CompanionSession session, CompanionEntity companion) {
        UUID owner = session.owner();
        CompanionSpeaker speaker = new CompanionSpeaker(
                server,
                owner,
                () -> companion.getName().getString(),
                () -> config.chat.maxReplyChars,
                companion::setThinking,
                companion::getUUID,
                () -> config.chat.hearingRadius);
        // With the owner away (free time can keep working) the companion has nobody to talk to.
        Speaker effective = session.speakerWrapper()
                .apply(new dev.friendai.agent.turn.OwnerPresentSpeaker(
                        speaker, () -> companion.owner().isPresent()));
        CompanionBrain brain = new CompanionBrain(
                () -> new Persona(
                        companion.getName().getString(),
                        memory.book(owner).persona().orElse(config.companion.description),
                        config.companion.language,
                        memory.book(owner).ownerLanguage(owner).orElse(null)),
                () -> new BrainSettings(
                        config.brain.maxToolStepsPerTurn,
                        config.brain.historyMessages,
                        config.brain.cooldownMs,
                        config.chat.guestCooldownMs),
                () -> gateways.main(session),
                endsFreeTime(owner, tools.bind(companion)),
                new CompanionPerception(companion, perceptionContext),
                effective,
                scheduler,
                AccessResolver.of(owner, p -> memory.book(owner).isTrusted(p)),
                caller -> config.memory.enabled ? MemoryPromptRenderer.render(memory.book(owner), owner, caller) : "");
        brain.setTradingTurnPort(session.tradingAuthority());
        brain.setTradingPromptSource((caller, request) -> {
            if (caller.access() != AccessLevel.OWNER
                    || !owner.equals(caller.uuid())
                    || !(companion.level() instanceof ServerLevel level)) return "";
            var book = trading.book(owner);
            var tradingConfig = settings.get().trading();
            return TradePromptSummary.render(
                    book,
                    companion.hands().player().getUUID(),
                    request,
                    level.getServer().overworld().getGameTime(),
                    tradingConfig.quoteFreshTicks(),
                    tradingConfig.locationFreshTicks());
        });
        brain.setSkillSource((caller, message, turn) -> skills.begin(
                owner,
                caller,
                message,
                turn,
                companion.executionCoordinator().currentToolName(),
                brain.isSkillFreeTimeTurn(),
                false,
                companion.combatController().state() != dev.friendai.task.combat.CombatController.State.IDLE));
        brain.setObserver(new DebugLogObserver(debug.logOf(owner)));
        brain.setPlannerPort(new GamePlannerPort(owner, companion, session.replacedPlans()));
        brain.setAgentSettings(() -> new dev.friendai.agent.turn.AgentSettings(
                config.agent.toolRouting,
                dev.friendai.agent.turn.AgentSettings.Autonomy.parse(config.agent.autonomy),
                config.agent.maxReplans,
                config.agent.goalTickSec,
                config.agent.promptBudget,
                config.agent.plannerTimeoutSec));
        brain.setPlaceSource(caller -> config.places.enabled ? PlaceService.promptBlock(companion, caller) : "");
        brain.setPlacedSource(caller ->
                config.places.enabled ? dev.friendai.placed.PlacedService.promptBlock(companion, caller) : "");
        brain.setGoalSource(caller -> {
            var board = goals.board(owner);
            var primary = board.primary();
            String plan = "";
            if (primary.isPresent() && primary.get().planId() != 0) {
                long planId = primary.get().planId();
                plan = companion.executionCoordinator().queue().plan(planId).isPresent()
                        ? "plan #" + planId + " "
                                + companion
                                        .executionCoordinator()
                                        .queue()
                                        .planStatus(planId)
                                        .name()
                                        .toLowerCase(java.util.Locale.ROOT)
                                        .replace('_', ' ')
                        : "plan #" + planId + " is no longer queued";
            }
            var facts = new dev.friendai.task.queue.CompanionGuardFacts(companion);
            facts.refresh();
            return board.view(plan, facts);
        });
        AgentEvents agent = new AgentEvents(owner, companion);
        companion.executionCoordinator().setListener(new TaskListener() {
            @Override
            public void onFinished(int id, String toolName, TaskStatus status, String summary) {
                if (!isCurrent(session, companion)) return;
                debug.historyOf(owner)
                        .add(new TaskRecord(
                                id, toolName, status == TaskStatus.SUCCESS ? "SUCCESS" : "FAILED", summary));
                boolean ok = status == TaskStatus.SUCCESS;
                postFromSession(
                        session,
                        companion,
                        owner,
                        new BrainEvent.TaskFinished(id, toolName, ok, summary, agent.extra(ok, 0, toolName, summary)));
            }

            @Override
            public void onCancelled(int id, String toolName, String reason) {
                if (!isCurrent(session, companion)) return;
                debug.historyOf(owner).add(new TaskRecord(id, toolName, "CANCELLED", reason));
                postFromSession(session, companion, owner, new BrainEvent.TaskCancelled(id, toolName, reason));
            }

            @Override
            public void onJobFinished(long jobId, long planId, String toolName, TaskStatus status, String summary) {
                if (!isCurrent(session, companion)) return;
                if (planId != 0) { // direct jobs are recorded through onFinished/onCancelled
                    debug.historyOf(owner)
                            .add(new TaskRecord(
                                    (int) jobId,
                                    toolName,
                                    status == TaskStatus.SUCCESS ? "SUCCESS" : "FAILED",
                                    summary));
                }
            }

            @Override
            public void onPlanFinished(long planId, String goal, boolean success, String summary) {
                if (!isCurrent(session, companion)) return;
                if (session.replacedPlans().remove(planId)
                        || freeTime.onPlanEnded(owner, companion, planId, success, summary)) {
                    return;
                }
                postFromSession(
                        session,
                        companion,
                        owner,
                        new BrainEvent.PlanFinished(
                                planId, goal, success, summary, agent.extra(success, planId, goal, summary)));
            }

            @Override
            public void onPlanBlocked(
                    long planId, String goal, long jobId, String toolName, String reason, int remaining) {
                if (!isCurrent(session, companion)) return;
                if (session.replacedPlans().contains(planId)
                        || freeTime.onPlanEnded(owner, companion, planId, false, reason)) {
                    return;
                }
                postFromSession(
                        session,
                        companion,
                        owner,
                        new BrainEvent.PlanBlocked(
                                planId,
                                goal,
                                jobId,
                                toolName,
                                reason,
                                remaining,
                                agent.extra(false, planId, toolName, reason)));
            }

            @Override
            public void onNotice(String text) {
                if (!isCurrent(session, companion)) return;
                postFromSession(session, companion, owner, new BrainEvent.WorldNotice(text));
            }

            @Override
            public void onPlace(PlaceCandidate candidate, String toolName) {
                if (!isCurrent(session, companion)) return;
                // Not news for the model: a successful task quietly refreshes the owner's place book.
                PlaceService.autoRegister(companion, candidate);
            }

            @Override
            public void onDebug(String text) {
                if (!isCurrent(session, companion)) return;
                debug.logOf(owner).add(LogKind.TASK, text);
            }
        });
        session.attach(companion, brain);
        FriendaiMod.LOGGER.debug("Created brain for {}", owner);
        return session;
    }

    private void postFromSession(CompanionSession session, CompanionEntity companion, UUID owner, BrainEvent event) {
        if (isCurrent(session, companion)) post(owner, event);
    }

    private boolean isCurrent(CompanionSession session, CompanionEntity companion) {
        return sessions.get(session.owner()) == session && session.companion() == companion;
    }

    /** The single runtime authority ledger for this owner; tasks must reuse it and never rebuild scopes. */
    public TradeAuthority tradeAuthorityFor(UUID owner) {
        if (owner == null) throw new IllegalArgumentException("trade authority owner is required");
        return session(owner).tradingAuthority().authority();
    }

    /** Revokes every runtime trading scope when server trading is disabled. */
    public void invalidateTradingAuthorities(String reason) {
        sessions.values().forEach(session -> session.tradingAuthority().invalidateAll(reason));
    }
}
