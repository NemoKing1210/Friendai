package dev.friendai.server.agent.planner;

import com.google.gson.JsonObject;
import dev.friendai.agent.autonomy.FreeTime;
import dev.friendai.agent.autonomy.FreeTimeService;
import dev.friendai.agent.autonomy.Shift;
import dev.friendai.agent.autonomy.ShiftStopWhen;
import dev.friendai.agent.goal.Goal;
import dev.friendai.agent.goal.GoalBoard;
import dev.friendai.agent.goal.GoalStatus;
import dev.friendai.agent.plan.PlanSubject;
import dev.friendai.agent.plan.PlannerInput;
import dev.friendai.agent.plan.PlannerObjective;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.execution.queue.JobQueue;
import dev.friendai.execution.queue.PlanFingerprint;
import dev.friendai.execution.queue.PlanRecord;
import dev.friendai.execution.queue.QueueText;
import dev.friendai.execution.tool.AccessLevel;
import dev.friendai.execution.tool.Caller;
import dev.friendai.features.places.Place;
import dev.friendai.features.places.PlaceText;
import dev.friendai.infrastructure.config.ServerConfig;
import dev.friendai.server.agent.CompanionPerception;
import dev.friendai.server.agent.FreeTimeDriver;
import dev.friendai.server.agent.perception.PerceptionContext;
import dev.friendai.server.execution.tools.TaskToolDefinition;
import dev.friendai.task.queue.CompanionGuardFacts;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Builds every planner input from one place. The brain, recovery and free time differ only in the objective and the
 * tool scope their {@link PlanSubject} implies; the shared fields — world, tools, knowledge, queue, lessons and the
 * target plan — are filled once here, so a field cannot drift between callers.
 *
 * <p>One instance is bound to one companion's live session and must be built and asked on the server thread.
 */
public final class PlannerInputFactory {
    private final CompanionEntity companion;
    private final Supplier<PerceptionContext> perception;
    private final Map<String, TaskToolDefinition> taskTools;
    private final Map<String, TaskToolDefinition> planTools;
    private final Supplier<List<Place>> places;
    private final Supplier<GoalBoard> goals;
    private final Supplier<ServerConfig> config;
    private final Supplier<List<String>> verifiedFacts;
    private final Supplier<FreeTimeService> freeTime;

    public PlannerInputFactory(
            CompanionEntity companion,
            Supplier<PerceptionContext> perception,
            Map<String, TaskToolDefinition> taskTools,
            Map<String, TaskToolDefinition> planTools,
            Supplier<List<Place>> places,
            Supplier<GoalBoard> goals,
            Supplier<ServerConfig> config,
            Supplier<List<String>> verifiedFacts,
            Supplier<FreeTimeService> freeTime) {
        this.companion = companion;
        this.perception = perception;
        this.taskTools = taskTools;
        this.planTools = planTools;
        this.places = places;
        this.goals = goals;
        this.config = config;
        this.verifiedFacts = verifiedFacts;
        this.freeTime = freeTime;
    }

    /** Tools a subject must never plan with; only a free-time shift has any. */
    public static Set<String> denied(PlanSubject subject) {
        return subject instanceof PlanSubject.FreeTimeShift ? FreeTimeDriver.DENIED : Set.of();
    }

    /** The input for one subject, or empty when its prerequisite is gone. */
    public Optional<PlannerInput> build(PlanSubject subject, Caller caller, String failure) {
        if (subject == null || caller == null) return Optional.empty();
        String reason = failure == null ? "" : failure;
        if (subject instanceof PlanSubject.OwnerOrder order) return order(order, caller, reason);
        if (subject instanceof PlanSubject.ModelGoal goal) {
            return goalObjective(subject, goal.goalId(), List.of(), false, caller, reason);
        }
        if (subject instanceof PlanSubject.ActiveGoal goal) {
            return goalObjective(subject, goal.goalId(), goal.unmet(), true, caller, reason);
        }
        if (subject instanceof PlanSubject.TroubledPlan trouble) return plan(trouble, caller, reason);
        if (subject instanceof PlanSubject.FreeTimeShift shift) return shift(shift, caller, reason);
        return Optional.empty();
    }

    private Optional<PlannerInput> order(PlanSubject.OwnerOrder subject, Caller caller, String failure) {
        if (subject.text().isBlank()) return Optional.empty();
        return Optional.of(base(subject, PlannerObjective.forOrder(subject.text()), caller, failure, 0L, ""));
    }

    private Optional<PlannerInput> goalObjective(
            PlanSubject subject,
            long goalId,
            List<String> unmet,
            boolean targetGoalPlan,
            Caller caller,
            String failure) {
        Goal goal = findGoal(goalId);
        if (goal == null || goal.status() != GoalStatus.ACTIVE) return Optional.empty();
        long target = targetGoalPlan ? goal.planId() : 0L;
        return Optional.of(base(subject, PlannerObjective.forGoal(goal, unmet), caller, failure, target, ""));
    }

