package dev.friendai.brain;

import dev.friendai.agent.plan.PlanProposal;
import dev.friendai.agent.plan.PlannerInput;
import dev.friendai.agent.recover.FailureHandler;
import dev.friendai.agent.route.IntentVocabulary;
import dev.friendai.agent.route.RouteDecision;
import dev.friendai.agent.route.RouteSignals;
import dev.friendai.agent.route.TurnMode;
import dev.friendai.agent.route.TurnRouter;
import dev.friendai.agent.tools.ToolCatalog;
import dev.friendai.agent.tools.ToolGroup;
import dev.friendai.llm.model.LlmResponse;
import dev.friendai.llm.model.Message;
import dev.friendai.llm.model.ToolCall;
import dev.friendai.llm.model.ToolSpec;
import dev.friendai.tool.AccessLevel;
import dev.friendai.tool.Caller;
import dev.friendai.tool.ToolExecutor;
import dev.friendai.tool.ToolResult;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The agent loop: events in, speech and tool calls out. At most one LLM request is in flight, and one turn
 * acts for exactly one principal. Every method must be called on the scheduler's thread; LLM callbacks hop
 * back through it.
 */
public final class CompanionBrain {
    private static final Logger LOGGER = LoggerFactory.getLogger("friendai/brain");
    private static final String REQUEST_TOOLS = "request_tools";
    /** The marker the failure handler puts in an event when no replans are left. */
    private static final String BUDGET_SPENT = FailureHandler.BUDGET_SPENT;
    /** How many queued events one guest may have waiting for a turn. */
    public static final int MAX_PENDING_PER_GUEST = 2;
    /** How long a spoken line and the owner's coordinate question stay relevant. */
    private static final long SPEECH_WINDOW_MS = 120_000L;
    /** How many recent lines an event turn is checked against before it speaks again. */
    private static final int RECENT_SPEECH_LINES = 4;

    private final Supplier<Persona> persona;
    private final Supplier<BrainSettings> settings;
    private final Supplier<LlmGateway> gateway;
    private final ToolExecutor tools;
    private final PerceptionSource perception;
    private final Speaker speaker;
    private final BrainScheduler scheduler;
    private final AccessResolver access;
    private final MemorySource memorySource;
    private final ConversationMemory memory;
    private BrainObserver observer = BrainObserver.NONE;
    private GoalSource goalSource = GoalSource.NONE;
    private PlaceSource placeSource = PlaceSource.NONE;
    private PlannerPort plannerPort = PlannerPort.NONE;
    private boolean plannedThisTurn;
    /** A plan that failed or needs a decision in this turn's batch is the one the goal follows. */
    private boolean ownsTroubledPlan;
    /** What the planner just did; shown once in the next request's Mission block. */
    private String plannerNote = "";
    private Supplier<AgentSettings> agentSettings = () -> AgentSettings.DEFAULTS;

    private final Deque<BrainEvent> queue = new ArrayDeque<>();
    private final Map<UUID, Long> lastAcceptedGuestMessage = new HashMap<>();
    private Caller turnCaller;
    private boolean thinking;
    private boolean cooldownScheduled;
    private boolean hasFinishedTurn;
    private long lastTurnEnd;
    private int generation;
    private int step;
    private TurnKind turnKind = TurnKind.PLAYER;
    private final List<String> turnPlayerTexts = new ArrayList<>();
    private String lastOwnerText;
    private long lastOwnerAtMillis;
    /** What the player just heard, newest first: an event turn repeats none of it. */
    private final Deque<SpokenLine> recentSpeech = new ArrayDeque<>();
    private Set<String> turnToolNames = Set.of();
    private final TurnRouter router = new TurnRouter(IntentVocabulary.load());
    private final ToolCatalog catalog = ToolCatalog.defaults();
    /** The tool groups this turn may use; widened by request_tools, reset at every turn start. */
    private final Set<ToolGroup> visibleGroups = EnumSet.allOf(ToolGroup.class);
    private RouteDecision decision = new RouteDecision(TurnMode.ACTION, EnumSet.allOf(ToolGroup.class), false, "none");

