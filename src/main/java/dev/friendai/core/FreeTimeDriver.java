package dev.friendai.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.friendai.FriendaiMod;
import dev.friendai.agent.autonomy.AutonomyFacts;
import dev.friendai.agent.autonomy.AutonomySettings;
import dev.friendai.agent.autonomy.AwaySummary;
import dev.friendai.agent.autonomy.Executor;
import dev.friendai.agent.autonomy.FreeTime;
import dev.friendai.agent.autonomy.FreeTimeService;
import dev.friendai.agent.autonomy.FreeTimeStatus;
import dev.friendai.agent.autonomy.FreeTimeText;
import dev.friendai.agent.autonomy.IdleStartPolicy;
import dev.friendai.agent.autonomy.IdleWatchdog;
import dev.friendai.agent.autonomy.Need;
import dev.friendai.agent.autonomy.NeedKind;
import dev.friendai.agent.autonomy.NeedsAssessor;
import dev.friendai.agent.autonomy.ProgressionLadder;
import dev.friendai.agent.autonomy.ReflexPlans;
import dev.friendai.agent.autonomy.ReflexStep;
import dev.friendai.agent.autonomy.Shift;
import dev.friendai.agent.autonomy.ShiftBudget;
import dev.friendai.agent.autonomy.ShiftInputs;
import dev.friendai.agent.autonomy.ShiftOutcome;
import dev.friendai.agent.autonomy.ShiftPolicy;
import dev.friendai.brain.AgentSettings.Autonomy;
import dev.friendai.config.ServerConfig;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.entity.CompanionManager;
import dev.friendai.queue.JobQueue;
import dev.friendai.task.TaskSettings;
import dev.friendai.text.UserMessage;
import dev.friendai.tool.AccessLevel;
import dev.friendai.tool.Caller;
import dev.friendai.tool.ToolResult;
import dev.friendai.tool.builtin.FriendaiTools;
import dev.friendai.tool.builtin.TaskToolDef;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.server.MinecraftServer;

/**
 * Runs free time on the server thread: starts it after an idle spell, queues reflexes and fallbacks, keeps the
 * shift in step with its queue plan, and ends it on stop. Ticked once a second from {@link BrainService}.
 */
public final class FreeTimeDriver {
    /** Tools free time never uses, whatever the director or a template asks for. */
    public static final Set<String> DENIED = Set.of(
            "attack_player",
            "trust_player",
            "untrust_player",
            "forget",
            "set_name",
            "allow_food",
            "forbid_food",
            "reset_food_rule",
            "set_combat_settings",
            "set_combat_mode",
            "set_goal",
            "update_goal",
            "finish_goal",
            "start_free_time",
            "end_free_time",
            "choose_activity",
            "follow_player",
            "execute_command",
            "give_items",
            "drop_items");
    /** About one night: how long a shelter reflex stays home. */
    static final int SHELTER_MINUTES = 8;

    private static final Pattern PLAN_ID = Pattern.compile("plan #(\\d+)");
    /** Every free-time plan's goal starts with this, so a reused plan id is never mistaken for one. */
    public static final String PLAN_PREFIX = "free time: ";

    /** How long a director turn may take to start thinking before the request counts as unanswered. */
    static final long CHOICE_GRACE_MILLIS = 3_000;

    private final MinecraftServer server;
    private final BrainService brains;
    private final Supplier<ServerConfig> config;
    private final Map<String, TaskToolDef> taskTools;
    private final Map<String, TaskToolDef> planTools;
    private final Map<UUID, Long> awaitingSince = new HashMap<>();
    private final Set<UUID> planning = new java.util.HashSet<>();
    private final Map<UUID, String> stageAtStart = new HashMap<>();
    private final AutonomyFactsBuilder facts = new AutonomyFactsBuilder();
    private final Map<UUID, ShiftBudget> budgets = new HashMap<>();
    private final Map<UUID, Long> lastOwnerActivity = new HashMap<>();
    /** Free time with nothing running since this tick is a stall the watchdog answers. */
    private final Map<UUID, Long> quietSince = new HashMap<>();
    /** The last stall line told to each owner, so a quiet agenda does not repeat itself. */
    private final Map<UUID, Long> stallSaidAt = new HashMap<>();
    /** Whether a movement order ran on the previous tick, to notice when one ends. */
    private final Map<UUID, Boolean> wasMovementOrder = new HashMap<>();

