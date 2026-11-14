package dev.friendai.server.agent;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.friendai.FriendaiMod;
import dev.friendai.agent.autonomy.ShiftPolicy;
import dev.friendai.agent.autonomy.ShiftStopWhen;
import dev.friendai.agent.routine.Routine;
import dev.friendai.agent.routine.Routine.IntervalTrigger;
import dev.friendai.agent.routine.Routine.Outcome;
import dev.friendai.agent.routine.Routine.Phase;
import dev.friendai.agent.routine.Routine.PhaseTrigger;
import dev.friendai.agent.routine.Routine.Step;
import dev.friendai.agent.routine.Routine.Trigger;
import dev.friendai.agent.routine.RoutineRepository;
import dev.friendai.agent.routine.RoutineSchedule;
import dev.friendai.agent.routine.RoutineText;
import dev.friendai.agent.turn.AgentSettings;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.entity.CompanionManager;
import dev.friendai.execution.queue.PlanArchive;
import dev.friendai.execution.queue.PlanRecord;
import dev.friendai.execution.queue.PlanStatus;
import dev.friendai.execution.queue.QueuedStep;
import dev.friendai.execution.tool.Caller;
import dev.friendai.execution.tool.QueueParser;
import dev.friendai.execution.tool.ToolResult;
import dev.friendai.foundation.text.UserMessage;
import dev.friendai.infrastructure.config.ServerConfig;
import dev.friendai.server.execution.tools.QueueTools;
import dev.friendai.server.execution.tools.TaskToolDefinition;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.server.MinecraftServer;

/**
 * Runs owner-defined routines on the server thread, once per agent tick.
 *
 * <p>A routine is not a second scheduler: a due occurrence becomes one ordinary {@link QueueTools#addPlan} call with
 * the owner's caller, and the routine only watches that plan's terminal state. Owner work always wins; a non-safety
 * free-time shift is stopped at its safe boundary so the routine can run; a safety reflex is left alone. Every
 * decision is bounded and uses game ticks, never wall time.
 */
public final class RoutineDriver {
    private static final Pattern PLAN_ID = Pattern.compile("plan #(\\d+)");
    private static final int ROUTINE_PRIORITY = -5;

    private final MinecraftServer server;
    private final CompanionSessions brains;
    private final Supplier<ServerConfig> config;
    private final RoutineRepository routines;
    private final Map<String, TaskToolDefinition> planTools;
    /** One paused line per owner per disabled episode, so the owner is told once rather than every second. */
    private final Set<UUID> pausedSaid = new HashSet<>();

    RoutineDriver(
            MinecraftServer server,
            CompanionSessions brains,
            Supplier<ServerConfig> config,
            RoutineRepository routines,
            Map<String, TaskToolDefinition> planTools) {
        this.server = server;
        this.brains = brains;
        this.config = config;
        this.routines = routines;
        this.planTools = planTools;
    }

    /** Autonomy is off on this server ({@code autonomy.enabled} or a passive {@code agent.autonomy}). */
    public boolean disabled() {
        ServerConfig cfg = config.get();
        return !cfg.autonomy.enabled
                || AgentSettings.Autonomy.parse(cfg.agent.autonomy) == AgentSettings.Autonomy.PASSIVE;
    }

    public Optional<Routine> state(UUID owner) {
        return routines.state(owner);
    }

    /** The menu/tool line for the owner's routine, or empty when there is none. */
    public String status(UUID owner) {
        Routine routine = routines.state(owner).orElse(null);
        if (routine == null) {
            return "";
        }
        return RoutineText.describe(routine, gameTime());
    }

    /** The one-second driver pass, before free time so a routine's jobs are visible to the free-time policy. */
    public void tick() {
        long now = gameTime();
        long dayTime = dayTime();
        for (CompanionEntity companion : CompanionManager.get(server).all()) {
            UUID owner = companion.ownerUuid();
            if (owner == null) {
                continue;
            }
            try {
                evaluate(owner, companion, now, dayTime);
            } catch (RuntimeException failure) {
                FriendaiMod.LOGGER.warn("Routine failed for {}", owner, failure);
            }
        }
    }