    private static final class SpokenLine {
        private final String text;
        private final long atMillis;

        private SpokenLine(String text, long atMillis) {
            this.text = text;
            this.atMillis = atMillis;
        }
    }

    public CompanionBrain(
            Supplier<Persona> persona,
            Supplier<BrainSettings> settings,
            Supplier<LlmGateway> gateway,
            ToolExecutor tools,
            PerceptionSource perception,
            Speaker speaker,
            BrainScheduler scheduler,
            AccessResolver access) {
        this(persona, settings, gateway, tools, perception, speaker, scheduler, access, MemorySource.NONE);
    }

    public CompanionBrain(
            Supplier<Persona> persona,
            Supplier<BrainSettings> settings,
            Supplier<LlmGateway> gateway,
            ToolExecutor tools,
            PerceptionSource perception,
            Speaker speaker,
            BrainScheduler scheduler,
            AccessResolver access,
            MemorySource memorySource) {
        this.persona = persona;
        this.settings = settings;
        this.gateway = gateway;
        this.tools = tools;
        this.perception = perception;
        this.speaker = speaker;
        this.scheduler = scheduler;
        this.access = access;
        this.memorySource = memorySource;
        this.turnCaller = new Caller(access.owner(), "", AccessLevel.OWNER);
        this.memory = new ConversationMemory(settings.get().historyMessages());
    }

    public void post(BrainEvent e) {
        if (shouldDrop(e)) {
            return;
        }
        queue.addLast(e);
        maybeStartTurn();
    }

    /**
     * Guests are throttled because every request costs money; the owner never is. Dropped messages go to the
     * observer and the debug log, never to chat.
     */
    private boolean shouldDrop(BrainEvent event) {
        if (!(event instanceof BrainEvent.PlayerSaid said)) {
            return false;
        }
        UUID guest = said.playerUuid();
        if (access.accessOf(guest) != AccessLevel.GUEST) {
            return false;
        }
        long now = scheduler.nowMillis();
        Long last = lastAcceptedGuestMessage.get(guest);
        if (last != null && now - last < settings.get().guestCooldownMs()) {
            observe(o -> o.onEventDropped(event, "guest cooldown"));
            return true;
        }
        long pending = queue.stream()
                .filter(queued -> principalOf(queued).equals(guest))
                .count();
        if (pending >= MAX_PENDING_PER_GUEST) {
            observe(o -> o.onEventDropped(event, "too many pending messages"));
            return true;
        }
        lastAcceptedGuestMessage.put(guest, now);
        return false;
    }

    /** Drops the in-flight request (its response will be ignored) and all queued events. */
    public void cancel() {
        generation++;
        queue.clear();
        if (thinking) {
            thinking = false;
            speaker.thinking(false);
        }
    }

    public boolean isThinking() {
        return thinking;
    }

    public void setObserver(BrainObserver observer) {
        this.observer = java.util.Objects.requireNonNull(observer);
    }

    public void setAgentSettings(Supplier<AgentSettings> agentSettings) {
        this.agentSettings = java.util.Objects.requireNonNull(agentSettings);
    }

    public void setGoalSource(GoalSource goalSource) {
        this.goalSource = java.util.Objects.requireNonNull(goalSource);
    }

    public void setPlaceSource(PlaceSource placeSource) {
        this.placeSource = java.util.Objects.requireNonNull(placeSource);
    }

    public void setPlannerPort(PlannerPort plannerPort) {
        this.plannerPort = java.util.Objects.requireNonNull(plannerPort);
    }

    /** The system prompt the next request would carry. */
    public String currentSystemPrompt() {
        return buildSystemPrompt(plannerNote);
    }