    private final Map<UUID, Boolean> wasOnline = new HashMap<>();
    private final Set<UUID> seenOnline = new java.util.HashSet<>();
    /** The last decision per owner, for the status line; in memory only, it is not work state. */
    private final Map<UUID, String> lastDecision = new HashMap<>();
    /** Free-time plans cancelled on purpose; their end event is swallowed once. */
    private final Set<Long> released = new java.util.HashSet<>();

    FreeTimeDriver(
            MinecraftServer server,
            BrainService brains,
            Supplier<ServerConfig> config,
            Map<String, TaskToolDef> taskTools,
            Map<String, TaskToolDef> planTools) {
        this.server = server;
        this.brains = brains;
        this.config = config;
        this.taskTools = taskTools;
        this.planTools = planTools;
    }

    /** Free time is switched off on this server ({@code autonomy.enabled} or a passive {@code agent.autonomy}). */
    public boolean disabled() {
        ServerConfig cfg = config.get();
        return !cfg.autonomy.enabled || Autonomy.parse(cfg.agent.autonomy) == Autonomy.PASSIVE;
    }

    private FreeTimeService service() {
        return FriendaiServerState.get(server).freeTime();
    }

    public ShiftBudget budget(UUID owner) {
        return budgets.computeIfAbsent(owner, id -> new ShiftBudget());
    }

    /** The owner spoke or gave an order: new shifts wait for a quiet spell, and a spent backoff may try again. */
    public void ownerActivity(UUID owner, long nowMillis) {
        lastOwnerActivity.put(owner, nowMillis);
        budget(owner).ownerSpoke();
        if (service().state(owner).awaitingChoice()) {
            service().clearChoice(owner); // the owner takes over the conversation; their orders are not limited
        }
    }

    public FreeTime state(UUID owner) {
        return service().state(owner);
    }

    /** What free time is doing, why, what it will decide next and what the budget allows. */
    public FreeTimeStatus status(UUID owner) {
        FreeTime state = service().state(owner);
        if (!state.on()) {
            return FreeTimeStatus.off();
        }
        ServerConfig cfg = config.get();
        boolean online = wasOnline.getOrDefault(owner, true);
        int perHour = online ? cfg.autonomy.maxShiftsPerHour : cfg.autonomy.offlineShiftsPerHour;
        long now = now();
        return FreeTimeStatus.of(
                state,
                lastDecision.getOrDefault(owner, "waiting"),
                budget(owner).used(now),
                perHour,
                budget(owner).failStreak());
    }

    /**
     * Turns free time on. A command that names a wish becomes a shift and a plan at once: the owner heard "I'll do
     * that", so the work must start now instead of waiting for the director. A command start does not wait for the
     * quiet spell the order itself began either.
     */
    public FreeTime start(CompanionEntity companion, FreeTime.Origin origin, String wish) {
        UUID owner = companion.ownerUuid();
        long now = now();
        String hint = wish == null ? "" : wish.strip();
        if (origin == FreeTime.Origin.COMMAND) {
            lastOwnerActivity.put(owner, now - config.get().autonomy.resumeAfterSec * 1000L);
        }
        facts.invalidate(owner);
        brains.ensureBrain(owner); // its task listener reports how free-time plans end
        FreeTime state = service().start(owner, origin, hint, now);
        if (origin == FreeTime.Origin.COMMAND && !hint.isBlank() && state.shift() == null) {
            startWishShift(companion, hint, now);
        }
        return service().state(owner);
    }

    /** The owner's wish is the shift's goal: the planner drafts it directly, without a director turn. */
    private void startWishShift(CompanionEntity companion, String wish, long nowMillis) {
        UUID owner = companion.ownerUuid();
        AutonomySettings s = config.get().autonomy.toSettings();
        AutonomyFacts f = facts.build(companion, owner, nowMillis);
        NeedKind need =
                NeedsAssessor.kindNamed("", NeedsAssessor.assess(f, service().state(owner), s));
        markStart(owner, f);
        Shift shift = service()
                .beginShift(
                        owner,
                        wish,
                        List.of(),
                        "the owner asked for it",
                        need,
                        false,
                        nowMillis,
                        nowMillis + s.shiftMinutes() * 60_000L)
                .shift();
        requestPlan(owner, companion, shift.id(), s.shiftMinutes(), true);
    }