    private void evaluate(UUID owner, CompanionEntity companion, long now, long dayTime) {
        Routine routine = routines.state(owner).orElse(null);
        if (routine == null) {
            pausedSaid.remove(owner);
            return;
        }
        if (routine.lastOutcome() == Outcome.RUNNING) {
            finishIfTerminal(owner, companion, routine);
            return;
        }
        routine = routines.state(owner).orElse(null);
        if (routine == null || !routine.enabled()) {
            return;
        }
        if (routine.exhausted()) {
            routines.save(
                    owner, routine.withEnabled(false).withOutcome(Outcome.EXHAUSTED, "finished the planned runs"));
            report(owner, companion, UserMessage.of("friendai.routine.exhausted", routine.name()));
            return;
        }
        if (disabled()) {
            if (pausedSaid.add(owner)) {
                report(owner, companion, UserMessage.of("friendai.routine.paused", routine.name()));
            }
            return;
        }
        pausedSaid.remove(owner);
        // The owner paused the companion: nothing new may be admitted, routine or not.
        if (companion.executionCoordinator().manualPause()) {
            return;
        }
        if (!due(routine, now, dayTime)) {
            return;
        }
        if (missed(routine, now, dayTime)) {
            routines.save(
                    owner, routine.skipped(next(routine, now, dayTime), "missed its window while the server was off"));
            return;
        }
        if (adoptOrFinishExisting(owner, companion, routine, now, false)) {
            return;
        }
        var freeTime = brains.freeTime();
        if (freeTime.state(owner).on()) {
            var shift = freeTime.state(owner).shift();
            if (shift != null && !ShiftPolicy.cancelsRunningShift(shift.need())) {
                // The routine outranks autonomous work, but never a safety reflex.
                freeTime.stop(owner, "a routine is due", ShiftStopWhen.WORK_CONFLICTS);
                return;
            }
            if (shift != null) {
                return;
            }
        }
        if (companion.executionCoordinator().readyWorkAhead()) {
            return;
        }
        dispatch(owner, companion, routine, now);
    }

    /**
     * Looks for the occurrence this routine already enqueued. A matching live plan is adopted; a matching archived
     * plan is recorded as finished; neither is re-enqueued. This is the crash recovery path, so a lost routine write
     * cannot duplicate completed work.
     */
    private boolean adoptOrFinishExisting(
            UUID owner, CompanionEntity companion, Routine routine, long now, boolean alreadyRunning) {
        for (PlanRecord plan : companion.executionCoordinator().queue().plans()) {
            if (!matchesOccurrence(plan.goal(), routine, alreadyRunning)) {
                continue;
            }
            PlanStatus status = companion.executionCoordinator().queue().planStatus(plan.id());
            if (status == PlanStatus.ACTIVE || status == PlanStatus.NEEDS_DECISION) {
                routines.save(
                        owner,
                        alreadyRunning
                                ? routine.withPlan(plan.id())
                                : routine.started(now, next(routine, now, dayTime()), plan.id()));
                return true;
            }
            finish(
                    owner,
                    companion,
                    routine.withNextDue(next(routine, now, dayTime())),
                    status == PlanStatus.DONE,
                    "plan #" + plan.id() + " " + status);
            return true;
        }
        for (PlanArchive archive : companion.executionCoordinator().queue().archives()) {
            if (!matchesOccurrence(archive.goal(), routine, alreadyRunning)) {
                continue;
            }
            finish(
                    owner,
                    companion,
                    routine.withNextDue(next(routine, now, dayTime())),
                    archive.success(),
                    archive.summary());
            return true;
        }
        return false;
    }

    /**
     * A plan belongs to this occurrence only: the current due tick when the record is settled, or a tick that the
     * running occurrence actually started from when the record already says RUNNING. A plan from an earlier
     * occurrence is never adopted or reported again.
     */
    private static boolean matchesOccurrence(String goal, Routine routine, boolean alreadyRunning) {
        if (goal == null) {
            return false;
        }
        if (routine.trigger() instanceof Routine.PhaseTrigger) {
            return goal.equals(routine.occurrenceKey());
        }
        String prefix = "routine:" + routine.name() + "#";
        if (!goal.startsWith(prefix)) {
            return false;
        }
        long tick;
        try {
            tick = Long.parseLong(goal.substring(prefix.length()));
        } catch (NumberFormatException invalid) {
            return false;
        }
        if (!alreadyRunning) {
            return tick == routine.nextDueTick();
        }
        return tick <= routine.lastStartedTick()
                && routine.lastStartedTick() - tick < RoutineSchedule.period(routine.trigger());
    }