    private String buildSystemPrompt(String note) {
        return PromptBuilder.system(
                persona.get(),
                perception.describe(turnCaller),
                turnCaller,
                memorySource.describe(turnCaller),
                turnKind,
                goalSource.view(turnCaller),
                agentSettings.get().promptBudget(),
                catalog.hints(agentSettings.get().toolRouting() ? visibleGroups : EnumSet.allOf(ToolGroup.class)),
                placeSource.describe(turnCaller),
                note);
    }

    private void observe(java.util.function.Consumer<BrainObserver> call) {
        try {
            call.accept(observer);
        } catch (RuntimeException e) {
            LOGGER.warn("Brain observer failed", e);
        }
    }

    /** The player a turn acts for. Task events belong to the owner. */
    private UUID principalOf(BrainEvent event) {
        return event instanceof BrainEvent.PlayerSaid said ? said.playerUuid() : access.owner();
    }

    private void maybeStartTurn() {
        if (thinking || queue.isEmpty()) {
            return;
        }
        long wait = hasFinishedTurn ? settings.get().cooldownMs() - (scheduler.nowMillis() - lastTurnEnd) : 0;
        if (wait > 0) {
            if (!cooldownScheduled) {
                cooldownScheduled = true;
                scheduler.schedule(() -> {
                    cooldownScheduled = false;
                    maybeStartTurn();
                }, wait);
            }
            return;
        }
        UUID principal = principalOf(queue.peekFirst());
        List<BrainEvent> batch = new ArrayList<>();
        queue.removeIf(event -> {
            if (principalOf(event).equals(principal)) {
                batch.add(event);
                return true;
            }
            return false;
        });
        String name = "";
        turnPlayerTexts.clear();
        for (BrainEvent event : batch) {
            if (event instanceof BrainEvent.PlayerSaid said) {
                name = said.playerName();
                turnPlayerTexts.add(said.text());
                if (said.playerUuid().equals(access.owner())) {
                    lastOwnerText = said.text();
                    lastOwnerAtMillis = scheduler.nowMillis();
                }
            }
        }
        turnKind = turnPlayerTexts.isEmpty() ? TurnKind.EVENT : TurnKind.PLAYER;
        AccessLevel level = access.accessOf(principal);
        turnCaller = new Caller(principal, name == null ? "" : name, level);
        List<Long> troubledPlans = batch.stream()
                .map(CompanionBrain::troubledPlanId)
                .filter(id -> id >= 0)
                .toList();
        boolean planTrouble = !troubledPlans.isEmpty();
        ownsTroubledPlan = troubledPlans.stream().anyMatch(plannerPort::ownsPlan);
        decision = router.route(new RouteSignals(
                turnKind,
                String.join("\n", turnPlayerTexts),
                !goalSource.view(turnCaller).isEmpty(),
                planTrouble,
                0));
        visibleGroups.clear();
        visibleGroups.addAll(decision.groups());
        thinking = true;
        speaker.thinking(true);
        String lines = batch.stream()
                .map(event -> event.toPromptLine(level))
                .collect(Collectors.joining("\n"));
        for (BrainEvent event : batch) {
            observe(o -> o.onEvent(event));
        }
        memory.add(Message.user(lines));
        step = 0;
        plannedThisTurn = false;
        plannerNote = "";
        // A failed plan that still has replans left is worked out by the planner before the model answers.
        if (decision.mode() == TurnMode.RECOVERY && decision.wantPlanner() && ownsTroubledPlan
                && !lines.contains(BUDGET_SPENT)) {
            plan(generation, lines);
        } else {
            requestStep(generation);
        }
    }