    /** Ends free time and cancels only free time's own plan. */
    public void stop(UUID owner, String reason) {
        FreeTime state = service().state(owner);
        if (!state.on()) {
            return;
        }
        Shift shift = state.shift();
        service().stop(owner, reason, now());
        CompanionManager.get(server).find(owner).ifPresent(companion -> {
            if (shift != null) {
                release(companion, shift.planId(), reason);
            }
        });
    }

    /** Cancels a free-time plan on purpose, so its end is not reported as news. */
    private void release(CompanionEntity companion, long planId, String reason) {
        if (planId == 0) {
            return;
        }
        companion.taskRunner().unmarkAutonomousPlan(planId);
        if (companion.taskRunner().queue().plan(planId).isPresent()) {
            released.add(planId);
            companion.taskRunner().cancelPlan(planId, reason);
        }
    }

    private boolean ownsPlan(UUID owner, long planId) {
        Shift shift = service().state(owner).shift();
        return planId != 0 && shift != null && shift.planId() == planId;
    }

    /**
     * A queue plan ended or got stuck. True when it was free time's: the shift is closed here and the model is not
     * told; false for any other plan.
     */
    public boolean onPlanEnded(UUID owner, CompanionEntity companion, long planId, boolean success, String summary) {
        if (released.remove(planId)) {
            return true;
        }
        if (!ownsPlan(owner, planId)) {
            return false;
        }
        if (companion.taskRunner().queue().plan(planId).isPresent()
                && companion.taskRunner().queue().planStatus(planId) != dev.friendai.queue.PlanStatus.ACTIVE) {
            // a stuck plan: free time does not wait for a decision, it gives up on the shift
            released.add(planId);
            companion.taskRunner().cancelPlan(planId, "free time gave up");
        }
        companion.taskRunner().unmarkAutonomousPlan(planId);
        long now = now();
        Shift shift = service().state(owner).shift();
        facts.invalidate(owner);
        String stageAfter =
                ProgressionLadder.evaluate(facts.build(companion, owner, now)).stage();
        String diagnosis = success
                ? ""
                : new dev.friendai.agent.recover.RecoveryAdvisor()
                        .diagnose("", summary == null ? "" : summary, Set.of())
                        .line();
        // A reflex has no recorded start stage, so it never reports a new stage on its own.
        ShiftOutcome.Result outcome = ShiftOutcome.of(
                shift,
                success,
                summary,
                stageAtStart.getOrDefault(owner, stageAfter),
                stageAfter,
                diagnosis,
                now,
                config.get().autonomy.toSettings());
        service()
                .finishShift(owner, outcome.record().success(), outcome.record().summary(), now);
        outcome.lesson().ifPresent(lesson -> service().addLesson(owner, lesson));
        brains.skillOutcome(
                owner,
                "shift:" + planId,
                success
                        ? dev.friendai.skill.TeachingContext.OutcomeKind.SHIFT_SUCCESS
                        : dev.friendai.skill.TeachingContext.OutcomeKind.SHIFT_FAILURE,
                summary);
        outcome.cooldownUntil().ifPresent(until -> service().cooldown(owner, shift.need(), until));
        if (success) {
            // Any finished shift, template included, shows the companion is working: retry the director later.
            budget(owner).succeeded();
        } else if (summary != null && summary.contains(ShiftOutcome.TIME_UP)) {
            // The plan ran; hitting its limit is not an unanswered director either.
            budget(owner).succeeded();
        } else if (!shift.reflex() && outcome.cooldownUntil().isPresent()) {
            budget(owner).failed();
        }
        outcome.notable()
                .ifPresent(stage -> tell(owner, companion, UserMessage.of("friendai.freetime.stage_reached", stage)));
        if (!success && summary != null && summary.contains("storage is full")) {
            tell(owner, companion, UserMessage.of("friendai.freetime.storage_full"));
        }
        return true;
    }

    /** Remembers the ladder stage a shift starts from, so its end can tell whether it moved up. */
    private void markStart(UUID owner, AutonomyFacts f) {
        stageAtStart.put(owner, ProgressionLadder.evaluate(f).stage());
    }