    private boolean finishIfTerminal(UUID owner, CompanionEntity companion, Routine routine) {
        long planId = routine.lastPlanId();
        if (planId <= 0) {
            // The occurrence was saved before enqueueing; adopt the plan with the stable routine goal if it exists.
            if (adoptOrFinishExisting(owner, companion, routine, gameTime(), true)) {
                return true;
            }
            interrupt(owner, companion, routine, "the plan was not recorded");
            return true;
        }
        var queue = companion.executionCoordinator().queue();
        Optional<PlanRecord> plan = queue.plan(planId);
        if (plan.isEmpty()) {
            Optional<PlanArchive> archive =
                    queue.archives().stream().filter(a -> a.id() == planId).findFirst();
            if (archive.isPresent()) {
                finish(
                        owner,
                        companion,
                        routine,
                        archive.get().success(),
                        archive.get().summary());
            } else {
                interrupt(owner, companion, routine, "the plan is gone");
            }
            return true;
        }
        PlanStatus status = queue.planStatus(planId);
        if (status == PlanStatus.ACTIVE || status == PlanStatus.NEEDS_DECISION) {
            return false;
        }
        finish(owner, companion, routine, status == PlanStatus.DONE, "plan #" + planId + " " + status);
        return true;
    }

    /** Whether this routine's occurrence is due now; a phase follows the day clock, an interval the game clock. */
    private static boolean due(Routine routine, long now, long dayTime) {
        return switch (routine.trigger()) {
            case Routine.PhaseTrigger phase -> RoutineSchedule.phaseDue(phase, dayTime, routine.nextDueDay());
            case Routine.IntervalTrigger ignored -> RoutineSchedule.due(now, routine.nextDueTick());
        };
    }

    private static boolean missed(Routine routine, long now, long dayTime) {
        return switch (routine.trigger()) {
            case Routine.PhaseTrigger phase -> RoutineSchedule.phaseMissed(phase, dayTime, routine.nextDueDay());
            case Routine.IntervalTrigger ignored ->
                RoutineSchedule.missed(routine.trigger(), now, routine.nextDueTick());
        };
    }

    /** The next occurrence after this one; neither a slept-through window nor a fixed 24k game-tick step. */
    private static RoutineSchedule.Next next(Routine routine, long now, long dayTime) {
        return switch (routine.trigger()) {
            case Routine.PhaseTrigger phase -> RoutineSchedule.afterPhase(phase, now, dayTime, routine.nextDueDay());
            case Routine.IntervalTrigger ignored ->
                new RoutineSchedule.Next(
                        RoutineSchedule.advance(routine.trigger(), now, Math.max(1, routine.nextDueTick())), 0);
        };
    }

    private void finish(UUID owner, CompanionEntity companion, Routine routine, boolean success, String summary) {
        boolean capped = routine.exhausted();
        Outcome outcome = capped && success ? Outcome.EXHAUSTED : success ? Outcome.SUCCEEDED : Outcome.FAILED;
        Routine finished = routine.finished(
                        outcome, Routine.cut(summary, Routine.MAX_SUMMARY), next(routine, gameTime(), dayTime()))
                .withEnabled(!capped);
        if (success) {
            // A success resolves whatever the last failure was, so the next failure is news again.
            routines.save(owner, finished.withBlocker(""));
            if (capped) {
                report(owner, companion, UserMessage.of("friendai.routine.exhausted", routine.name()));
            } else if (routine.report()) {
                report(owner, companion, UserMessage.of("friendai.routine.done", routine.name()));
            }
            return;
        }
        boolean news = isNewBlocker(routine, summary);
        routines.save(owner, news ? finished.withBlocker(Routine.blockerKey(summary)) : finished);
        if (news) {
            report(owner, companion, failedMessage(routine, summary));
        }
    }

