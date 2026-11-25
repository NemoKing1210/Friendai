package dev.friendai.agent.turn;

import dev.friendai.agent.goal.GoalView;
import dev.friendai.agent.llm.RequestTelemetry;
import dev.friendai.agent.llm.model.LlmErrorKind;
import dev.friendai.agent.llm.model.LlmException;
import dev.friendai.agent.llm.model.LlmResponse;
import dev.friendai.agent.llm.model.Message;
import dev.friendai.agent.llm.model.Role;
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
import dev.friendai.agent.recover.RecoveryAdvisor;
import dev.friendai.agent.recover.RecoveryIncidentBook;
import dev.friendai.agent.route.IntentVocabulary;
import dev.friendai.agent.route.PlanNeed;
import dev.friendai.agent.route.PlanningDecision;
import dev.friendai.agent.route.RouteDecision;
import dev.friendai.agent.route.RouteSignals;
import dev.friendai.agent.route.TurnMode;
import dev.friendai.agent.route.TurnRouter;
import dev.friendai.agent.tools.Cost;
import dev.friendai.agent.tools.ToolCatalog;
import dev.friendai.agent.tools.ToolGroup;
import dev.friendai.execution.tool.AccessLevel;
import dev.friendai.execution.tool.Caller;
import dev.friendai.execution.tool.ToolExecutor;
import dev.friendai.execution.tool.ToolResult;
import dev.friendai.features.conversation.Audience;
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
    /** The one tool whose schema a request carries only while a taught run is being recorded. */
    private static final String MANAGE_SKILL = "manage_skill";
    /**
     * The core tools a proposal turn keeps: core schemas are offered whatever the groups say, so the ones that can
     * change the queue or the goal are dropped by name. A new core tool is not offered until it is listed here.
     */
    private static final Set<String> PROPOSAL_CORE_TOOLS = Set.of("queue_show", "plan_help", "plan_validate");
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
    /** Plan tools worth naming when the model's attempts on one incident are shown back to it. */
    private static final Set<String> PLANNING_TOOLS = Set.of("queue_add", "queue_edit", "plan_patch", "replan_plan");

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
    /** What one blocked job has already spent on recovery, so a chain of unrelated jobs cannot reset it. */
    private final RecoveryIncidentBook incidents = new RecoveryIncidentBook(new RecoveryAdvisor());

    private final RepeatedCallGuard repeatedCalls = new RepeatedCallGuard();
    /** This turn's batch reports a block or a failure, so a spent incident makes it a words-only turn. */
    private boolean recoveryTurn;

    private ConversationMemory memory;
    private UUID activePrincipal;
    private BrainObserver observer = BrainObserver.NONE;
    private GoalSource goalSource = GoalSource.NONE;
    private PlaceSource placeSource = PlaceSource.NONE;
    private PlacedSource placedSource = PlacedSource.NONE;
    private PlannerPort plannerPort = PlannerPort.NONE;
    private TradingTurnPort tradingTurnPort = TradingTurnPort.NOOP;
    /** Delivered lines a principal's conversation starts from when this brain first answers them. */
    private RecentConversationSource recentConversation = RecentConversationSource.NONE;

    /** A cleared transcript must also leave the live conversation it backed; applied when the turn settles. */
    private boolean clearConversationOnTurnEnd;
    /** A plan that failed or needs a decision in this turn's batch is the one the goal follows. */
    private boolean ownsTroubledPlan;
    /**
     * The plans and goals this turn is about. A background job failing elsewhere is news for the next turn, not a
     * reason to discard an answer that is still valid for the current order.
     */
    private final Set<Long> turnPlanIds = new java.util.HashSet<>();

    private final Set<Long> turnGoalIds = new java.util.HashSet<>();
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
    /** The current turn already reported its one terminal outcome; later paths must not report again. */
    private boolean turnEndReported;
    /** The owner was spoken to during the current turn, so its end is reported as {@code delivered}. */
    private boolean turnReplyDelivered;

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
    private boolean playerOnlyBatch = true;
    private long turnSeq;
    /** Who the current player line was meant for; event-only turns speak nearby. */
    private Audience turnAudience = Audience.NEARBY;
    /** The event that opened the running turn, so a silent answer can be replaced by a line the mod knows. */
    private BrainEvent turnEvent;
    /** The memory query for this turn: what the player said, or for a recovery the goal and its blocker. */
    private String turnMemoryQuery = "";
    /** The most recovery evidence one memory query carries, so an event batch cannot flood retrieval. */
    private static final int RECOVERY_QUERY_CHARS = 400;

    private final List<String> turnPlayerTexts = new ArrayList<>();
    /**
     * What the talking player pointed at when their line was accepted, kept for the turn so a delayed answer still
     * means the block they pointed at. The last line that carried a target wins; a batch of blind lines falls back
     * to the last captured "nothing there".
     */
    private Optional<UtteranceReferent> turnReferent = Optional.empty();

    private String lastOwnerText;
    private long lastOwnerAtMillis;
    /** What the player just heard, newest first: an event turn repeats none of it. */
    private final Deque<SpokenLine> recentSpeech = new ArrayDeque<>();

    private Set<String> turnToolNames = Set.of();
    /**
     * The tools whose schemas the latest request actually carried, after routing, the teaching filter and the token
     * budget. A call outside this set was written from memory or from another turn and is refused without effect.
     */
    private Set<String> offeredTools = Set.of();

    private final TurnRouter router = new TurnRouter(IntentVocabulary.load());
    private final ToolCatalog catalog;
    /** The tool groups this turn may use; widened by request_tools, reset at every turn start. */
    private final Set<ToolGroup> visibleGroups = EnumSet.allOf(ToolGroup.class);
    /** One plan per principal that was shown for approval and not started yet. */
    private final Map<UUID, PendingPlan> pendingPlans = new HashMap<>();
    /** True while this turn only presents a plan: no work, queue change or goal change may start. */
    private boolean proposalTurn;

    private record PendingPlan(PlanSubject.OwnerOrder order, PlanProposal proposal) {}

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
        // Recorded before the drops below: a blockage the authority cooldown keeps out of the queue still belongs
        // to the incident it names, and a cancellation still ends one.
        feedIncidents(e);
        if (!CompanionSpeechPolicy.needsModelTurn(e)) {
            observe(o -> o.onEventDropped(e, EventDropReasons.CANCELLATION));
            return;
        }
        if (shouldDrop(e)) {
            return;
        }
        boolean supersedes = e instanceof BrainEvent.PlayerSaid said && revisesTheCurrentOrder(said);
        if (e instanceof BrainEvent.PlanBlocked blocked && !mayAskAuthorityQuestion(blocked)) {
            observe(o -> o.onEventDropped(e, EventDropReasons.AUTHORITY_COOLDOWN));
            return;
        }
        // The correction itself is kept: it is the newest authorized intent and opens the next turn. Only the answer
        // already in flight is dropped, so the old reasoning cannot speak or act for an order the owner replaced.
        queue.addLast(e);
        if (supersedes) abortForCorrection();
        maybeStartTurn();
    }

    /**
     * True when the owner's line restates the action the running turn is about. A guest never corrects the owner's
     * order. Only the owner's own order can be revised: a turn already answering an event continues, so an aside
     * ("where is my coal?") does not discard the news it was about to report.
     */
    private boolean revisesTheCurrentOrder(BrainEvent.PlayerSaid said) {
        return thinking
                && turnKind == TurnKind.PLAYER
                && turnCaller != null
                && said.playerUuid().equals(access.owner())
                && said.playerUuid().equals(turnCaller.uuid())
                && router.marksCorrection(said.text());
    }

    /**
     * Drops the answer in flight so a late response is rejected by its generation, and lets the queued correction
     * open the next turn. The task already running is not touched: stopping work stays the owner's explicit stop.
     */
    private void abortForCorrection() {
        reportTurnEnd("superseded");
        generation++;
        CompletableFuture<PlanProposal> planning = plannerFuture;
        plannerFuture = null;
        if (planning != null) planning.cancel(true);
        CompletableFuture<LlmResponse> request = requestFuture;
        requestFuture = null;
        if (request != null) request.cancel(true);
        teachingContext = null;
        skillSession = SkillTurnSession.NONE;
        streamInFlight = false;
        thinking = false;
        activePrincipal = null;
        turnReferent = Optional.empty();
        speakSafely("thinking", () -> speaker.thinking(false));
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
     * One outcome, recorded once at settlement: a blocked job opens or charges its incident, a stand-alone failure is
     * a remedy of the open one, and success, a repair, a cancellation or the owner's own line ends it.
     */
    private void feedIncidents(BrainEvent event) {
        switch (event) {
            case BrainEvent.PlanBlocked blocked ->
                incidents.blocked(
                        blocked.planId(), blocked.jobId(), blocked.toolName(), blocked.reason(), blocked.extra());
            case BrainEvent.Replan replan -> incidents.repaired(replan.planId());
            case BrainEvent.TaskFinished task -> {
                if (task.success()) {
                    incidents.succeeded(task.planId());
                } else if (!task.disposition().failure() || FailureHandler.isCancellation(task.summary())) {
                    incidents.cancelled();
                } else {
                    incidents.failed(task.planId(), task.toolName(), task.summary(), task.extra());
                }
            }
            case BrainEvent.PlanFinished plan -> incidents.settled(plan.planId());
            case BrainEvent.TaskCancelled ignored -> incidents.cancelled();
            case BrainEvent.PlayerSaid said -> {
                if (said.playerUuid().equals(access.owner())) incidents.ownerSpoke();
            }
            default -> {}
        }
    }

    /** Events that describe a blocked or failed attempt rather than routine news. */
    private static boolean isIncidentReport(BrainEvent event) {
        return event instanceof BrainEvent.PlanBlocked
                || (event instanceof BrainEvent.TaskFinished task
                        && task.disposition().failure()
                        && !FailureHandler.isCancellation(task.summary()));
    }

    /**
     * A bounded memory query for a recovery turn: the active goal, its unmet needs, what the owner asked this turn
     * and the failure that stopped it. The order and the goal carry the owner's own words, so a fact saved in their
     * language still matches in English or Russian.
     */
    private static String recoveryMemoryQuery(GoalView goal, String playerText, String evidence) {
        StringBuilder query = new StringBuilder();
        if (goal != null && !goal.isEmpty()) {
            query.append(goal.text());
            if (!goal.unmet().isEmpty()) {
                query.append(" (").append(String.join("; ", goal.unmet())).append(')');
            }
        }
        for (String part : new String[] {playerText, evidence}) {
            if (part == null || part.isBlank() || query.length() >= RECOVERY_QUERY_CHARS) {
                continue;
            }
            if (query.length() > 0) {
                query.append('\n');
            }
            int room = RECOVERY_QUERY_CHARS - query.length();
            query.append(part, 0, Math.min(part.length(), room));
        }
        return query.length() <= RECOVERY_QUERY_CHARS ? query.toString() : query.substring(0, RECOVERY_QUERY_CHARS);
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
            observe(o -> o.onEventDropped(event, EventDropReasons.GUEST_COOLDOWN));
            return true;
        }
        long pending = queue.stream()
                .filter(queued -> principalOf(queued).equals(guest))
                .count();
        if (pending >= MAX_PENDING_PER_GUEST) {
            observe(o -> o.onEventDropped(event, EventDropReasons.GUEST_BACKLOG));
            return true;
        }
        lastAcceptedGuestMessage.put(guest, now);
        return false;
    }

    /** Drops the in-flight request (its response will be ignored) and all queued events. */
    public void cancel() {
        // One turn ends once: only a request that is actually running has something to cancel. Queued events
        // during the post-turn cooldown belong to no turn, and a delivered turn has already ended.
        if (thinking) reportTurnEnd("cancelled");
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
        turnReferent = Optional.empty();
        queue.clear();
        turnPlanIds.clear();
        turnGoalIds.clear();
        incidents.cancelled();
        pendingPlans.clear();
        recoveryTurn = false;
        turnMemoryQuery = "";
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

    /** Identity of the active player turn, scoped to this brain instance. */
    public long playerTurnId(UUID principal) {
        return thinking && turnKind == TurnKind.PLAYER && principal.equals(activePrincipal) ? turnSeq : 0;
    }

    /** The player text of the active turn, joined in arrival order; empty outside a player turn. */
    public String currentPlayerText() {
        return turnKind == TurnKind.PLAYER ? String.join("\n", turnPlayerTexts) : "";
    }

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

    /**
     * Where a recreated principal conversation may start from. Consulted once per principal, when its history is
     * created; {@link RecentConversationSource#NONE} leaves every conversation to start empty.
     */
    public void setRecentConversationSource(RecentConversationSource source) {
        this.recentConversation = source == null ? RecentConversationSource.NONE : source;
    }

    /**
     * Forgets every live principal conversation. Called after the transcript was cleared, so neither the text still
     * in memory nor a restored seed can bring the cleared exchange back. A turn already running is forgotten when it
     * ends; a queued turn then starts empty. Facts and other memory stores are not touched.
     */
    public void clearConversationHistory() {
        for (PrincipalHistory history : histories.values()) {
            history.memory.clear();
        }
        if (thinking) {
            clearConversationOnTurnEnd = true;
        } else {
            memory.clear();
        }
    }

    public void setTradingTurnPort(TradingTurnPort tradingTurnPort) {
        this.tradingTurnPort = tradingTurnPort == null ? TradingTurnPort.NOOP : tradingTurnPort;
    }

    /** Supplies owner-private observed offers for this principal's current prompt only. */
    public void setTradingPromptSource(BiFunction<Caller, String, String> source) {
        this.tradingPromptSource = source == null ? (caller, text) -> "" : source;
    }

    /**
     * Drops the core schemas a proposal turn must not carry. Core tools ignore the group filter, so the queue and goal
     * tools would otherwise reach a turn whose whole point is that nothing starts.
     */
    private List<ToolSpec> proposalTools(List<ToolSpec> offered) {
        if (!proposalTurn) return offered;
        return offered.stream()
                .filter(spec ->
                        !catalog.meta(spec.name()).map(meta -> meta.core()).orElse(false)
                                || PROPOSAL_CORE_TOOLS.contains(spec.name()))
                .toList();
    }

    /**
     * The groups this request may offer. A proposal turn is the one case where routing cannot widen them: the owner
     * asked to see a plan before anything runs, so only observation is available whatever the settings say.
     */
    private Set<ToolGroup> promptGroups() {
        if (proposalTurn) return ToolCatalog.readOnlyGroups();
        return agentSettings.get().toolRouting() ? visibleGroups : EnumSet.allOf(ToolGroup.class);
    }

    /** The system prompt the next request would carry. */
    public String currentSystemPrompt() {
        return buildSystemPrompt(plannerNote).text();
    }

    private dev.friendai.agent.llm.model.SystemPrompt buildSystemPrompt(String note) {
        return PromptBuilder.systemPrompt(
                skillSession.persona(persona.get()),
                perception.describe(turnCaller, turnReferent),
                turnCaller,
                memorySource.describe(turnCaller, turnMemoryQuery),
                turnKind,
                decision.mode(),
                goalSource.view(turnCaller),
                agentSettings.get().promptBudget(),
                catalog.hints(promptGroups(), decision.mode() == TurnMode.CHAT),
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
            ConversationMemory fresh = new ConversationMemory(historyMessages);
            seedRecentConversation(fresh, principal, level);
            history = new PrincipalHistory(fresh, level);
            histories.put(principal, history);
        } else if (history.access != level) {
            // A trust change must not carry text or tool output across the authorization boundary.
            history.memory.clear();
            history.access = level;
        }
        memory = history.memory;
        activePrincipal = principal;
    }

    /**
     * Starts a principal's conversation from the delivered lines an earlier session left behind, so a follow-up
     * after the companion was recreated can refer to the exchange before it. Only a player line and a companion
     * line that was actually spoken become history — never a tool call or a tool result — and the conversation must
     * start at a player line. A guest's conversation is never restored, a failing source costs only the continuity,
     * and a source that returns anything else is ignored line by line.
     */
    private void seedRecentConversation(ConversationMemory fresh, UUID principal, AccessLevel level) {
        if (level == AccessLevel.GUEST) {
            return;
        }
        List<Message> recent;
        try {
            recent = recentConversation.recent(
                    new Caller(principal, "", level), RecentConversationSource.BUDGET_MESSAGES);
        } catch (RuntimeException e) {
            LOGGER.debug("Recent conversation unavailable", e);
            return;
        }
        if (recent == null || recent.isEmpty()) {
            return;
        }
        List<Message> delivered = new ArrayList<>(recent.size());
        for (Message message : recent) {
            if (message == null || message.text().isBlank()) {
                continue;
            }
            if (message.role() == Role.USER) {
                delivered.add(Message.user(message.text()));
            } else if (message.role() == Role.ASSISTANT && message.toolCalls().isEmpty()) {
                delivered.add(Message.assistant(message.text(), List.of()));
            }
        }
        int start = 0;
        while (start < delivered.size() && delivered.get(start).role() != Role.USER) {
            start++;
        }
        if (start == delivered.size()) {
            return;
        }
        fresh.seed(delivered.subList(start, delivered.size()));
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
        turnReferent = Optional.empty();
        turnAudience = Audience.NEARBY;
        for (BrainEvent event : batch) {
            if (event instanceof BrainEvent.PlayerSaid said) {
                name = said.playerName();
                turnPlayerTexts.add(said.text());
                turnAudience = said.audience();
                said.referent()
                        .filter(referent -> referent.hasTarget() || turnReferent.isEmpty())
                        .ifPresent(referent -> turnReferent = Optional.of(referent));
                if (said.playerUuid().equals(access.owner())) {
                    lastOwnerText = said.text();
                    lastOwnerAtMillis = scheduler.nowMillis();
                }
            }
        }
        turnKind = turnPlayerTexts.isEmpty() ? TurnKind.EVENT : TurnKind.PLAYER;
        // A batch that is nothing but player lines is a conversation or an order; a failure or a world notice mixed
        // into it still deserves the model's own answer.
        playerOnlyBatch = batch.stream().allMatch(event -> event instanceof BrainEvent.PlayerSaid);
        long startedTurn = ++turnSeq;
        turnEndReported = false;
        turnReplyDelivered = false;
        repeatedCalls.clear();
        turnEvent = batch.get(0);
        // A spent incident pauses only the turn that reports its own block or failure; unrelated news keeps its
        // tools and its own work.
        recoveryTurn = batch.stream().anyMatch(CompanionBrain::isIncidentReport);
        boolean recoveryPaused = recoveryTurn && incidents.paused();
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
        turnPlanIds.clear();
        turnPlanIds.addAll(troubledPlans);
        turnGoalIds.clear();
        batch.forEach(event -> {
            if (event instanceof BrainEvent.TaskFinished task && task.goalId() > 0) turnGoalIds.add(task.goalId());
            if (event instanceof BrainEvent.PlanBlocked blocked) turnPlanIds.add(blocked.planId());
        });
        boolean budgetSpent =
                batch.stream().anyMatch(event -> event.toPromptLine().contains(BUDGET_SPENT));
        GoalView goalView = goalSource.view(candidateCaller);
        if (goalView.goalId() > 0) turnGoalIds.add(goalView.goalId());
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
        decision = withEventToolContext(
                router.route(turnSignals, agentSettings.get().plannerMode()), batch);
        observe(o -> o.onRouteDecision(decision));
        proposalTurn = decision.planning().proposeOnly();
        // Only an affirmative owner instruction may mint a spend scope. The words decide, not the route: a blocked
        // plan, a fallback route, a question or a request to see a plan first must not read as consent to spend.
        turnCaller = tradingTurnPort.begin(
                candidateCaller,
                ownerInitiated
                        && !proposalTurn
                        && decision.mode() != TurnMode.CHAT
                        && decision.groups().contains(ToolGroup.TRADING)
                        && router.explicitlyRequestsTrading(String.join("\n", turnPlayerTexts)));
        String approvalNote = "";
        if (turnKind == TurnKind.PLAYER && !turnPlayerTexts.isEmpty()) {
            PendingPlan pending = pendingPlans.remove(turnCaller.uuid());
            if (pending != null) {
                if (router.isPlanApproval(String.join("\n", turnPlayerTexts))) {
                    approvalNote = approvePlan(pending);
                } else {
                    // The request moved on, so the plan drafted for the old one may not run.
                    observe(o -> o.onPlannerAttempt(
                            new PlannerAttempt(pending.order(), 1, "fallback", "proposal-replaced", 0L, 0L)));
                }
            }
        }
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
        String memoryQuery = String.join("\n", turnPlayerTexts);
        if (memoryQuery.isBlank() && recoveryTurn) {
            // A recovery turn has no player text: the goal and the failure lines are what memory should speak to.
            String evidence = batch.stream().map(BrainEvent::toPromptLine).collect(Collectors.joining("\n"));
            memoryQuery = recoveryMemoryQuery(goalView, "", evidence);
        }
        turnMemoryQuery = memoryQuery;
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
        offeredTools = Set.of();
        thinking = true;
        speakSafely("thinking", () -> speaker.thinking(true));
        String lines = batch.stream().map(event -> event.toPromptLine(level)).collect(Collectors.joining("\n"));
        if (recoveryPaused) {
            // The blocker line belongs to the same message as the failure it explains: the model reads both, and
            // this turn has no tools to act on either.
            lines = lines + "\n" + incidents.modelLine();
        }
        for (BrainEvent event : batch) {
            observe(o -> o.onEvent(event));
        }
        memory.add(Message.user(lines));
        step = 0;
        plannerNote = approvalNote;
        if (!approvalNote.isEmpty()) {
            // The owner approved the plan they were shown; it is queued and nothing else may replace it this turn.
            requestStep(generation);
            return;
        }
        if (recoveryPaused) {
            // No repair is safe any more: asking the planner again would spend another request on the same block.
            observe(o -> o.onPlannerAttempt(new PlannerAttempt(null, 1, "fallback", "recovery-spent", 0L, 0L)));
            requestStep(generation);
            return;
        }
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
                plan(generation, trouble, lines, false);
            } else {
                requestStep(generation);
            }
        } else if (planning.subject() instanceof PlanSubject.FreeTimeShift) {
            requestStep(generation);
        } else {
            plan(generation, planning.subject(), "", planning.proposeOnly());
        }
    }

    /**
     * Queues the plan the owner just approved and returns the note the model answers from. The proposal may have
     * gone stale while it waited — the queue, the goal or the principal's access can change — so the queueing call
     * is the final check and a refusal is reported instead of started work.
     */
    private String approvePlan(PendingPlan pending) {
        ToolResult applied;
        try {
            applied = plannerPort.apply(turnCaller, pending.order(), pending.proposal());
        } catch (RuntimeException e) {
            LOGGER.warn("Could not queue the approved plan", e);
            applied = ToolResult.error("could not queue the plan");
        }
        if (applied.error()) {
            observe(o -> o.onPlannerAttempt(new PlannerAttempt(pending.order(), 1, "fallback", "rejected", 0L, 0L)));
            return "The owner approved the plan you proposed, but it could not be queued (" + applied.content()
                    + "); read the queue, then tell them what is blocking it.";
        }
        long planId = planIdIn(applied.content());
        observe(o -> o.onPlannerAttempt(new PlannerAttempt(pending.order(), 1, "delivered", "", 0L, planId)));
        return "The owner approved the plan you proposed (" + applied.content()
                + "); it is queued and will run. Tell them it has started, and do not queue another plan.";
    }

    /**
     * An event turn starts from the event groups. A known task outcome widens it to the repair kit, a shift turn adds
     * {@code AUTONOMY}, and an event whose text names a tool — a failure's remedy line is the one place that happens —
     * adds that tool's group, so a notice can never leave the companion without the tool it was just told to use.
     */
    private RouteDecision withEventToolContext(RouteDecision routed, List<BrainEvent> batch) {
        if (turnKind != TurnKind.EVENT) return routed;
        Set<ToolGroup> named = EnumSet.noneOf(ToolGroup.class);
        boolean knownTask = false;
        for (BrainEvent event : batch) {
            if (event instanceof BrainEvent.FreeTimeShift) {
                // The shift turn exists to choose an activity, so the autonomy tools are its subject.
                named.add(ToolGroup.AUTONOMY);
                continue;
            }
            String tool = event instanceof BrainEvent.TaskFinished task
                    ? task.toolName()
                    : event instanceof BrainEvent.PlanBlocked blocked ? blocked.toolName() : "";
            if (tool.isEmpty()) {
                // A goal, new shift or explicit replan can require work beyond a task's local context.
                if (!reportsNothingToActOn(event) && !(event instanceof BrainEvent.GoalTick)) return routed;
                named.addAll(groupsNamedIn(event.toPromptLine()));
                continue;
            }
            var meta = catalog.meta(tool);
            if (meta.isEmpty()) return routed;
            named.add(meta.get().group());
            knownTask = true;
        }
        if (!knownTask && named.isEmpty()) return routed;
        Set<ToolGroup> groups = knownTask ? ToolCatalog.recoveryGroups() : EnumSet.copyOf(routed.groups());
        groups.addAll(named);
        return new RouteDecision(routed.mode(), groups, routed.planning(), routed.reason());
    }

    /** The groups of the catalog tools an event's text names; the diagnosis line is what makes it useful. */
    private Set<ToolGroup> groupsNamedIn(String text) {
        if (text == null || text.isBlank()) {
            return Set.of();
        }
        Set<ToolGroup> groups = EnumSet.noneOf(ToolGroup.class);
        for (var meta : catalog.all()) {
            if (meta.group() != ToolGroup.CORE && names(text, meta.name())) {
                groups.add(meta.group());
            }
        }
        return groups;
    }

    /** True when the text holds the name as a whole word, so "defeat" is not the {@code eat} tool. */
    private static boolean names(String text, String name) {
        int from = 0;
        while (from <= text.length() - name.length()) {
            int at = text.indexOf(name, from);
            if (at < 0) {
                return false;
            }
            int end = at + name.length();
            boolean leftOk = at == 0 || !isNameChar(text.charAt(at - 1));
            boolean rightOk = end == text.length() || !isNameChar(text.charAt(end));
            if (leftOk && rightOk) {
                return true;
            }
            from = at + 1;
        }
        return false;
    }

    private static boolean isNameChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    /** A newly failed task supersedes the world facts on which an in-flight response was based. */
    private boolean hasPendingFailure() {
        return turnCaller != null
                && queue.stream()
                        .anyMatch(event -> principalOf(event).equals(turnCaller.uuid()) && isRelevantFailure(event));
    }

    /**
     * Only a failure this turn is about can replace its answer. A job that fails in the background belongs to the
     * next turn; discarding a valid reply about another order would cost a model round trip and lose the owner's
     * answer. A stand-alone job has no plan to compare, so it is relevant while the turn answers the owner's own
     * order rather than conversation or unrelated events.
     */
    private boolean isRelevantFailure(BrainEvent event) {
        if (!(event instanceof BrainEvent.TaskFinished task)
                || task.success()
                || !task.disposition().failure()
                || FailureHandler.isCancellation(task.summary())) {
            return false;
        }
        if (task.planId() > 0) {
            return turnPlanIds.contains(task.planId()) || (task.goalId() > 0 && turnGoalIds.contains(task.goalId()));
        }
        if (task.goalId() > 0 && turnGoalIds.contains(task.goalId())) return true;
        return turnKind == TurnKind.PLAYER && !socialTurn;
    }

    /** Read outcomes once before replacing a response that could otherwise announce or start stale work. */
    private boolean refreshAfterFailure(int gen) {
        if (!hasPendingFailure()) return false;
        if (step >= settings.get().maxToolSteps()) {
            endTurnAtStepCeiling();
            return true;
        }
        List<BrainEvent.TaskFinished> outcomes = new ArrayList<>();
        queue.removeIf(event -> {
            if (!isRelevantFailure(event) || !principalOf(event).equals(turnCaller.uuid())) return false;
            outcomes.add((BrainEvent.TaskFinished) event);
            return true;
        });
        if (streamedTurn && earlySequence > 0) closeStreamQuietly();
        for (BrainEvent event : outcomes) observe(o -> o.onEvent(event));
        recoveryTurn = true;
        String outcomeLines = outcomes.stream().map(BrainEvent::toPromptLine).collect(Collectors.joining("\n"));
        if (incidents.paused()) {
            outcomeLines = outcomeLines + "\n" + incidents.modelLine();
        }
        // The follow-up request is a recovery: memory should answer for the goal and the blocker that stopped it,
        // not only for the order that started the turn.
        turnMemoryQuery =
                recoveryMemoryQuery(goalSource.view(turnCaller), String.join("\n", turnPlayerTexts), outcomeLines);
        memory.add(Message.user(outcomeLines));
        visibleGroups.addAll(ToolCatalog.recoveryGroups());
        for (BrainEvent.TaskFinished task : outcomes) {
            catalog.meta(task.toolName()).ifPresent(meta -> visibleGroups.add(meta.group()));
        }
        if (requestIdGeneration == gen && requestIdForGeneration > 0L) {
            observe(o -> o.onRequestDiscarded(requestIdForGeneration, "new_execution_outcome"));
        }
        requestStep(gen);
        return true;
    }

    /**
     * One planner consultation per turn for one subject. The reply is applied on the scheduler thread and shown to
     * the model once, in the Mission block of the next request. A stop, dismissal or logout meanwhile bumps the
     * generation, so a late proposal is dropped without touching the queue (rule 5).
     */
    private void plan(int gen, PlanSubject subject, String failure, boolean proposeOnly) {
        Optional<PlannerInput> input;
        long troubledPlan = subject instanceof PlanSubject.TroubledPlan trouble ? trouble.planId() : 0L;
        try {
            // What the player pointed at with this line is evidence a reference can be resolved from, exactly like
            // the dialogue and the remembered facts; the port decides whether the line needs it.
            UtteranceReferent pointed = turnReferent.orElse(null);
            Optional<PlannerPort.Refusal> refusal = plannerPort.preflight(subject, turnCaller, pointed);
            if (refusal.isPresent()) {
                PlannerPort.Refusal rejected = refusal.get();
                observe(o -> o.onPlannerAttempt(new PlannerAttempt(subject, 1, "fallback", rejected.code(), 0L, 0L)));
                noPlannerProgress(troubledPlan);
                plannerNote = rejected.guidance();
                requestStep(gen);
                return;
            }
            input = plannerPort.inputFor(subject, turnCaller, failure, pointed);
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
            if (hasPendingFailure()) {
                observe(o -> o.onPlannerAttempt(
                        new PlannerAttempt(subject, 1, "fallback", "new-execution-outcome", durationMs, 0L)));
                plannerNote =
                        "The previous proposal predates the new task failure. Check the current work before acting.";
                refreshAfterFailure(gen);
                return;
            }
            if (error != null || proposal == null) {
                Throwable cause = error == null ? null : PlanFailure.unwrap(error);
                PlanFailure planFailure = cause instanceof PlannerException planner ? planner.failure() : null;
                PlanFailure.Code code = planFailure == null ? PlanFailure.Code.REQUEST_FAILED : planFailure.code();
                Map<String, String> facts = planFailure == null ? Map.of() : planFailure.facts();
                LOGGER.info("Planner gave no plan: {}", code);
                observe(o -> o.onPlannerAttempt(
                        new PlannerAttempt(subject, 1, "failed", code.name(), durationMs, 0L, facts)));
                String reason = fallbackReason(cause);
                observe(o ->
                        o.onPlannerAttempt(new PlannerAttempt(subject, 1, "fallback", reason, durationMs, 0L, facts)));
                noPlannerProgress(troubledPlan);
                plannerNote = "no plan was drafted (" + code + "); plan the next step yourself";
            } else if (proposeOnly && subject instanceof PlanSubject.OwnerOrder order) {
                deliverProposal(subject, order, proposal, durationMs);
            } else {
                ToolResult applied;
                try {
                    applied = plannerPort.apply(turnCaller, subject, proposal);
                } catch (RuntimeException e) {
                    applied = ToolResult.error("could not queue the plan");
                }
                long planId = applied.error() ? 0L : planIdIn(applied.content());
                if (applied.error()) {
                    noPlannerProgress(troubledPlan);
                    observe(o ->
                            o.onPlannerAttempt(new PlannerAttempt(subject, 1, "fallback", "rejected", durationMs, 0L)));
                } else {
                    // A repair or a replacement plan is progress on the incident it serves, so its budget starts
                    // again even though the queue still has work to do.
                    if (troubledPlan > 0) incidents.repaired(troubledPlan);
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

    /**
     * A proposal the owner asked to see: validated like a queued plan but not queued, kept for their approval and
     * shown to the model once. The turn carries only read-only tools, so neither the model nor a fallback reply can
     * start the work the owner asked it to hold.
     */
    private void deliverProposal(
            PlanSubject subject, PlanSubject.OwnerOrder order, PlanProposal proposal, long durationMs) {
        ToolResult valid;
        try {
            valid = plannerPort.validate(turnCaller, subject, proposal);
        } catch (RuntimeException e) {
            LOGGER.warn("Could not validate the proposed plan", e);
            valid = ToolResult.error("the plan could not be checked");
        }
        if (valid.error()) {
            observe(o -> o.onPlannerAttempt(new PlannerAttempt(subject, 1, "fallback", "rejected", durationMs, 0L)));
            plannerNote = "the plan you drafted did not pass validation (" + valid.content()
                    + "); tell the owner what is wrong with it and what you would change";
            return;
        }
        pendingPlans.put(turnCaller.uuid(), new PendingPlan(order, proposal));
        observe(o -> o.onPlannerAttempt(new PlannerAttempt(subject, 1, "delivered", "", durationMs, 0L)));
        plannerNote = "A plan was drafted but NOT queued, because the owner asked to see it first: "
                + proposal.stepsJson()
                + (proposal.rationale().isBlank() ? "" : "; rationale: " + proposal.rationale())
                + (proposal.expectedOutcome().isBlank() ? "" : "; expected: " + proposal.expectedOutcome())
                + ". Present it to the owner as a short numbered list of steps and say that nothing has been started."
                + " Do not queue, run or start anything, and do not change the goal; they will approve it or change it.";
    }

    /** A consultation that produced no plan is a spent remedy on the plan it was meant to repair. */
    private void noPlannerProgress(long planId) {
        if (planId > 0) {
            incidents.noProgress(planId);
        }
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
            endTurnAtStepCeiling();
            return;
        }
        step++;
        CompletableFuture<LlmResponse> future;
        try {
            List<ToolSpec> specs = tools.specs(turnCaller.access()).stream()
                    .filter(spec -> !spec.name().equals(MANAGE_SKILL) || teachingContext != null)
                    .toList();
            Set<ToolGroup> groups = promptGroups();
            // A spent incident is answered in words: the failure line already names the blocker and the decision
            // the owner has to make, and another task would only restart the chain.
            boolean wordsOnly = socialTurn || (recoveryTurn && incidents.paused());
            List<ToolSpec> sent = wordsOnly
                    ? List.of()
                    : proposalTools(catalog.filter(specs, groups, decision.mode() == TurnMode.CHAT));
            // ReplyGuard sanitizes internal names across the full registry, even when routing hid a schema.
            turnToolNames = wordsOnly
                    ? java.util.Set.of()
                    : specs.stream().map(ToolSpec::name).collect(Collectors.toUnmodifiableSet());
            dev.friendai.agent.llm.model.SystemPrompt system = buildSystemPrompt(plannerNote);
            plannerNote = "";
            LlmGateway active = gateway.get();
            // System, history and schemas share one bounded budget: a long tool-result history or a large catalog
            // must never overflow the provider and fail after a paid round trip.
            RequestBudget.Fit fitted = RequestBudget.fit(
                    system.text(),
                    memory.snapshot(),
                    sent,
                    active.contextTokens(),
                    active.maxOutputTokens(),
                    name -> catalog.meta(name).map(meta -> meta.core()).orElse(true));
            if (fitted instanceof RequestBudget.Refusal refusal) {
                LOGGER.warn(
                        "Request did not fit: {} tokens over a {} token budget", refusal.tokens(), refusal.budget());
                reportTurnEnd("error");
                speakSafely(
                        "request too large",
                        () -> speaker.error(UserMessage.of(
                                "friendai.agent.request_too_large", refusal.tokens(), refusal.budget())));
                endTurn();
                return;
            }
            RequestBudget.Fitted fit = (RequestBudget.Fitted) fitted;
            // Fitting never rewrites the prompt, only the history and schemas, so the split survives it.
            String prompt = fit.system();
            java.util.List<dev.friendai.agent.llm.model.Message> fittedHistory = fit.history();
            List<ToolSpec> fittedTools = fit.tools();
            // The set that actually reaches the provider is the whole vocabulary of the coming step: a call to a
            // schema the budget had to drop is as unavailable as one routing never sent.
            offeredTools = fittedTools.stream().map(ToolSpec::name).collect(Collectors.toUnmodifiableSet());
            observe(o -> o.onRequest(prompt, fittedHistory, fittedTools));
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
                        fittedHistory,
                        fittedTools,
                        delta -> {
                            accumulator.accept(delta);
                            scheduler.execute(() -> releaseEarly(gen, stream, accumulator));
                        },
                        telemetry);
                // The raw request, before this turn's own mapping: cancelling the mapped stage would not reach it.
                if (raw == null) throw missingProviderResponse();
                requestFuture = raw;
                future = raw.thenApply(response -> completeFromStream(accumulator, response));
            } else {
                streamInFlight = false;
                future = active.complete(system, fittedHistory, fittedTools, telemetry);
                if (future == null) throw missingProviderResponse();
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
                || hasPendingFailure()
                || decision.mode() != TurnMode.CHAT
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
                    observe(o -> o.onDirectLine("friendai.voice.stream_incomplete"));
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
            speaker.streamSentence(
                    speechStream, speechStream, earlySequence, text, last, turnAudience, turnCaller.uuid());
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
            speaker.streamSentence(
                    speechStream, speechStream, earlySequence, "", true, turnAudience, turnCaller.uuid());
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
            speakSafely("reply", () -> speaker.say(spoken, turnAudience, turnCaller.uuid()));
            return;
        }
        speakSafely("reply", () -> speaker.sayChat(spoken, turnAudience, turnCaller.uuid()));
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
            throw missingProviderResponse();
        }
        if (response.text().isBlank()
                && response.toolCalls().isEmpty()
                && (!accumulator.text().isBlank() || !accumulator.toolCalls().isEmpty())) {
            return accumulator.response(response.stopReason());
        }
        return response;
    }

    private static LlmException missingProviderResponse() {
        return new LlmException(LlmErrorKind.PARSE, "Provider returned no response");
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
        if (refreshAfterFailure(gen)) return;
        if (error != null || response == null) {
            Throwable failure = error != null ? error : missingProviderResponse();
            LOGGER.debug("LLM request failed: {}", failure.getClass().getSimpleName());
            observe(o -> o.onError(failure));
            reportTurnEnd("error");
            UserMessage message = ErrorMessages.forUser(failure);
            speakSafely("error", () -> speaker.error(message));
            observe(o -> o.onDirectLine(message.key()));
            endTurn();
            return;
        }
        String raw = response.text();
        if (!raw.isBlank()) {
            observe(o -> o.onAssistantText(raw));
        }
        String spoken = null;
        if (!raw.isBlank() && turnSpeechAllowed) {
            spoken = ReplyGuard.filter(raw, coordinatesAllowed(), recentSpeech(), turnToolNames)
                    .orElse(null);
        }
        if (suppressed != null) {
            String reason = suppressed;
            observe(o -> o.onReplySuppressed(reason));
        }
        // A words-only turn has no schema to call: a call the provider typed anyway would start the very work the
        // turn exists to stop, so it is not accepted and not remembered as an answered call.
        boolean wordsOnly = socialTurn || (recoveryTurn && incidents.paused());
        List<ToolCall> calls = wordsOnly ? List.of() : response.toolCalls();
        if (!raw.isBlank() || !calls.isEmpty()) {
            String remembered = spoken != null ? spoken : (raw.isBlank() ? raw : ReplyGuard.SILENT);
            memory.add(Message.assistant(remembered, calls));
        }
        if (calls.isEmpty()) {
            if (spoken != null) {
                deliverTurnReply(spoken, raw);
            }
            answerIdleGoal(spoken);
            answerPausedRecovery(spoken);
            endTurn();
            return;
        }
        boolean queuedByModel = false;
        boolean activityChosen = false;
        boolean anyCallFailed = false;
        boolean queueMutatedBesidesAdd = false;
        boolean onlyQueueAdd = true;
        for (ToolCall call : calls) {
            ToolResult result;
            observe(o -> o.onToolCall(call));
            if (!offeredTools.contains(call.name())) {
                // The request did not carry this schema, so the call was written from memory or from another turn.
                // Refusing it without running anything keeps the provider's id and order while nothing changes.
                result = notOffered(call.name());
                LOGGER.debug("Refused an unoffered {} call", call.name());
            } else {
                Optional<ToolResult> reused = repeatedCalls.blocked(call);
                if (reused.isPresent()) {
                    result = reused.get();
                    LOGGER.debug("Reused the recorded failure of an identical {} call", call.name());
                } else {
                    noteAttempt(call.name());
                    try {
                        if ("trade_item".equals(call.name())
                                && (decision.mode() == TurnMode.CHAT
                                        || !decision.groups().contains(ToolGroup.TRADING))) {
                            result = ToolResult.error("trading is not available for this request");
                        } else {
                            result = REQUEST_TOOLS.equals(call.name())
                                    ? requestTools(call)
                                    : tools.execute(call, turnCaller);
                        }
                    } catch (RuntimeException e) {
                        LOGGER.warn("Tool {} threw", call.name(), e);
                        result = ToolResult.error("Tool " + call.name() + " failed: " + e.getMessage());
                    }
                    repeatedCalls.record(call, result);
                }
            }
            ToolResult finalResult = result;
            observe(o -> o.onToolResult(call, finalResult));
            // The history copy is bounded: it is re-sent with every later step of the turn. Observers and the
            // repeated-call guard keep the whole result.
            memory.add(Message.toolResult(call.id(), call.name(), ToolResultText.of(result.content())));
            queuedByModel |= !result.error() && "queue_add".equals(call.name());
            activityChosen |= !result.error() && CHOOSE_ACTIVITY.equals(call.name());
            anyCallFailed |= result.error();
            onlyQueueAdd &= "queue_add".equals(call.name());
            // An edit or patch after the add changes the work the reply just described, so its result must be read.
            queueMutatedBesidesAdd |=
                    !result.error() && ("queue_edit".equals(call.name()) || "plan_patch".equals(call.name()));
        }
        // Every call was admitted, so the held text describes work that really started; a refusal is never narrated
        // by the response that produced it, because the next step has the real result and can answer truthfully.
        if (spoken != null && !anyCallFailed) {
            deliverTurnReply(spoken, raw);
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
        if (queuedByModel
                && !anyCallFailed
                && !queueMutatedBesidesAdd
                && spoken == null
                && onlyQueueAdd
                && turnKind == TurnKind.PLAYER
                && playerOnlyBatch
                && decision.mode() != TurnMode.CHAT) {
            // The plan is committed and carries its own work, so a second request would only restate the same fact.
            // The owner hears the acknowledgement now and the task events report the outcome.
            speakSafely(
                    "plan queued",
                    () -> speaker.say(UserMessage.of("friendai.agent.plan_queued"), turnAudience, turnCaller.uuid()));
            observe(o -> o.onDirectLine("friendai.agent.plan_queued"));
            turnReplyDelivered = true;
            endTurn();
            return;
        }
        if (queuedByModel && !anyCallFailed && !queueMutatedBesidesAdd && spoken == null && isRecoveryTurn()) {
            // A replacement plan is the useful outcome of an autonomous recovery; do not ask the model to narrate it.
            endTurn();
            return;
        }
        // Any refused call, or work that is only starting, means this text cannot be trusted yet: the owner hears the
        // next response, which has the real tool results in hand. A failure is never narrated from the response that
        // produced it.
        requestStep(gen);
    }

    /** Speaks a reply that the turn has grounded in admitted work or in a completed step. */
    private void deliverTurnReply(String spoken, String raw) {
        deliverSpeech(spoken, raw);
        rememberSpoken(spoken);
        String heard = spoken;
        observe(o -> o.onReply(turnCaller, heard));
        turnReplyDelivered = true;
    }

    private boolean isRecoveryTurn() {
        return turnKind == TurnKind.EVENT
                && (decision.mode() == TurnMode.RECOVERY
                        || turnEvent instanceof BrainEvent.Replan
                        || turnEvent instanceof BrainEvent.PlanBlocked
                        || turnEvent instanceof BrainEvent.TaskFinished task
                                && task.disposition().failure()
                        || turnEvent instanceof BrainEvent.PlanFinished plan
                                && plan.disposition().failure());
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
            case LIMIT_EXCEEDED -> "limit";
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
                && finished.disposition().failure()
                && !FailureHandler.isCancellation(finished.summary())) {
            return finished.planId();
        }
        return -1;
    }

    /**
     * The refusal for a call whose schema the request did not carry. It names the one way back when there is one: a
     * group that routing hid can be unlocked by name with {@code request_tools}, and the next request then carries
     * that schema. A teaching-only tool outside a taught run, or a schema the token budget had to drop, is simply
     * unavailable for this request.
     */
    private ToolResult notOffered(String tool) {
        if ("trade_item".equals(tool) && decision.mode() == TurnMode.CHAT) {
            return ToolResult.error("trading is not available for this request");
        }
        String refusal = "The tool " + tool + " was not offered in this request, so nothing was done.";
        if (proposalTurn) {
            return ToolResult.error(refusal + " This turn only presents a plan to the owner, so no work may start.");
        }
        Optional<dev.friendai.agent.tools.ToolMeta> meta = catalog.meta(tool);
        if (meta.isEmpty()) {
            return ToolResult.error(refusal);
        }
        if (MANAGE_SKILL.equals(tool)) {
            return ToolResult.error(refusal + " It is offered only while a skill is being taught.");
        }
        if (meta.get().core()
                || !agentSettings.get().toolRouting()
                || visibleGroups.contains(meta.get().group())) {
            return ToolResult.error(refusal);
        }
        String name = meta.get().group().name().toLowerCase(java.util.Locale.ROOT);
        return ToolResult.error(refusal + " Call request_tools with group \"" + name
                + "\" to load that group, then read the schema of " + tool + " and call it again.");
    }

    /** Unlocks a tool group for the rest of the turn; an unknown group is an error listing the valid ones. */
    private ToolResult requestTools(ToolCall call) {
        String wanted = call.arguments() != null
                        && call.arguments().has("group")
                        && call.arguments().get("group").isJsonPrimitive()
                ? call.arguments().get("group").getAsString()
                : "";
        if (proposalTurn) {
            return ToolResult.error(
                    "This turn only presents a plan to the owner, so no work may start. Answer with the plan and wait.");
        }
        // A call with no group is the discovery request: list the groups without guessing one, so a model that
        // needs a capability the route did not load can ask for its exact name.
        if (wanted.isBlank()) {
            return ToolResult.ok(
                    "Tool groups: " + groupList()
                            + ". Call request_tools with group \"<name>\" to load that group, read the schema, then call the tool.");
        }
        Optional<ToolGroup> group = ToolGroup.parse(wanted).filter(g -> g != ToolGroup.CORE);
        if (group.isEmpty()) {
            return ToolResult.error("Unknown tool group \"" + wanted + "\". Valid groups: " + groupList());
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

    /** The group names {@code request_tools} accepts, in one stable line. */
    private static String groupList() {
        return java.util.Arrays.stream(ToolGroup.values())
                .filter(g -> g != ToolGroup.CORE)
                .map(g -> g.name().toLowerCase(java.util.Locale.ROOT))
                .collect(Collectors.joining(", "));
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
        // The blocker names the owner's own goal, so it stays owner-only even when the turn was not private.
        speakSafely("blocked goal", () -> speaker.say(UserMessage.of("friendai.companion.goal_blocked", tick.goal())));
        observe(o -> o.onDirectLine("friendai.companion.goal_blocked"));
        turnReplyDelivered = true;
    }

    /**
     * One blocked job may already have spent several remedies. A call is worth naming back to the model when it is
     * work rather than a cheap query, so the pause line lists what was actually tried instead of every read.
     */
    private void noteAttempt(String tool) {
        if (PLANNING_TOOLS.contains(tool)) {
            incidents.attempted(tool);
            return;
        }
        var meta = catalog.meta(tool);
        if (meta.isEmpty() || meta.get().cost() != Cost.LOW) {
            incidents.attempted(tool);
        }
    }

    /**
     * A paused recovery turn exists to tell the owner which decision is needed. When the model answers silently the
     * owner would see nothing, so the mod says the typed blocker instead of leaving a companion that only failed.
     */
    private void answerPausedRecovery(String spoken) {
        if (spoken != null || !recoveryTurn || !incidents.paused()) {
            return;
        }
        String detail = incidents.detail();
        if (detail.isBlank()) {
            return;
        }
        // The recovered detail belongs to the owner's work; never hand it to another player's screen.
        speakSafely(
                "paused recovery", () -> speaker.say(UserMessage.of("friendai.companion.recovery_blocked", detail)));
        observe(o -> o.onDirectLine("friendai.companion.recovery_blocked"));
        turnReplyDelivered = true;
    }

    /**
     * The one terminal record a started turn gets: the first caller wins, so a delivered reply, a later error and a
     * cancellation cannot describe the same turn twice. The debug log derives the turn's duration from the start and
     * end pair, so a second record would report a wrong duration as well as a duplicate outcome.
     */
    private void reportTurnEnd(String outcome) {
        if (turnEndReported) {
            return;
        }
        turnEndReported = true;
        observe(o -> o.onTurnEnd(turnSeq, outcome));
    }

    /**
     * The turn wanted another model request but {@code maxToolSteps} is spent. The ceiling is the terminal reason,
     * and a turn that has not told the owner anything says one localized line instead of stopping mid-work in
     * silence; a turn that already delivered a reply keeps its silence.
     */
    private void endTurnAtStepCeiling() {
        reportTurnEnd("step_limit");
        if (!turnReplyDelivered && turnSpeechAllowed && turnCaller != null) {
            speakSafely(
                    "step limit",
                    () -> speaker.say(UserMessage.of("friendai.agent.step_limit"), turnAudience, turnCaller.uuid()));
            observe(o -> o.onDirectLine("friendai.agent.step_limit"));
        }
        endTurn();
    }

    private void endTurn() {
        reportTurnEnd(turnReplyDelivered ? "delivered" : "silent");
        teachingContext = null;
        thinking = false;
        if (clearConversationOnTurnEnd) {
            clearConversationOnTurnEnd = false;
            for (PrincipalHistory history : histories.values()) {
                history.memory.clear();
            }
            memory.clear();
        }
        streamInFlight = false;
        requestFuture = null;
        hasFinishedTurn = true;
        lastTurnEnd = scheduler.nowMillis();
        turnPlanIds.clear();
        turnGoalIds.clear();
        turnMemoryQuery = "";
        // Internal state is already settled: an external indicator that fails must not stop the next turn.
        speakSafely("thinking", () -> speaker.thinking(false));
        activePrincipal = null;
        turnReferent = Optional.empty();
        maybeStartTurn();
    }
}