    /** One owner's free time, once a second. */
    void tick(UUID owner, CompanionEntity companion, boolean brainThinking, boolean ownerGoalActive, long nowMillis) {
        ServerConfig cfg = config.get();
        AutonomySettings s = cfg.autonomy.toSettings();
        Autonomy ceiling = Autonomy.parse(cfg.agent.autonomy);
        FreeTimeService service = service();
        FreeTime state = service.state(owner);
        var runner = companion.taskRunner();
        boolean online = companion.owner().isPresent();
        boolean movementOrder = runner.movementOrderActive();
        if (Boolean.TRUE.equals(wasMovementOrder.put(owner, movementOrder)) && !movementOrder) {
            // The order the owner gave already served the quiet spell: work resumes at once, not a minute later.
            lastOwnerActivity.put(owner, nowMillis - s.resumeAfterSec() * 1000L);
        }
        long lastOwner = lastOwnerActivity.computeIfAbsent(owner, id -> nowMillis);
        boolean combat = runner.paused()
                || runner.suspended() && online
                || companion.isSleeping()
                || companion.getTarget() != null;
        Boolean before = wasOnline.put(owner, online);
        if (Boolean.FALSE.equals(before) && online) {
            onOwnerBack(owner, companion);
        }
        if (!state.on()) {
            // An idle start means the owner left the companion without work: they must have been here this run.
            if (wasOnlineThisRun(owner, online)
                    && IdleStartPolicy.shouldStart(
                            state,
                            s,
                            ceiling,
                            ownerGoalActive,
                            !runner.hasWork(),
                            combat,
                            online,
                            nowMillis,
                            lastOwner,
                            service.lastEndMillis(owner))) {
                service.start(owner, FreeTime.Origin.IDLE, "", nowMillis);
                tell(owner, companion, UserMessage.of("friendai.freetime.idle_start"));
            }
            return;
        }
        long savedPlan = state.shift() == null ? 0 : state.shift().planId();
        // A queue that restarted (death, re-summon, rollback) may reuse the saved id for an owner plan: only a plan
        // free time queued itself, recognisable by its goal, still belongs to the shift.
        long shiftPlan = savedPlan != 0
                        && runner.queue()
                                .plan(savedPlan)
                                .map(plan -> plan.goal().startsWith(PLAN_PREFIX))
                                .orElse(false)
                ? savedPlan
                : 0;
        boolean ownerWork = runner.busyWithOther()
                || runner.queue().jobs().stream()
                        .anyMatch(job -> !JobQueue.terminal(job) && (shiftPlan == 0 || job.planId() != shiftPlan));
        if (!online && s.whileOffline() && runner.suspended() && !ownerWork) {
            runner.resume(); // free time may work while the owner is away; owner work stays suspended
        }
        if (shiftPlan != 0) {
            runner.markAutonomousPlan(shiftPlan); // after a restart the runner forgot its marks
        }
        AutonomyFacts f = facts.build(companion, owner, nowMillis);
        if (state.awaitingChoice()
                && !brainThinking
                && nowMillis - awaitingSince.getOrDefault(owner, 0L) > CHOICE_GRACE_MILLIS) {
            // the director turn ended without choose_activity ([silent], an error, or a restart):
            // do not leave the hour empty, start the first deterministic template the agenda offers
            state = service.clearChoice(owner);
            budget(owner).failed();
            if (queueFirstTemplate(owner, companion, f, s, nowMillis)) {
                state = service.state(owner);
            }
        }
        boolean planExists = shiftPlan != 0;
        ShiftInputs in = new ShiftInputs(
                state,
                f,
                s,
                ceiling,
                ownerGoalActive,
                planExists,
                planningInFlight(owner),
                ownerWork,
                brainThinking,
                lastOwner,
                movementOrder);
        ShiftPolicy.Decision decision = ShiftPolicy.decide(in, budget(owner));
        lastDecision.put(owner, FreeTimeText.decisionLabel(decision));
        switch (decision) {
            case ShiftPolicy.CloseStale ignored -> service().finishShift(owner, false, "plan is gone", nowMillis);
            case ShiftPolicy.Expired ignored -> {
                Shift shift = state.shift();
                if (shift != null) {
                    release(companion, shift.planId(), "ran out of time");
                    service().finishShift(owner, false, ShiftOutcome.TIME_UP, nowMillis);
                    budget(owner).succeeded(); // the plan ran; a timeout is not an unanswered director
                }
            }
            case ShiftPolicy.Reflex reflex -> {
                if (state.shift() != null) {
                    interrupt(owner, companion, state.shift(), reflex.need(), nowMillis);
                }
                queueTemplate(owner, companion, reflex.need(), ReflexPlans.plan(reflex.need(), f, s), s, nowMillis);
            }
            case ShiftPolicy.Fallback fallback ->
                queueTemplate(
                        owner, companion, fallback.need(), ReflexPlans.fallback(fallback.need(), f), s, nowMillis);
            case ShiftPolicy.Director ignored -> askDirector(owner, companion, f, nowMillis);
            case ShiftPolicy.None ignored -> {}
            case ShiftPolicy.WaitBudget ignored -> {}
        }
        watchdog(owner, companion, f, s, brainThinking, nowMillis);
    }