    /** One actionable line for a failure: the reason, or a plain line when nothing usable was reported. */
    private static UserMessage failedMessage(Routine routine, String summary) {
        String reason = Routine.cut(summary, Routine.MAX_SUMMARY);
        return reason.isBlank()
                ? UserMessage.of("friendai.routine.failed_unknown", routine.name())
                : UserMessage.of("friendai.routine.failed", routine.name(), reason);
    }

    /** Whether this failure's cause differs from the one the owner was last told about. */
    private static boolean isNewBlocker(Routine routine, String summary) {
        return !Routine.blockerKey(summary).equals(routine.lastBlocker());
    }

    /**
     * An occurrence that was recorded but whose plan cannot be found. It is a failure like any other, so the same
     * lost plan is not announced every time the driver looks.
     */
    private void interrupt(UUID owner, CompanionEntity companion, Routine routine, String reason) {
        Routine finished = routine.finished(
                Outcome.INTERRUPTED, Routine.cut(reason, Routine.MAX_SUMMARY), next(routine, gameTime(), dayTime()));
        boolean news = isNewBlocker(routine, reason);
        routines.save(owner, news ? finished.withBlocker(Routine.blockerKey(reason)) : finished);
        if (news) {
            report(owner, companion, UserMessage.of("friendai.routine.interrupted", routine.name()));
        }
    }

    private void dispatch(UUID owner, CompanionEntity companion, Routine routine, long now) {
        RoutineSchedule.Next next = next(routine, now, dayTime());
        Routine running = routine.started(now, next, 0);
        // Save the intent first: a crash after this line becomes INTERRUPTED, never a replay.
        routines.save(owner, running);
        ToolResult result = QueueTools.addPlan(
                brains,
                companion,
                FreeTimeDriver.ownerCaller(owner),
                planArgs(routine),
                planTools,
                false,
                FreeTimeDriver.DENIED);
        Matcher matcher = PLAN_ID.matcher(result.content());
        if (result.error() || !matcher.find()) {
            String reason = Routine.cut(result.content(), Routine.MAX_SUMMARY);
            Routine failed = running.finished(Outcome.FAILED, reason, next);
            boolean news = isNewBlocker(running, result.content());
            routines.save(owner, news ? failed.withBlocker(Routine.blockerKey(result.content())) : failed);
            if (news) {
                report(owner, companion, failedMessage(running, result.content()));
            }
            return;
        }
        routines.save(owner, running.withPlan(Long.parseLong(matcher.group(1))));
    }

    private static JsonObject planArgs(Routine routine) {
        JsonObject args = new JsonObject();
        args.addProperty("goal", routine.occurrenceKey());
        JsonArray steps = new JsonArray();
        for (Step step : routine.steps()) {
            JsonObject row = new JsonObject();
            row.addProperty("tool", step.tool());
            row.addProperty("args", step.args().toString());
            steps.add(row);
        }
        args.add("steps", steps);
        args.addProperty("priority", ROUTINE_PRIORITY);
        args.addProperty("on_failure", "stop_plan");
        args.addProperty("time_limit_min", 0);
        return args;
    }

    private void report(UUID owner, CompanionEntity companion, UserMessage message) {
        if (companion.ownerUuid() == null || !owner.equals(companion.ownerUuid())) {
            return;
        }
        brains.freeTime().tell(owner, companion, message);
    }