    /**
     * One planner consultation per turn. The reply is applied on the scheduler thread and shown to the model once,
     * in the Mission block of the next request. A stop, dismissal or logout meanwhile bumps the generation, so a
     * late proposal is dropped without touching the queue (rule 5).
     */
    private void plan(int gen, String failure) {
        plannedThisTurn = true;
        Optional<PlannerInput> input;
        try {
            input = plannerPort.input(turnCaller, failure);
        } catch (RuntimeException e) {
            LOGGER.warn("Planner input failed", e);
            input = Optional.empty();
        }
        if (input.isEmpty()) {
            requestStep(gen);
            return;
        }
        CompletableFuture<PlanProposal> future;
        try {
            future = plannerPort.propose(input.get());
        } catch (RuntimeException e) {
            future = CompletableFuture.failedFuture(e);
        }
        future.whenComplete((proposal, error) -> scheduler.execute(() -> {
            if (gen != generation) {
                return;
            }
            if (error != null || proposal == null) {
                LOGGER.info("Planner gave no plan: {}", error == null ? "empty" : error.getClass().getSimpleName());
                plannerNote = "no plan was drafted (the planner is unavailable); work out the next step yourself";
            } else {
                ToolResult applied;
                try {
                    applied = plannerPort.apply(turnCaller, proposal);
                } catch (RuntimeException e) {
                    applied = ToolResult.error("could not queue the plan");
                }
                plannerNote = applied.error()
                        ? "the planner's plan could not be queued (" + applied.content() + "); plan it yourself"
                        : "a plan was queued (" + applied.content() + "): " + proposal.rationale()
                                + (proposal.expectedOutcome().isBlank() ? "" : "; expected: " + proposal.expectedOutcome());
            }
            requestStep(gen);
        }));
    }

    private void requestStep(int gen) {
        if (gen != generation) {
            return;
        }
        if (step >= settings.get().maxToolSteps()) {
            endTurn();
            return;
        }
        step++;
        CompletableFuture<LlmResponse> future;
        try {
            List<ToolSpec> specs = tools.specs(turnCaller.access());
            // Speech is checked against every tool this caller has, not just the routed subset.
            turnToolNames = specs.stream().map(ToolSpec::name).collect(Collectors.toUnmodifiableSet());
            List<ToolSpec> sent = agentSettings.get().toolRouting() ? catalog.filter(specs, visibleGroups) : specs;
            String system = buildSystemPrompt(plannerNote);
            plannerNote = "";
            future = gateway.get().complete(system, memory.snapshot(), sent);
        } catch (RuntimeException e) {
            future = CompletableFuture.failedFuture(e);
        }
        future.whenComplete((response, error) -> scheduler.execute(() -> onResponse(gen, response, error)));
    }

    private void onResponse(int gen, LlmResponse response, Throwable error) {
        if (gen != generation) {
            return;
        }
        if (error != null) {
            LOGGER.warn("LLM request failed: {}", error.toString());
            observe(o -> o.onError(error));
            speaker.error(ErrorMessages.forUser(error));
            endTurn();
            return;
        }
        String raw = response.text();
        if (!raw.isBlank()) {
            observe(o -> o.onAssistantText(raw));
        }
        String spoken = null;
        if (!raw.isBlank()) {
            Optional<String> guarded =
                    ReplyGuard.filter(raw, coordinatesAllowed(), recentSpeech(), turnToolNames);
            if (guarded.isPresent()) {
                spoken = guarded.get();
                speaker.say(spoken, turnCaller.uuid());
                rememberSpoken(spoken);
            }
        }
        List<ToolCall> calls = response.toolCalls();
        if (!raw.isBlank() || !calls.isEmpty()) {
            String remembered = spoken != null ? spoken : (raw.isBlank() ? raw : ReplyGuard.SILENT);
            memory.add(Message.assistant(remembered, calls));
        }
        if (calls.isEmpty()) {
            endTurn();
            return;
        }
        boolean goalSet = false;
        boolean queuedByModel = false;
        for (ToolCall call : calls) {
            ToolResult result;
            observe(o -> o.onToolCall(call));
            try {
                result = REQUEST_TOOLS.equals(call.name()) ? requestTools(call) : tools.execute(call, turnCaller);
            } catch (RuntimeException e) {
                LOGGER.warn("Tool {} threw", call.name(), e);
                result = ToolResult.error("Tool " + call.name() + " failed: " + e.getMessage());
            }
            ToolResult finalResult = result;
            observe(o -> o.onToolResult(call, finalResult));
            memory.add(Message.toolResult(call.id(), call.name(), result.content()));
            goalSet |= !result.error() && "set_goal".equals(call.name());
            queuedByModel |= !result.error() && "queue_add".equals(call.name());
        }
        if (goalSet && !queuedByModel && !plannedThisTurn
                && decision.mode() == TurnMode.GOAL && decision.wantPlanner()) {
            plan(gen, "");
            return;
        }
        requestStep(gen);
    }