    /**
     * Free time on with nothing running is a bug state, not a rest: after the window the director is asked anyway
     * (the failure backoff is skipped, the hourly cap is not), a fallback template is queued, or the owner is told
     * once that nothing could start.
     */
    private void watchdog(
            UUID owner,
            CompanionEntity companion,
            AutonomyFacts f,
            AutonomySettings s,
            boolean brainThinking,
            long nowMillis) {
        var runner = companion.taskRunner();
        FreeTime state = service().state(owner);
        boolean movementOrder = runner.movementOrderActive();
        IdleWatchdog.Busy busy = new IdleWatchdog.Busy(
                state.shift() != null,
                runner.hasWork() && !movementOrder,
                state.awaitingChoice(),
                brainThinking,
                planningInFlight(owner),
                f.inCombat(),
                movementOrder);
        boolean mayDecide = !runner.paused() && !runner.suspended() && (f.ownerOnline() || s.whileOffline());
        if (busy.anything() || !mayDecide) {
            quietSince.remove(owner);
            return;
        }
        long since = quietSince.computeIfAbsent(owner, id -> nowMillis);
        // Free time switched off on this server is not a stall: nothing may start anyway.
        if (!IdleWatchdog.due(state.on() && s.enabled(), busy, since, nowMillis, TaskSettings.freeTimeWatchdogSec)) {
            return;
        }
        quietSince.put(owner, nowMillis);
        int perHour = f.ownerOnline() ? s.maxShiftsPerHour() : s.offlineShiftsPerHour();
        if (budget(owner).allows(nowMillis, perHour)) {
            askDirector(owner, companion, f, nowMillis);
            return;
        }
        if (queueFirstTemplate(owner, companion, f, s, nowMillis)) {
            return;
        }
        if (nowMillis - stallSaidAt.getOrDefault(owner, 0L) >= TaskSettings.freeTimeWatchdogSec * 1000L) {
            stallSaidAt.put(owner, nowMillis);
            tell(owner, companion, UserMessage.of("friendai.freetime.stalled"));
        }
    }

    private boolean wasOnlineThisRun(UUID owner, boolean online) {
        if (online) {
            seenOnline.add(owner);
        }
        return seenOnline.contains(owner);
    }

    /** What a director turn may call: the choice itself and read-only looks around. */
    static final Set<String> CHOICE_TOOLS = Set.of(
            "choose_activity",
            "end_free_time",
            "look_around",
            "get_inventory",
            "show_inventory",
            "find_place",
            "recall",
            "find_trees",
            "find_blocks",
            "describe_block",
            "survey_area",
            "queue_show",
            "known_resources",
            "plan_obtain",
            "get_recipe",
            "request_tools");

    /**
     * Why a movement order is refused while free time works, or empty when it may run. Proximity is never needed to
     * speak: walking to the owner cancels the running task and drops its queue, so the companion says its line from
     * where it stands.
     */
    public Optional<String> refusalDuringShift(UUID owner, String tool) {
        FreeTime state = service().state(owner);
        if (!state.on() || state.shift() == null || !"approach".equals(tool)) {
            return Optional.empty();
        }
        return Optional.of("free time is running: stay where you are and let the work continue. You do not need "
                + "to walk to the owner to talk; say your line from here.");
    }