    /** Sets or replaces the owner's one routine from the normalized `set_routine` arguments. */
    public ToolResult set(CompanionEntity companion, Caller caller, JsonObject args) {
        UUID owner = companion.ownerUuid();
        if (owner == null) {
            return ToolResult.error("this companion has no owner");
        }
        Optional<Routine> existing = routines.state(owner);
        if (existing.isPresent() && existing.get().lastOutcome() == Outcome.RUNNING) {
            return ToolResult.error("a routine is running; cancel it first");
        }
        String name = string(args, "name");
        boolean hasPhase = args.has("phase") && !args.get("phase").isJsonNull();
        boolean hasInterval =
                args.has("interval_min") && !args.get("interval_min").isJsonNull();
        if (hasPhase == hasInterval) {
            return ToolResult.error("give exactly one of phase or interval_min");
        }
        Trigger trigger;
        try {
            trigger = hasPhase
                    ? new PhaseTrigger(
                            Phase.valueOf(string(args, "phase").strip().toUpperCase(java.util.Locale.ROOT)))
                    : new IntervalTrigger(args.get("interval_min").getAsInt());
        } catch (RuntimeException invalid) {
            return ToolResult.error("invalid phase or interval");
        }
        JsonArray input =
                args.has("steps") && args.get("steps").isJsonArray() ? args.getAsJsonArray("steps") : new JsonArray();
        Map<String, TaskToolDefinition> allowed = new LinkedHashMap<>();
        planTools.forEach((tool, definition) -> {
            if (!FreeTimeDriver.DENIED.contains(tool)
                    && (caller == null || caller.access().allows(definition.minAccess()))) {
                allowed.put(tool, definition);
            }
        });
        Map<String, JsonObject> schemas = new LinkedHashMap<>();
        Map<String, UnaryOperator<JsonObject>> normalizers = new LinkedHashMap<>();
        allowed.forEach((tool, definition) -> {
            schemas.put(tool, definition.schema());
            normalizers.put(tool, definition.normalizer());
        });
        var parsed = QueueParser.parseFlatActions(
                input,
                schemas,
                normalizers,
                allowed.keySet(),
                Routine.MAX_STEPS,
                Routine.MAX_ARGS_CHARS,
                Routine.MAX_STEPS_JSON_CHARS);
        if (!parsed.ok()) {
            return ToolResult.error("Invalid routine: " + String.join("; ", parsed.errors()));
        }
        List<Step> steps = new ArrayList<>();
        for (QueuedStep step : parsed.steps()) {
            steps.add(new Step(step.tool(), step.args()));
        }
        int maxRuns = args.has("max_runs") && !args.get("max_runs").isJsonNull()
                ? args.get("max_runs").getAsInt()
                : 0;
        boolean report = args.has("report")
                && !args.get("report").isJsonNull()
                && args.get("report").getAsBoolean();
        Routine routine;
        try {
            routine = Routine.create(name, trigger, steps, maxRuns, gameTime(), dayTime(), report);
            if (args.has("enabled") && !args.get("enabled").isJsonNull()) {
                routine = routine.withEnabled(args.get("enabled").getAsBoolean());
            }
        } catch (IllegalArgumentException invalid) {
            return ToolResult.error("Invalid routine: " + invalid.getMessage());
        }
        try {
            routines.save(owner, routine);
        } catch (dev.friendai.foundation.persistence.UnsupportedStoreVersionException unreadable) {
            return ToolResult.error("the routine file is from a newer version and stays read-only");
        }
        return ToolResult.ok("routine " + routine.name() + " set: " + RoutineText.describe(routine, gameTime()));
    }

    public ToolResult list(CompanionEntity companion) {
        UUID owner = companion.ownerUuid();
        if (owner == null) {
            return ToolResult.error("this companion has no owner");
        }
        Routine routine = routines.state(owner).orElse(null);
        if (routine == null) {
            return ToolResult.ok("no routine");
        }
        return ToolResult.ok(RoutineText.describe(routine, gameTime()));
    }

    public ToolResult cancel(CompanionEntity companion, Caller caller, JsonObject args) {
        UUID owner = companion.ownerUuid();
        if (owner == null) {
            return ToolResult.error("this companion has no owner");
        }
        Routine routine = routines.state(owner).orElse(null);
        if (routine == null) {
            return ToolResult.ok("no routine to cancel");
        }
        String name = string(args, "name");
        if (!name.isBlank() && !name.equalsIgnoreCase(routine.name())) {
            return ToolResult.error("the routine is named " + routine.name());
        }
        if (routine.lastPlanId() > 0) {
            companion.executionCoordinator().cancelPlan(routine.lastPlanId(), "routine cancelled");
        }
        try {
            routines.clear(owner);
        } catch (dev.friendai.foundation.persistence.UnsupportedStoreVersionException unreadable) {
            return ToolResult.error("the routine file is from a newer version and stays read-only");
        }
        pausedSaid.remove(owner);
        return ToolResult.ok("routine " + routine.name() + " cancelled");
    }

    private static String string(JsonObject root, String key) {
        return root.has(key) && !root.get(key).isJsonNull() ? root.get(key).getAsString() : "";
    }

    private long gameTime() {
        return server.overworld().getGameTime();
    }

    private long dayTime() {
        return server.overworld().getDefaultClockTime();
    }
}