    private Optional<PlannerInput> plan(PlanSubject.TroubledPlan subject, Caller caller, String failure) {
        PlanRecord plan = queue().plan(subject.planId()).orElse(null);
        if (plan == null) return Optional.empty();
        String reason = failure.isBlank() ? subject.failure() : failure;
        String pending = plan.cursor() == null ? "" : plan.cursor().pendingActionPath();
        return Optional.of(base(
                subject, PlannerObjective.forPlan(plan, linkedGoal(plan.id())), caller, reason, plan.id(), pending));
    }

    private Optional<PlannerInput> shift(PlanSubject.FreeTimeShift subject, Caller caller, String failure) {
        FreeTimeService service = freeTime == null ? null : freeTime.get();
        UUID owner = companion.ownerUuid();
        if (service == null || owner == null) return Optional.empty();
        FreeTime state = service.state(owner);
        Shift shift = state == null ? null : state.shift();
        if (shift == null || shift.id() != subject.shiftId()) return Optional.empty();
        return Optional.of(base(subject, PlannerObjective.forShift(shift, lessons(state)), caller, failure, 0L, ""));
    }

    private Optional<Goal> linkedGoal(long planId) {
        GoalBoard board = goals == null ? null : goals.get();
        if (board == null) return Optional.empty();
        return board.all().stream().filter(goal -> goal.planId() == planId).findFirst();
    }

    private Goal findGoal(long goalId) {
        GoalBoard board = goals == null ? null : goals.get();
        return board == null ? null : board.find(goalId).orElse(null);
    }

    private PlannerInput base(
            PlanSubject subject,
            Goal objective,
            Caller caller,
            String failure,
            long targetPlanId,
            String pendingNodePath) {
        ServerConfig settings = config.get();
        boolean shift = subject instanceof PlanSubject.FreeTimeShift;
        Set<String> denied = denied(subject);
        Map<String, TaskToolDefinition> source = shift ? planTools : taskTools;
        Map<String, JsonObject> schemas = new LinkedHashMap<>();
        StringBuilder digest = new StringBuilder();
        source.forEach((name, definition) -> {
            if (!denied.contains(name) && caller.access().allows(definition.minAccess())) {
                schemas.put(name, definition.schema());
                digest.append("- ").append(describeTool(definition)).append('\n');
            }
        });
        CompanionGuardFacts facts = new CompanionGuardFacts(companion);
        facts.refresh();
        String queue = QueueText.show(queue(), facts, null, 20, false);
        List<Place> known =
                settings.places.enabled && caller.access().allows(AccessLevel.TRUSTED) ? places.get() : List.of();
        return new PlannerInput(
                objective,
                new CompanionPerception(companion, perception).describe(caller),
                digest.toString(),
                PlaceText.planner(known, settings.places.plannerLimit),
                Optional.of(queue),
                objective.lessons(),
                failure,
                schemas,
                source.keySet(),
                shift ? List.of() : verifiedFacts.get(),
                List.of(),
                0,
                targetPlanId,
                targetPlanId == 0 ? "" : PlanFingerprint.of(queue(), targetPlanId),
                pendingNodePath);
    }

    private JobQueue queue() {
        return companion.executionCoordinator().queue();
    }

    /** The lessons a free-time shift starts from: its own, plus the newest failure worth avoiding. */
    private static List<String> lessons(FreeTime state) {
        List<String> lessons = new ArrayList<>(state.lessons());
        state.history().stream()
                .filter(record -> !record.success())
                .filter(record -> record.stopWhen() != ShiftStopWhen.OWNER_BACK
                        && record.stopWhen() != ShiftStopWhen.OWNER_SPOKE
                        && record.stopWhen() != ShiftStopWhen.OWNER_PAUSED)
                .findFirst()
                .ifPresent(record ->
                        lessons.add("Most recent failed activity: " + record.goal() + ": " + record.summary()));
        return lessons;
    }

    /** {@code name(arg*, arg): first sentence of the description}; a star marks a required argument. */
    static String describeTool(TaskToolDefinition definition) {
        JsonObject schema = definition.schema();
        Set<String> required = new java.util.HashSet<>();
        if (schema.has("required")) {
            schema.getAsJsonArray("required").forEach(element -> required.add(element.getAsString()));
        }
        List<String> args = new ArrayList<>();
        if (schema.has("properties")) {
            for (String arg : schema.getAsJsonObject("properties").keySet()) {
                args.add(arg + (required.contains(arg) ? "*" : ""));
            }
        }
        String description = definition.description() == null ? "" : definition.description();
        int stop = description.indexOf(". ");
        String first = stop > 0 ? description.substring(0, stop + 1) : description;
        return definition.name() + "(" + String.join(", ", args) + "): "
                + (first.length() > 200 ? first.substring(0, 200) : first);
    }
}