    /** Why a tool call in a director turn is refused, or empty when it may run. Free time works only through plans,
     * where the deny list and the place guard apply; a director turn may not act directly.
     */
    public Optional<String> refusalDuringChoice(UUID owner, String tool) {
        if (!service().state(owner).awaitingChoice() || CHOICE_TOOLS.contains(tool)) {
            return Optional.empty();
        }
        return Optional.of(
                "in free time, choose the activity with choose_activity; " + tool + " runs only as part of its plan");
    }

    /** A planner request for this owner's shift has not answered yet. */
    boolean planningInFlight(UUID owner) {
        return planning.contains(owner);
    }

    /** Asks the director, through one brain event, to choose the next activity. */
    void askDirector(UUID owner, CompanionEntity companion, AutonomyFacts f, long nowMillis) {
        FreeTime state = service().state(owner);
        AutonomySettings s = config.get().autonomy.toSettings();
        List<String> agenda = NeedsAssessor.assess(f, state, s).stream()
                .map(need -> "- " + need.kind().name() + ": " + need.text() + " (urgency " + need.urgency() + ")")
                .toList();
        service().awaitChoice(owner);
        awaitingSince.put(owner, nowMillis);
        budget(owner).record(nowMillis);
        brains.post(
                owner,
                new dev.friendai.brain.BrainEvent.FreeTimeShift(
                        agenda,
                        ProgressionLadder.evaluate(f).line(),
                        FreeTimeText.recent(state),
                        state.lessons(),
                        state.wish()));
    }

    /**
     * The director's {@code choose_activity}: begins the shift and asks the planner for its plan.
     *
     * @param needName the agenda need the activity serves; empty or unknown falls back to the most urgent one
     */
    public ToolResult choose(
            CompanionEntity companion, String goal, List<String> criteria, String why, int minutes, String needName) {
        UUID owner = companion.ownerUuid();
        FreeTime state = service().state(owner);
        if (!state.on() || !state.awaitingChoice()) {
            return ToolResult.error("no free-time shift is waiting for a choice");
        }
        long now = now();
        AutonomySettings s = config.get().autonomy.toSettings();
        int length = Math.max(3, Math.min(minutes, s.shiftMinutes()));
        AutonomyFacts f = facts.build(companion, owner, now);
        NeedKind need = NeedsAssessor.kindNamed(needName, NeedsAssessor.assess(f, state, s));
        markStart(owner, f);
        Shift shift = service()
                .beginShift(owner, goal, criteria, why, need, false, now, now + length * 60_000L)
                .shift();
        awaitingSince.remove(owner);
        if (!why.isBlank()) {
            say(owner, companion, why);
        }
        requestPlan(owner, companion, shift.id(), length, false);
        return ToolResult.ok("activity chosen; planning a plan for \"" + goal + "\"");
    }

    private void requestPlan(UUID owner, CompanionEntity companion, long shiftId, int minutes, boolean speakFailure) {
        Caller caller = ownerCaller(owner);
        dev.friendai.agent.plan.PlannerInput input = plannerInput(owner, companion, caller);
        planning.add(owner);
        brains.propose(owner, input, true)
                .whenComplete((proposal, error) -> brains.onServerThread(() -> {
                    planning.remove(owner);
                    FreeTime state = service().state(owner);
                    if (!state.on() || state.shift() == null || state.shift().id() != shiftId) {
                        return; // stopped or replaced while the planner was thinking
                    }
                    Optional<Long> plan = error != null || proposal == null
                            ? Optional.empty()
                            : queue(owner, companion, state.shift().goal(), proposal.stepsJson(), minutes, planTools);
                    if (plan.isEmpty()) {
                        long now = now();
                        String reason = error == null || error.getMessage() == null
                                ? "the plan was refused"
                                : error.getMessage();
                        // The need did not fail, the planner could not draft work for it: try the deterministic
                        // template
                        // for the same need before giving the hour up. The director still backs off.
                        AutonomyFacts f = facts.build(companion, owner, now);
                        Optional<List<ReflexStep>> template =
                                ReflexPlans.fallback(state.shift().need(), f);
                        if (template.isPresent()) {
                            Optional<Long> fallback =
                                    queue(owner, companion, state.shift().goal(), template.get(), minutes, taskTools);
                            if (fallback.isPresent()) {
                                service().linkPlan(owner, fallback.get());
                                facts.invalidate(owner);
                                budget(owner).failed();
                                return;
                            }
                        }
                        // The need did not fail, the planner could not draft work for it: only the director backs off.
                        service().finishShift(owner, false, "no plan: " + reason, now);
                        budget(owner).failed();
                        if (speakFailure) {
                            // The owner asked for this one: the sentence "I'll do that" must not be left hanging.
                            tell(owner, companion, UserMessage.of("friendai.freetime.no_plan", reason));
                        }
                        return;
                    }
                    service().linkPlan(owner, plan.get());
                    facts.invalidate(owner);
                }));
    }