    /** The plan an event reports as failed or blocked, or -1 for anything else (a stop or cancel is not trouble). */
    private static long troubledPlanId(BrainEvent event) {
        if (event instanceof BrainEvent.PlanBlocked blocked) {
            return blocked.planId();
        }
        if (event instanceof BrainEvent.PlanFinished finished && !finished.success()
                && !FailureHandler.isCancellation(finished.summary())) {
            return finished.planId();
        }
        return -1;
    }

    /** Unlocks a tool group for the rest of the turn; an unknown group is an error listing the valid ones. */
    private ToolResult requestTools(ToolCall call) {
        String wanted = call.arguments() != null && call.arguments().has("group")
                && call.arguments().get("group").isJsonPrimitive()
                ? call.arguments().get("group").getAsString() : "";
        Optional<ToolGroup> group = ToolGroup.parse(wanted).filter(g -> g != ToolGroup.CORE);
        if (group.isEmpty()) {
            String valid = java.util.Arrays.stream(ToolGroup.values())
                    .filter(g -> g != ToolGroup.CORE)
                    .map(g -> g.name().toLowerCase(java.util.Locale.ROOT))
                    .collect(Collectors.joining(", "));
            return ToolResult.error("Unknown tool group \"" + wanted + "\". Valid groups: " + valid);
        }
        visibleGroups.add(group.get());
        String unlocked = tools.specs(turnCaller.access()).stream()
                .map(ToolSpec::name)
                .filter(name -> catalog.meta(name).map(meta -> meta.group() == group.get()).orElse(false))
                .collect(Collectors.joining(", "));
        return ToolResult.ok("tools unlocked: " + group.get().name().toLowerCase(java.util.Locale.ROOT)
                + (unlocked.isEmpty() ? "" : " (" + unlocked + ")"));
    }

    /**
     * Coordinates are allowed while the player is asking about them: on a player turn, when any message in
     * the batch asks where; on an event turn, only while the owner's question is still recent. A guest's
     * question never unlocks coordinates in the owner's background turn.
     */
    private boolean coordinatesAllowed() {
        if (turnKind == TurnKind.PLAYER) {
            return turnPlayerTexts.stream().anyMatch(ReplyGuard::playerAskedForCoordinates);
        }
        return lastOwnerText != null
                && scheduler.nowMillis() - lastOwnerAtMillis <= SPEECH_WINDOW_MS
                && ReplyGuard.playerAskedForCoordinates(lastOwnerText);
    }

    /**
     * The lines the player just heard, newest first. Only an event turn is held to them: answering the
     * player again is legitimate even when the answer sounds like the last one.
     */
    private List<String> recentSpeech() {
        if (turnKind != TurnKind.EVENT || recentSpeech.isEmpty()) {
            return List.of();
        }
        long now = scheduler.nowMillis();
        List<String> lines = new ArrayList<>(recentSpeech.size());
        for (SpokenLine line : recentSpeech) {
            if (now - line.atMillis > SPEECH_WINDOW_MS) {
                break;
            }
            lines.add(line.text);
        }
        return lines;
    }

    private void rememberSpoken(String spoken) {
        recentSpeech.addFirst(new SpokenLine(spoken, scheduler.nowMillis()));
        while (recentSpeech.size() > RECENT_SPEECH_LINES) {
            recentSpeech.removeLast();
        }
    }

    private void endTurn() {
        thinking = false;
        hasFinishedTurn = true;
        lastTurnEnd = scheduler.nowMillis();
        speaker.thinking(false);
        maybeStartTurn();
    }
}
