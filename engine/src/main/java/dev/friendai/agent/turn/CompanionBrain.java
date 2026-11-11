package dev.friendai.agent.turn;

import dev.friendai.agent.goal.GoalView;
import dev.friendai.agent.llm.RequestTelemetry;
import dev.friendai.agent.llm.model.LlmResponse;
import dev.friendai.agent.llm.model.Message;
import dev.friendai.agent.llm.model.StopReason;
import dev.friendai.agent.llm.model.ToolCall;
import dev.friendai.agent.llm.model.ToolSpec;
import dev.friendai.agent.llm.stream.StreamAccumulator;
import dev.friendai.agent.llm.stream.StreamingLlmGateway;
import dev.friendai.agent.plan.PlanFailure;
import dev.friendai.agent.plan.PlanProposal;
import dev.friendai.agent.plan.PlanSubject;
import dev.friendai.agent.plan.PlannerException;
import dev.friendai.agent.plan.PlannerInput;
import dev.friendai.agent.recover.FailureHandler;
import dev.friendai.agent.route.IntentVocabulary;
import dev.friendai.agent.route.PlanNeed;
import dev.friendai.agent.route.PlanningDecision;
import dev.friendai.agent.route.RouteDecision;
import dev.friendai.agent.route.RouteSignals;
import dev.friendai.agent.route.TurnMode;
import dev.friendai.agent.route.TurnRouter;
import dev.friendai.agent.tools.ToolCatalog;
import dev.friendai.agent.tools.ToolGroup;
import dev.friendai.execution.tool.AccessLevel;
import dev.friendai.execution.tool.Caller;
import dev.friendai.execution.tool.ToolExecutor;
import dev.friendai.execution.tool.ToolResult;
import dev.friendai.features.trading.TradingTurnPort;
import dev.friendai.foundation.text.UserMessage;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;
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
    /** Free time's director tool: once it succeeds, the turn is over. */
    static final String CHOOSE_ACTIVITY = "choose_activity";
    /** The marker the failure handler puts in an event when no replans are left. */
    private static final String BUDGET_SPENT = FailureHandler.BUDGET_SPENT;
    /** The plan id a queued plan reports, for the durable planner record only; never for control flow. */
    private static final java.util.regex.Pattern PLAN_ID = java.util.regex.Pattern.compile("\\bplan #(\\d{1,9})\\b");
    /** How many queued events one guest may have waiting for a turn. */
    public static final int MAX_PENDING_PER_GUEST = 2;
    /** How long a spoken line and the owner's coordinate question stay relevant. */
    private static final long SPEECH_WINDOW_MS = 120_000L;
    /** How many recent lines an event turn is checked against before it speaks again. */
    private static final int RECENT_SPEECH_LINES = 4;
    /** At most this many principal histories; the owner and the active turn are never evicted. */
    private static final int MAX_HISTORIES = 32;
    /** Old plan blockers are evicted so repeated queue notifications cannot grow this cooldown map forever. */
    private static final int MAX_AUTHORITY_QUESTION_KEYS = 64;

    private final Supplier<Persona> persona;
    private final Supplier<BrainSettings> settings;
    private final Supplier<LlmGateway> gateway;
    private final ToolExecutor tools;
    private final PerceptionSource perception;
    private final Speaker speaker;
    private final BrainScheduler scheduler;
    private final AccessResolver access;
    private final MemorySource memorySource;
    private final int historyMessages;
    /** Principal-scoped conversations, least recently used first; the map access order is the LRU order. */
    private final Map<UUID, PrincipalHistory> histories = new LinkedHashMap<>(16, 0.75f, true);
    /** Last model-turn attempt for a blocked action, bounded and keyed by blocker rather than transient job id. */
    private final LinkedHashMap<String, Long> authorityQuestionAt = new LinkedHashMap<>(16, 0.75f, true);

    private ConversationMemory memory;
    private UUID activePrincipal;
    private BrainObserver observer = BrainObserver.NONE;
    private GoalSource goalSource = GoalSource.NONE;
    private PlaceSource placeSource = PlaceSource.NONE;
    private PlacedSource placedSource = PlacedSource.NONE;
    private PlannerPort plannerPort = PlannerPort.NONE;
    private TradingTurnPort tradingTurnPort = TradingTurnPort.NOOP;
    /** A plan that failed or needs a decision in this turn's batch is the one the goal follows. */
    private boolean ownsTroubledPlan;
    /** What the planner just did; shown once in the next request's Mission block. */
    private String plannerNote = "";

    /** The in-flight planner proposal, dropped by {@link #cancel()} like a late model response. */
    private CompletableFuture<PlanProposal> plannerFuture;
    /** A social batch answers with words only: no schema is sent and no tool call is accepted. */
    private boolean socialTurn;

    private boolean turnSpeechAllowed = true;

    private Supplier<AgentSettings> agentSettings = () -> AgentSettings.DEFAULTS;
    private BiFunction<Caller, String, String> tradingPromptSource = (caller, text) -> "";

    private final Deque<BrainEvent> queue = new ArrayDeque<>();
    private final Map<UUID, Long> lastAcceptedGuestMessage = new HashMap<>();
    private Caller turnCaller;
    private boolean thinking;
    private boolean cooldownScheduled;
    private boolean hasFinishedTurn;
    private long lastTurnEnd;
    private int generation;

    /**
     * The model request this turn is waiting on. Cancelling it releases the transport and any fallback request the
     * streaming client started; the generation counter alone only hides the answer.
     */
    private CompletableFuture<LlmResponse> requestFuture;

    private long requestCounter;
    private long requestIdForGeneration;
    private int requestIdGeneration = -1;
    private int step;
    private TurnKind turnKind = TurnKind.PLAYER;
    private long turnSeq;
    /** The event that opened the running turn, so a silent answer can be replaced by a line the mod knows. */
    private BrainEvent turnEvent;

    private final List<String> turnPlayerTexts = new ArrayList<>();
    private String lastOwnerText;
    private long lastOwnerAtMillis;
    /** What the player just heard, newest first: an event turn repeats none of it. */
    private final Deque<SpokenLine> recentSpeech = new ArrayDeque<>();

    private Set<String> turnToolNames = Set.of();
    /** The tools whose schemas the latest request carried; a call outside it was written from memory. */
    private Set<String> offeredTools = Set.of();

    private final TurnRouter router = new TurnRouter(IntentVocabulary.load());
    private final ToolCatalog catalog;
    /** The tool groups this turn may use; widened by request_tools, reset at every turn start. */
    private final Set<ToolGroup> visibleGroups = EnumSet.allOf(ToolGroup.class);

    private RouteDecision decision =
            new RouteDecision(TurnMode.ACTION, EnumSet.allOf(ToolGroup.class), PlanningDecision.none("none"), "none");

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
        this(
                persona,
                settings,
                gateway,
                tools,
                perception,
                speaker,
                scheduler,
                access,
                memorySource,
                ToolCatalog.defaults());
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
            MemorySource memorySource,
            ToolCatalog catalog) {
        this.persona = persona;
        this.settings = settings;
        this.gateway = gateway;
        this.tools = tools;
        this.perception = perception;
        this.speaker = speaker;
        this.scheduler = scheduler;
        this.access = access;
        this.memorySource = memorySource;
        this.catalog = Objects.requireNonNull(catalog);
        this.turnCaller = new Caller(access.owner(), "", AccessLevel.OWNER);
        this.historyMessages = settings.get().historyMessages();
        this.memory = new ConversationMemory(historyMessages);
    }

    /** A conversation plus the access level it was built under; a level change invalidates the text. */
    private static final class PrincipalHistory {
        private final ConversationMemory memory;
        private AccessLevel access;

        private PrincipalHistory(ConversationMemory memory, AccessLevel access) {
            this.memory = memory;
            this.access = access;
        }
    }

    public void post(BrainEvent e) {
        if (!CompanionSpeechPolicy.needsModelTurn(e)) {
            observe(o -> o.onEventDropped(e, "cancellation retained in task history"));
            return;
        }
        if (shouldDrop(e)) {
            return;
        }
        if (e instanceof BrainEvent.PlanBlocked blocked && !mayAskAuthorityQuestion(blocked)) {
            observe(o -> o.onEventDropped(e, "authority blocker is still pending; question cooldown"));
            return;
        }
        queue.addLast(e);
        maybeStartTurn();
    }

    private boolean mayAskAuthorityQuestion(BrainEvent.PlanBlocked event) {
        String key = CompanionSpeechPolicy.authorityQuestionKey(event);
        long now = scheduler.nowMillis();
        long last = authorityQuestionAt.getOrDefault(key, Long.MIN_VALUE);
        if (!CompanionSpeechPolicy.mayAskAuthorityQuestion(event, now, last)) {
            return false;
        }
        authorityQuestionAt.put(key, now);
        while (authorityQuestionAt.size() > MAX_AUTHORITY_QUESTION_KEYS) {
            authorityQuestionAt.remove(authorityQuestionAt.keySet().iterator().next());
        }
        return true;
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
        long cancelled = turnSeq;
        // One turn ends once: only a request that is actually running has something to cancel. Queued events
        // during the post-turn cooldown belong to no turn, and a delivered turn has already ended.
        if (thinking) observe(o -> o.onTurnEnd(cancelled, "cancelled"));
        generation++;
        CompletableFuture<PlanProposal> planning = plannerFuture;
        plannerFuture = null;
        if (planning != null) planning.cancel(true);
        CompletableFuture<LlmResponse> request = requestFuture;
        requestFuture = null;
        if (request != null) request.cancel(true);
        teachingContext = null;
        skillSession = SkillTurnSession.NONE;
        activePrincipal = null;
        queue.clear();
        streamInFlight = false;
        if (thinking) {
            thinking = false;
            speakSafely("thinking", () -> speaker.thinking(false));
        }
    }

    public boolean isThinking() {
        return thinking;
    }

    /** A turn is pending while a request runs, events wait, or the post-turn cooldown has not elapsed. */
    public boolean hasPendingWork() {
        return thinking || !queue.isEmpty() || cooldownScheduled;
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

    public void setPlacedSource(PlacedSource placedSource) {
        this.placedSource = java.util.Objects.requireNonNull(placedSource);
    }

    private dev.friendai.foundation.teaching.TeachingContext teachingContext;

    public dev.friendai.foundation.teaching.TeachingContext teachingContext() {
        return thinking ? teachingContext : null;
    }

    private boolean skillFreeTimeTurn;
    private boolean streamedTurn;
    /** Releases only whole, guard-parity fragments while the rest of the reply is still arriving. */
    private StreamSpeechPolicy speechPolicy;

    private int earlySequence;
    private boolean earlyIncomplete;
    private String earlyText = "";
    /** Set when the transport refused a streamed frame, so this request sends no more text. */
    private boolean streamSpeechFailed;
    /** True only while a streaming request is in flight; queued deltas of a finished one are dropped. */
    private boolean streamInFlight;
    /**
     * Identity of the current streamed request. The protocol needs a positive id, and the client deduplicates by it,
     * so every model request gets a fresh one: reusing the generation counter made it zero on a fresh brain and equal
     * for every later reply, which the speech client dropped as a duplicate.
     */
    private long speechStream;

    private long streamSeq;

    public boolean isSkillFreeTimeTurn() {
        return skillFreeTimeTurn;
    }

    private SkillSource skillSource = SkillSource.NONE;
    private SkillTurnSession skillSession = SkillTurnSession.NONE;

    public void setSkillSource(SkillSource source) {
        skillSource = java.util.Objects.requireNonNull(source);
    }

    public SkillTurnSession skillSession() {
        return skillSession;
    }

    public void setPlannerPort(PlannerPort plannerPort) {
        this.plannerPort = java.util.Objects.requireNonNull(plannerPort);
    }

    public void setTradingTurnPort(TradingTurnPort tradingTurnPort) {
        this.tradingTurnPort = tradingTurnPort == null ? TradingTurnPort.NOOP : tradingTurnPort;
    }

    /** Supplies owner-private observed offers for this principal's current prompt only. */
    public void setTradingPromptSource(BiFunction<Caller, String, String> source) {
        this.tradingPromptSource = source == null ? (caller, text) -> "" : source;
    }

    /** The system prompt the next request would carry. */
    public String currentSystemPrompt() {
        return buildSystemPrompt(plannerNote);
    }

    private String buildSystemPrompt(String note) {
        return PromptBuilder.system(
                skillSession.persona(persona.get()),
                perception.describe(turnCaller),
                turnCaller,
                memorySource.describe(turnCaller, String.join("\n", turnPlayerTexts)),
                turnKind,
                goalSource.view(turnCaller),
                agentSettings.get().promptBudget(),
                catalog.hints(
                        agentSettings.get().toolRouting() ? visibleGroups : EnumSet.allOf(ToolGroup.class),
                        decision.mode() == TurnMode.CHAT),
                placeSource.describe(turnCaller),
                placedSource.describe(turnCaller),
                note,
                safeTradingPrompt(),
                skillSession.sections(),
                a -> {
                    skillSession.recordAssembly(a.keptSectionIds());
                    var ids = a.keptSectionIds().stream()
                            .filter(id -> id.startsWith("skill:"))
                            .toList();
                    int chars = skillSession.sections().stream()
                            .filter(section -> ids.contains(section.id()))
                            .mapToInt(section -> ((dev.friendai.agent.prompt.SkillPromptSection) section)
                                            .text()
                                            .length()
                                    + 1)
                            .sum();
                    observe(o -> o.onInstructions(
                            skillSession.versionedIds(ids), chars, a.text().length(), a.mandatoryOverrun()));
                });
    }

    private String safeTradingPrompt() {
        if (turnCaller == null
                || turnCaller.access() != AccessLevel.OWNER
                || !turnCaller.uuid().equals(access.owner())) return "";
        try {
            return tradingPromptSource.apply(turnCaller, String.join("\n", turnPlayerTexts));
        } catch (RuntimeException e) {
            LOGGER.debug("Trading prompt summary unavailable", e);
            return "";
        }
    }

    private void observe(java.util.function.Consumer<BrainObserver> call) {
        try {
            call.accept(observer);
        } catch (RuntimeException e) {
            LOGGER.warn("Brain observer failed", e);
        }
    }

    /**
     * A turn that only makes conversation: it carries a social opening and nothing that reports work the companion
     * might have to act on. Such a turn sends no schema, so a batch that also reports a failure or asks for a plan
     * keeps its tools — otherwise the model is told to repair the plan with no tool to repair it with, and answers in
     * markup instead of words.
     */
    private static boolean isChatterOnly(List<BrainEvent> batch) {
        boolean social = false;
        for (BrainEvent event : batch) {
            if (event instanceof BrainEvent.SocialOpening) {
                social = true;
            } else if (!reportsNothingToActOn(event)) {
                return false;
            }
        }
        return social;
    }

    /** Events a words-only reply can absorb whole: a notice, a taught run, a stop or a success. */
    private static boolean reportsNothingToActOn(BrainEvent event) {
        return event instanceof BrainEvent.WorldNotice
                || event instanceof BrainEvent.SkillOutcome
                || event instanceof BrainEvent.TaskCancelled
                || (event instanceof BrainEvent.TaskFinished finished && finished.success())
                || (event instanceof BrainEvent.PlanFinished finished && finished.success());
    }

    /** The player a turn acts for. Task events belong to the owner. */
    private UUID principalOf(BrainEvent event) {
        return event instanceof BrainEvent.PlayerSaid said ? said.playerUuid() : access.owner();
    }

    /** Selects the conversation for this turn; the same history serves every step of the turn. */
    private void beginHistory(UUID principal, AccessLevel level) {
        PrincipalHistory history = histories.get(principal);
        if (history == null) {
            evictHistorySlot();
            history = new PrincipalHistory(new ConversationMemory(historyMessages), level);
            histories.put(principal, history);
        } else if (history.access != level) {
            // A trust change must not carry text or tool output across the authorization boundary.
            history.memory.clear();
            history.access = level;
        }
        memory = history.memory;
        activePrincipal = principal;
    }

    /** Drops the least recently used guest conversation; the owner and the active turn are never evicted. */
    private void evictHistorySlot() {
        if (histories.size() < MAX_HISTORIES) return;
        var iterator = histories.keySet().iterator();
        while (iterator.hasNext()) {
            UUID candidate = iterator.next();
            if (candidate.equals(access.owner()) || candidate.equals(activePrincipal)) continue;
            iterator.remove();
            return;
        }
    }

    private void maybeStartTurn() {
        if (thinking || queue.isEmpty()) {
            return;
        }
        long wait = hasFinishedTurn ? settings.get().cooldownMs() - (scheduler.nowMillis() - lastTurnEnd) : 0;
        if (wait > 0) {
            if (!cooldownScheduled) {
                cooldownScheduled = true;
                scheduler.schedule(
                        () -> {
                            cooldownScheduled = false;
                            maybeStartTurn();
                        },
                        wait);
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
        long startedTurn = ++turnSeq;
        turnEvent = batch.get(0);
        observe(o -> o.onTurnStart(startedTurn));
        AccessLevel level = access.accessOf(principal);
        beginHistory(principal, level);
        Caller candidateCaller = new Caller(principal, name == null ? "" : name, level);
        boolean ownerInitiated = batch.stream()
                .anyMatch(event -> event instanceof BrainEvent.PlayerSaid said
                        && said.playerUuid().equals(access.owner()));
        List<Long> troubledPlans = batch.stream()
                .map(CompanionBrain::troubledPlanId)
                .filter(id -> id >= 0)
                .toList();
        boolean planTrouble = !troubledPlans.isEmpty();
        // Several blocked plans can share one batch, and one of them may already be cancelled or revoked. Name
        // the first plan that still exists and may be repaired, so the subject is a plan the input factory can
        // actually use instead of one it must refuse.
        long troubledPlan = 0L;
        for (long planId : troubledPlans) {
            if (plannerPort.ownsPlan(planId)) {
                troubledPlan = planId;
                break;
            }
        }
        ownsTroubledPlan = troubledPlan > 0;
        boolean budgetSpent =
                batch.stream().anyMatch(event -> event.toPromptLine().contains(BUDGET_SPENT));
        GoalView goalView = goalSource.view(candidateCaller);
        boolean goalTickWithUnmet = batch.stream()
                .anyMatch(event -> event instanceof BrainEvent.GoalTick tick
                        && !tick.unmet().isEmpty());
        RouteSignals turnSignals = new RouteSignals(
                turnKind,
                String.join("\n", turnPlayerTexts),
                !goalView.isEmpty(),
                planTrouble,
                level == AccessLevel.OWNER,
                ownsTroubledPlan,
                goalTickWithUnmet,
                !goalView.planQueued(),
                false,
                budgetSpent,
                troubledPlan,
                "",
                goalView.goalId(),
                goalView.unmet(),
                0L);
        decision = router.route(turnSignals, agentSettings.get().plannerMode());
        observe(o -> o.onRouteDecision(decision));
        // Only an explicit owner trade action may mint a spend scope. Topics, fallback routing and queued
        // work cannot turn a read-only question into authority behind the chat tool filter.
        turnCaller = tradingTurnPort.begin(
                candidateCaller,
                ownerInitiated
                        && decision.mode() != TurnMode.CHAT
                        && decision.groups().contains(ToolGroup.TRADING)
                        && router.explicitlyRequestsTrading(String.join("\n", turnPlayerTexts)));
        skillFreeTimeTurn = batch.stream()
                .anyMatch(e -> e instanceof BrainEvent.FreeTimeShift
                        || (e instanceof BrainEvent.SkillOutcome outcome
                                && (outcome.kind()
                                                == dev.friendai.foundation.teaching.TeachingContext.OutcomeKind
                                                        .SHIFT_SUCCESS
                                        || outcome.kind()
                                                == dev.friendai.foundation.teaching.TeachingContext.OutcomeKind
                                                        .SHIFT_FAILURE)));
        // A recovery turn has no player text of its own, so its failure lines are what the skill triggers must see;
        // ordinary chat and event turns keep only what the player said.
        String skillText = String.join("\n", turnPlayerTexts);
        if (decision.mode() == TurnMode.RECOVERY) {
            StringBuilder withFailure = new StringBuilder(skillText);
            for (BrainEvent event : batch) {
                withFailure.append('\n').append(event.toPromptLine());
            }
            skillText = withFailure.toString();
        }
        skillSession = skillSource.begin(turnCaller, skillText, turnKind);
        teachingContext = null;
        if (turnPlayerTexts.isEmpty() && batch.stream().noneMatch(e -> e instanceof BrainEvent.FreeTimeShift)) {
            batch.stream()
                    .filter(e -> e instanceof BrainEvent.SkillOutcome)
                    .map(e -> (BrainEvent.SkillOutcome) e)
                    .findFirst()
                    .ifPresent(e -> teachingContext = new dev.friendai.foundation.teaching.TeachingContext(
                            access.owner(), e.outcomeId(), generation, e.kind()));
        }
        socialTurn = isChatterOnly(batch);
        turnSpeechAllowed = CompanionSpeechPolicy.maySpeak(batch, !turnPlayerTexts.isEmpty());
        visibleGroups.clear();
        visibleGroups.addAll(decision.groups());
        thinking = true;
        speakSafely("thinking", () -> speaker.thinking(true));
        String lines = batch.stream().map(event -> event.toPromptLine(level)).collect(Collectors.joining("\n"));
        for (BrainEvent event : batch) {
            observe(o -> o.onEvent(event));
        }
        memory.add(Message.user(lines));
        step = 0;
        plannerNote = "";
        // A plan-worthy owner order, an unmet active goal and a failed plan are planned before the model answers.
        // A free-time shift is planned by its driver, not by the chat turn.
        PlanningDecision planning = decision.planning();
        if (planning.need() != PlanNeed.REQUIRED || planning.subject() == null) {
            if (planning.need() == PlanNeed.REQUIRED) {
                // The policy asked for a plan but named nothing to plan: the turn carries on without one.
                observe(o -> o.onPlannerAttempt(new PlannerAttempt(null, 1, "fallback", "no-subject", 0L, 0L)));
            }
            requestStep(generation);
        } else if (planning.subject() instanceof PlanSubject.TroubledPlan trouble) {
            if (ownsTroubledPlan && !lines.contains(BUDGET_SPENT)) {
                plan(generation, trouble, lines);
            } else {
                requestStep(generation);
            }
        } else if (planning.subject() instanceof PlanSubject.FreeTimeShift) {
            requestStep(generation);
        } else {
            plan(generation, planning.subject(), "");
        }
    }

    /**
     * One planner consultation per turn for one subject. The reply is applied on the scheduler thread and shown to
     * the model once, in the Mission block of the next request. A stop, dismissal or logout meanwhile bumps the
     * generation, so a late proposal is dropped without touching the queue (rule 5).
     */
    private void plan(int gen, PlanSubject subject, String failure) {
        Optional<PlannerInput> input;
        try {
            input = plannerPort.inputFor(subject, turnCaller, failure);
        } catch (RuntimeException e) {
            LOGGER.warn("Planner input failed", e);
            input = Optional.empty();
        }
        if (input.isEmpty()) {
            observe(o -> o.onPlannerAttempt(new PlannerAttempt(subject, 1, "fallback", "no-input", 0L, 0L)));
            requestStep(gen);
            return;
        }
        observe(o -> o.onPlannerAttempt(new PlannerAttempt(subject, 1, "request", "", -1L, 0L)));
        long started = System.nanoTime();
        CompletableFuture<PlanProposal> future;
        try {
            future = plannerPort.propose(input.get(), () -> gen != generation);
        } catch (RuntimeException e) {
            future = CompletableFuture.failedFuture(e);
        }
        plannerFuture = future;
        CompletableFuture<PlanProposal> handle = future;
        future.whenComplete((proposal, error) -> scheduler.execute(() -> {
            if (plannerFuture == handle) {
                plannerFuture = null;
            }
            if (gen != generation) {
                return;
            }
            long durationMs = (System.nanoTime() - started) / 1_000_000L;
            if (error != null || proposal == null) {
                Throwable cause = error == null ? null : PlanFailure.unwrap(error);
                PlanFailure.Code code = cause instanceof PlannerException planner
                        ? planner.failure().code()
                        : PlanFailure.Code.REQUEST_FAILED;
                LOGGER.info("Planner gave no plan: {}", code);
                observe(o -> o.onPlannerAttempt(new PlannerAttempt(subject, 1, "failed", code.name(), durationMs, 0L)));
                String reason = fallbackReason(cause);
                observe(o -> o.onPlannerAttempt(new PlannerAttempt(subject, 1, "fallback", reason, durationMs, 0L)));
                plannerNote = "no plan was drafted (" + code + "); plan the next step yourself";
            } else {
                ToolResult applied;
                try {
                    applied = plannerPort.apply(turnCaller, subject, proposal);
                } catch (RuntimeException e) {
                    applied = ToolResult.error("could not queue the plan");
                }
                long planId = applied.error() ? 0L : planIdIn(applied.content());
                if (applied.error()) {
                    observe(o ->
                            o.onPlannerAttempt(new PlannerAttempt(subject, 1, "fallback", "rejected", durationMs, 0L)));
                } else {
                    observe(o ->
                            o.onPlannerAttempt(new PlannerAttempt(subject, 1, "delivered", "", durationMs, planId)));
                }
                plannerNote = applied.error()
                        ? "the planner's plan could not be queued (" + applied.content() + "); plan it yourself"
                        : "a plan was queued (" + applied.content() + "): " + proposal.rationale()
                                + (proposal.expectedOutcome().isBlank()
                                        ? ""
                                        : "; expected: " + proposal.expectedOutcome())
                                + "; run it, do not queue another";
            }
            requestStep(gen);
        }));
    }

    /** One telemetry scope per logical gateway invocation, carrying the turn and generation that started it. */
    private RequestTelemetry telemetryFor(int gen) {
        long requestId = ++requestCounter;
        return new RequestTelemetry(
                requestId, turnSeq, gen, attempt -> observe(o -> o.onRequestAttempt(requestId, attempt)));
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
            List<ToolSpec> specs = tools.specs(turnCaller.access()).stream()
                    .filter(spec -> !spec.name().equals("manage_skill") || teachingContext != null)
                    .toList();
            Set<ToolGroup> groups = agentSettings.get().toolRouting() ? visibleGroups : EnumSet.allOf(ToolGroup.class);
            List<ToolSpec> sent =
                    socialTurn ? List.of() : catalog.filter(specs, groups, decision.mode() == TurnMode.CHAT);
            // ReplyGuard sanitizes internal names across the full registry, even when routing hid a schema.
            turnToolNames = socialTurn
                    ? java.util.Set.of()
                    : specs.stream().map(ToolSpec::name).collect(Collectors.toUnmodifiableSet());
            offeredTools = sent.stream().map(ToolSpec::name).collect(Collectors.toUnmodifiableSet());
            String system = buildSystemPrompt(plannerNote);
            plannerNote = "";
            java.util.List<dev.friendai.agent.llm.model.Message> history = memory.snapshot();
            observe(o -> o.onRequest(system, history, sent));
            LlmGateway active = gateway.get();
            RequestTelemetry telemetry = telemetryFor(gen);
            requestIdForGeneration = telemetry.requestId();
            requestIdGeneration = gen;
            streamedTurn = false;
            if (active instanceof StreamingLlmGateway streaming) {
                streamedTurn = true;
                StreamAccumulator accumulator = new StreamAccumulator();
                speechPolicy = StreamSpeechPolicy.create();
                earlySequence = 0;
                earlyIncomplete = false;
                earlyText = "";
                streamSpeechFailed = false;
                streamInFlight = true;
                speechStream = ++streamSeq;
                long stream = speechStream;
                CompletableFuture<LlmResponse> raw = streaming.completeStreaming(
                        system,
                        history,
                        sent,
                        delta -> {
                            accumulator.accept(delta);
                            scheduler.execute(() -> releaseEarly(gen, stream, accumulator));
                        },
                        telemetry);
                // The raw request, before this turn's own mapping: cancelling the mapped stage would not reach it.
                requestFuture = raw;
                future = raw.thenApply(response -> completeFromStream(accumulator, response));
            } else {
                streamInFlight = false;
                future = active.complete(system, history, sent, telemetry);
                requestFuture = future;
            }
        } catch (RuntimeException e) {
            future = CompletableFuture.failedFuture(e);
        }
        future.whenComplete((response, error) -> scheduler.execute(() -> onResponse(gen, response, error)));
    }

    /**
     * The provider's full response is authoritative; if a streaming provider returns an empty envelope while its
     * increments carried text or tool calls, the accumulator reconstructs it. Tool calls still execute only after the
     * stream is complete, never from an increment.
     */
    private void releaseEarly(int gen, long stream, StreamAccumulator accumulator) {
        // A delta that was queued before its request finished must never speak during a later one: the identity,
        // the in-flight flag and the generation each reject a different kind of late frame.
        if (gen != generation
                || stream != speechStream
                || !streamInFlight
                || !thinking
                || !turnSpeechAllowed
                || speechPolicy == null
                || !streamedTurn
                || turnCaller == null) {
            return;
        }
        String text = accumulator.text();
        earlyText = text;
        offerEarly(speechPolicy.offer(text, guardParity(text)));
    }

    private void offerEarly(StreamSpeechPolicy.Decision decision) {
        switch (decision) {
            case StreamSpeechPolicy.Speak speak -> {
                if (!speak.text().isBlank() || speak.last()) {
                    String text = earlySequence == 0 || speak.text().isBlank() ? speak.text() : " " + speak.text();
                    sendStreamed(text, speak.last());
                }
            }
            case StreamSpeechPolicy.StopIncomplete ignored -> {
                earlyIncomplete = true;
                if (earlySequence > 0) {
                    speakSafely(
                            "stream incomplete notice",
                            () -> speaker.error(
                                    dev.friendai.foundation.text.UserMessage.of("friendai.voice.stream_incomplete")));
                }
            }
            case StreamSpeechPolicy.Hold ignored -> {}
        }
    }

    /**
     * One streamed sentence. The sequence advances only when the transport accepted the frame, so a failure leaves
     * no gap the client would wait in. After the first failure the request sends no more text and makes one
     * best-effort empty closing frame at the failed sequence; a text retry would duplicate audio the player may
     * already have heard.
     */
    private void sendStreamed(String text, boolean last) {
        if (streamSpeechFailed) {
            return;
        }
        try {
            speaker.streamSentence(speechStream, speechStream, earlySequence, text, last, turnCaller.uuid());
            earlySequence++;
        } catch (RuntimeException e) {
            streamSpeechFailed = true;
            LOGGER.warn("Streamed speech delivery failed; closing the stream", e);
            closeStreamQuietly();
        }
    }

    /** The one empty {@code last} frame that lets the client finish an interrupted stream; never throws. */
    private void closeStreamQuietly() {
        try {
            speaker.streamSentence(speechStream, speechStream, earlySequence, "", true, turnCaller.uuid());
        } catch (RuntimeException e) {
            LOGGER.warn("Could not close an interrupted speech stream", e);
        }
    }

    /** Delivers the reply outside the turn's state machine: a broken transport is logged, never fatal. */
    private void speakSafely(String what, Runnable delivery) {
        try {
            delivery.run();
        } catch (RuntimeException e) {
            LOGGER.warn("Speaker failed: {}", what, e);
        }
    }

    /**
     * Speaks the guarded reply. Callers keep history, the reply guard's record, the reply observation and the turn
     * cleanup outside this method, so a failed delivery cannot skip them.
     */
    private void deliverSpeech(String spoken, String raw) {
        if (!streamedTurn) {
            speakSafely("reply", () -> speaker.say(spoken, turnCaller.uuid()));
            return;
        }
        speakSafely("reply", () -> speaker.say(spoken));
        if (speechPolicy != null) {
            if (!earlyIncomplete) {
                offerEarly(speechPolicy.finish(earlyText.isBlank() ? raw : "", guardParity(raw)));
            }
            return;
        }
        List<String> sentences = dev.friendai.features.speech.tts.SpeechText.cleanAndSplit(spoken, 300);
        for (int i = 0; i < sentences.size(); i++) {
            sendStreamed(sentences.get(i), i == sentences.size() - 1);
        }
    }

    private boolean guardParity(String text) {
        String raw = text == null ? "" : text;
        return ReplyGuard.filter(raw, coordinatesAllowed(), recentSpeech(), turnToolNames)
                .map(raw.strip()::equals)
                .orElse(false);
    }

    private static LlmResponse completeFromStream(StreamAccumulator accumulator, LlmResponse response) {
        if (response == null) {
            return accumulator.response(StopReason.END);
        }
        if (response.text().isBlank()
                && response.toolCalls().isEmpty()
                && (!accumulator.text().isBlank() || !accumulator.toolCalls().isEmpty())) {
            return accumulator.response(response.stopReason());
        }
        return response;
    }

    private void onResponse(int gen, LlmResponse response, Throwable error) {
        if (gen == generation && response != null && response.usage() != null)
            observe(o -> o.onUsage(response.usage()));
        if (gen != generation) {
            long discarded = requestIdForGeneration;
            if (requestIdGeneration == gen && discarded > 0L) {
                observe(o -> o.onRequestDiscarded(discarded, "stale_generation"));
            }
            return;
        }
        // The authoritative response has arrived: an early delta still queued behind it must not speak.
        streamInFlight = false;
        if (response != null) {
            observe(o -> o.onResponse(response));
        }
        if (error != null) {
            LOGGER.debug("LLM request failed: {}", error.toString());
            observe(o -> o.onError(error));
            long failed = turnSeq;
            observe(o -> o.onTurnEnd(failed, "error"));
            speakSafely("error", () -> speaker.error(ErrorMessages.forUser(error)));
            endTurn();
            return;
        }
        String raw = response.text();
        if (!raw.isBlank()) {
            observe(o -> o.onAssistantText(raw));
        }
        String spoken = null;
        if (!raw.isBlank() && turnSpeechAllowed) {
            Optional<String> guarded = ReplyGuard.filter(raw, coordinatesAllowed(), recentSpeech(), turnToolNames);
            if (guarded.isPresent()) {
                spoken = guarded.get();
                deliverSpeech(spoken, raw);
                rememberSpoken(spoken);
                String heard = spoken;
                observe(o -> o.onReply(turnCaller, heard));
                long delivered = turnSeq;
                observe(o -> o.onTurnEnd(delivered, "delivered"));
            }
        }
        List<ToolCall> calls = response.toolCalls();
        if (!raw.isBlank() || !calls.isEmpty()) {
            String remembered = spoken != null ? spoken : (raw.isBlank() ? raw : ReplyGuard.SILENT);
            memory.add(Message.assistant(remembered, calls));
        }
        if (calls.isEmpty()) {
            answerIdleGoal(spoken);
            endTurn();
            return;
        }
        boolean queuedByModel = false;
        boolean activityChosen = false;
        boolean anyCallFailed = false;
        boolean queueMutatedBesidesAdd = false;
        for (ToolCall call : calls) {
            ToolResult result;
            observe(o -> o.onToolCall(call));
            Optional<ToolGroup> revealed = revealHiddenGroup(call.name());
            try {
                if ("trade_item".equals(call.name())
                        && (decision.mode() == TurnMode.CHAT
                                || !decision.groups().contains(ToolGroup.TRADING))) {
                    result = ToolResult.error("trading is not available for this request");
                } else {
                    result = REQUEST_TOOLS.equals(call.name()) ? requestTools(call) : tools.execute(call, turnCaller);
                }
            } catch (RuntimeException e) {
                LOGGER.warn("Tool {} threw", call.name(), e);
                result = ToolResult.error("Tool " + call.name() + " failed: " + e.getMessage());
            }
            if (result.error() && revealed.isPresent()) {
                result = ToolResult.error(result.content() + " The "
                        + revealed.get().name().toLowerCase(java.util.Locale.ROOT)
                        + " tools were not loaded when you made this call; they are loaded now, so read the schema of "
                        + call.name() + " and call it again.");
            }
            ToolResult finalResult = result;
            observe(o -> o.onToolResult(call, finalResult));
            memory.add(Message.toolResult(call.id(), call.name(), result.content()));
            queuedByModel |= !result.error() && "queue_add".equals(call.name());
            activityChosen |= !result.error() && CHOOSE_ACTIVITY.equals(call.name());
            anyCallFailed |= result.error();
            // An edit or patch after the add changes the work the reply just described, so its result must be read.
            queueMutatedBesidesAdd |=
                    !result.error() && ("queue_edit".equals(call.name()) || "plan_patch".equals(call.name()));
        }
        if (activityChosen) {
            // Free time's choice is the whole answer; the planner does the rest, so the turn costs one request.
            endTurn();
            return;
        }
        if (queuedByModel && !anyCallFailed && !queueMutatedBesidesAdd && spoken != null && !spoken.isBlank()) {
            // A guarded reply exists and this response did not subsequently invalidate the queued work: the owner has
            // the answer, queue_add validated the plan and started it, and another request would only re-read the id.
            endTurn();
            return;
        }
        if (queuedByModel && !anyCallFailed && !queueMutatedBesidesAdd && spoken == null && isRecoveryTurn()) {
            // A replacement plan is the useful outcome of an autonomous recovery; do not ask the model to narrate it.
            endTurn();
            return;
        }
        requestStep(gen);
    }

    private boolean isRecoveryTurn() {
        return turnKind == TurnKind.EVENT
                && (decision.mode() == TurnMode.RECOVERY
                        || turnEvent instanceof BrainEvent.Replan
                        || turnEvent instanceof BrainEvent.PlanBlocked
                        || turnEvent instanceof BrainEvent.TaskFinished task && !task.success()
                        || turnEvent instanceof BrainEvent.PlanFinished plan && !plan.success());
    }

    /** The plan id the queue reports in a result line; zero when the text does not carry one. */
    private static long planIdIn(String content) {
        var match = PLAN_ID.matcher(content == null ? "" : content);
        return match.find() ? Long.parseLong(match.group(1)) : 0L;
    }

    /** Why the turn goes on without a plan, as the durable record names it. */
    private static String fallbackReason(Throwable cause) {
        if (!(cause instanceof PlannerException planner)) return "transport";
        return switch (planner.failure().code()) {
            case REQUEST_FAILED -> "transport";
            case CANCELLED -> "cancelled";
            default -> "parse";
        };
    }

    /** The plan an event reports as failed or blocked, or -1 for anything else (a stop or cancel is not trouble). */
    private static long troubledPlanId(BrainEvent event) {
        if (event instanceof BrainEvent.Replan replan) {
            return replan.planId();
        }
        if (event instanceof BrainEvent.PlanBlocked blocked) {
            return blocked.planId();
        }
        if (event instanceof BrainEvent.PlanFinished finished
                && !finished.success()
                && !FailureHandler.isCancellation(finished.summary())) {
            return finished.planId();
        }
        return -1;
    }

    /**
     * Shows the group of a tool the model called although routing had hidden it. Such a call is written from memory,
     * without a schema, which is where a refused argument comes from; with the group visible for the rest of the turn
     * the next request carries the schema, so the model can repeat the call correctly instead of guessing again.
     */
    private Optional<ToolGroup> revealHiddenGroup(String tool) {
        if (socialTurn || offeredTools.contains(tool) || !agentSettings.get().toolRouting()) {
            return Optional.empty();
        }
        Optional<ToolGroup> group =
                catalog.meta(tool).filter(meta -> !meta.core()).map(dev.friendai.agent.tools.ToolMeta::group);
        if (group.isEmpty() || !visibleGroups.add(group.get())) {
            return Optional.empty();
        }
        return group;
    }

    /** Unlocks a tool group for the rest of the turn; an unknown group is an error listing the valid ones. */
    private ToolResult requestTools(ToolCall call) {
        String wanted = call.arguments() != null
                        && call.arguments().has("group")
                        && call.arguments().get("group").isJsonPrimitive()
                ? call.arguments().get("group").getAsString()
                : "";
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
                .filter(name -> catalog.meta(name)
                        .map(meta -> meta.group() == group.get()
                                && catalog.visible(name, visibleGroups, decision.mode() == TurnMode.CHAT))
                        .orElse(false))
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

    /**
     * A turn opened by the queue-idle goal nudge exists to repair the plan or to say what blocks it. When the
     * model answers silently the owner sees a companion that stands still and says nothing, so the blocked
     * line comes from the mod instead.
     */
    private void answerIdleGoal(String spoken) {
        if (spoken != null || !(turnEvent instanceof BrainEvent.GoalTick tick)) {
            return;
        }
        speakSafely("blocked goal", () -> speaker.say(UserMessage.of("friendai.companion.goal_blocked", tick.goal())));
        observe(o -> o.onTurnEnd(turnSeq, "delivered"));
    }

    private void endTurn() {
        teachingContext = null;
        thinking = false;
        streamInFlight = false;
        requestFuture = null;
        hasFinishedTurn = true;
        lastTurnEnd = scheduler.nowMillis();
        // Internal state is already settled: an external indicator that fails must not stop the next turn.
        speakSafely("thinking", () -> speaker.thinking(false));
        activePrincipal = null;
        maybeStartTurn();
    }
}