    private dev.friendai.agent.plan.PlannerInput plannerInput(UUID owner, CompanionEntity companion, Caller caller) {
        FreeTime state = service().state(owner);
        Shift shift = state.shift();
        var goal = new dev.friendai.agent.goal.Goal(
                shift.id(),
                shift.goal(),
                shift.criteria().stream()
                        .map(text -> new dev.friendai.agent.goal.Criterion(text, Optional.empty()))
                        .toList(),
                dev.friendai.agent.goal.GoalStatus.ACTIVE,
                0,
                0,
                state.lessons(),
                0,
                false);
        Map<String, JsonObject> schemas = new java.util.LinkedHashMap<>();
        StringBuilder digest = new StringBuilder();
        planTools.forEach((name, def) -> {
            if (!DENIED.contains(name)) {
                schemas.put(name, def.schema());
                digest.append("- ").append(BrainService.describeTool(def)).append('\n');
            }
        });
        ServerConfig cfg = config.get();
        var guard = new dev.friendai.task.queue.CompanionGuardFacts(companion);
        guard.refresh();
        return new dev.friendai.agent.plan.PlannerInput(
                goal,
                new CompanionPerception(companion).describe(caller),
                digest.toString(),
                dev.friendai.place.PlaceText.planner(
                        cfg.places.enabled
                                ? FriendaiServerState.get(server)
                                        .places()
                                        .book(owner)
                                        .all()
                                : List.of(),
                        cfg.places.plannerLimit),
                Optional.of(
                        dev.friendai.queue.QueueText.show(companion.taskRunner().queue(), guard, null, 20, false)),
                state.lessons(),
                "",
                schemas,
                planTools.keySet());
    }

    private void interrupt(UUID owner, CompanionEntity companion, Shift shift, Need need, long nowMillis) {
        service()
                .finishShift(
                        owner,
                        false,
                        "interrupted by " + need.kind().name().toLowerCase(java.util.Locale.ROOT),
                        nowMillis);
        release(companion, shift.planId(), "interrupted by free time");
    }

    private boolean queueTemplate(
            UUID owner,
            CompanionEntity companion,
            Need need,
            Optional<List<ReflexStep>> steps,
            AutonomySettings s,
            long nowMillis) {
        if (steps.isEmpty()) {
            service().cooldown(owner, need.kind(), nowMillis + s.failCooldownMin() * 60_000L);
            return false;
        }
        boolean shelter = need.kind() == NeedKind.SHELTER || need.kind() == NeedKind.SURVIVE;
        int minutes = shelter ? SHELTER_MINUTES : s.shiftMinutes();
        Optional<Long> plan = queue(owner, companion, "free time: " + need.text(), steps.get(), minutes, taskTools);
        if (plan.isEmpty()) {
            service().cooldown(owner, need.kind(), nowMillis + s.failCooldownMin() * 60_000L);
            return false;
        }
        stageAtStart.remove(owner);
        service()
                .beginShift(
                        owner, need.text(), List.of(), "", need.kind(), true, nowMillis, nowMillis + minutes * 60_000L);
        service().linkPlan(owner, plan.get());
        facts.invalidate(owner);
        return true;
    }

    /**
     * Starts the first agenda entry that has a deterministic template, reflex first. Used when the director is
     * silent, the planner failed, the budget is spent or the watchdog fired: the hour must keep moving.
     */
    private boolean queueFirstTemplate(
            UUID owner, CompanionEntity companion, AutonomyFacts f, AutonomySettings s, long nowMillis) {
        FreeTime state = service().state(owner);
        List<Need> needs = NeedsAssessor.assess(f, state, s);
        for (Need need : needs) {
            if (need.executor() != Executor.REFLEX) {
                continue;
            }
            Optional<List<ReflexStep>> steps = ReflexPlans.plan(need, f, s);
            if (steps.isPresent() && queueTemplate(owner, companion, need, steps, s, nowMillis)) {
                return true;
            }
        }
        for (NeedKind kind : ShiftPolicy.FALLBACK_ORDER) {
            for (Need need : needs) {
                if (need.kind() != kind) {
                    continue;
                }
                Optional<List<ReflexStep>> steps = ReflexPlans.fallback(need, f);
                if (steps.isPresent() && queueTemplate(owner, companion, need, steps, s, nowMillis)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Queues a free-time plan with the owner's authority; empty when the plan was refused. */
    Optional<Long> queue(
            UUID owner,
            CompanionEntity companion,
            String goal,
            List<ReflexStep> steps,
            int minutes,
            Map<String, TaskToolDef> tools) {
        JsonArray array = new JsonArray();
        for (ReflexStep step : steps) {
            JsonObject o = new JsonObject();
            o.addProperty("tool", step.tool());
            o.addProperty("args", step.args().toString());
            array.add(o);
        }
        return queue(owner, companion, goal, array, minutes, tools);
    }

    /** As above, with the full normalized plan tree in the {@code queue_add} wire shape. */
    Optional<Long> queue(
            UUID owner,
            CompanionEntity companion,
            String goal,
            JsonArray steps,
            int minutes,
            Map<String, TaskToolDef> tools) {
        JsonObject args = new JsonObject();
        String marked = goal.startsWith(PLAN_PREFIX) ? goal : PLAN_PREFIX + goal;
        args.addProperty("goal", marked.length() > 200 ? marked.substring(0, 200) : marked);
        args.add("steps", steps);
        args.addProperty("priority", -10);
        args.addProperty("on_failure", "stop_plan");
        args.addProperty("time_limit_min", minutes);
        ToolResult result = FriendaiTools.addPlan(companion, ownerCaller(owner), args, tools, false, DENIED);
        Matcher matcher = PLAN_ID.matcher(result.content());
        if (result.error() || !matcher.find()) {
            FriendaiMod.LOGGER.debug("Free-time plan refused: {}", result.content());
            return Optional.empty();
        }
        long planId = Long.parseLong(matcher.group(1));
        companion.taskRunner().markAutonomousPlan(planId);
        return Optional.of(planId);
    }

    static Caller ownerCaller(UUID owner) {
        return new Caller(owner, "", AccessLevel.OWNER);
    }

    /** The owner joined or came back online: say what happened while they were away. */
    void onOwnerBack(UUID owner, CompanionEntity companion) {
        AwaySummary.Taken taken = AwaySummary.take(service().state(owner));
        if (taken.text().isBlank()) {
            return;
        }
        service().update(owner, s -> taken.state());
        tell(owner, companion, UserMessage.of("friendai.freetime.away", taken.text()));
    }

    /** The director's own words to the owner, or saved for when they come back. */
    void say(UUID owner, CompanionEntity companion, String text) {
        var player = companion.owner();
        if (player.isPresent()) {
            player.get()
                    .sendSystemMessage(dev.friendai.chat.CompanionSpeaker.chatLine(
                            companion.getName().getString(), text));
        } else {
            service().update(owner, s -> AwaySummary.add(s, text));
        }
    }

    /** A fixed line to the owner when they can hear it, otherwise saved for when they come back. */
    void tell(UUID owner, CompanionEntity companion, UserMessage message) {
        var player = companion.owner();
        if (player.isPresent()) {
            player.get()
                    .sendSystemMessage(net.minecraft.network.chat.Component.literal("<")
                            .append(dev.friendai.chat.CompanionNames.styled(
                                    companion.getName().getString()))
                            .append(net.minecraft.network.chat.Component.literal("> "))
                            .append(Components.of(message)));
        } else {
            service()
                    .update(
                            owner,
                            s -> AwaySummary.add(s, Components.of(message).getString()));
        }
    }

    /** Wall-clock time: cooldowns and the idle spell are saved and must mean the same after a restart. */
    static long now() {
        return System.currentTimeMillis();
    }
}
