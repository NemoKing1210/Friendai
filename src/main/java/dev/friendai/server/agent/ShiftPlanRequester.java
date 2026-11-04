package dev.friendai.server.agent;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.friendai.agent.autonomy.AutonomyFacts;
import dev.friendai.agent.autonomy.FreeTime;
import dev.friendai.agent.autonomy.FreeTimeLogEvents;
import dev.friendai.agent.autonomy.FreeTimeService;
import dev.friendai.agent.autonomy.ReflexPlans;
import dev.friendai.agent.autonomy.ReflexStep;
import dev.friendai.agent.autonomy.Shift;
import dev.friendai.agent.autonomy.ShiftBudget;
import dev.friendai.agent.autonomy.ShiftStopWhen;
import dev.friendai.agent.plan.PlanFailure;
import dev.friendai.agent.plan.PlanSubject;
import dev.friendai.agent.plan.PlannerException;
import dev.friendai.agent.plan.PlannerInput;
import dev.friendai.agent.turn.PlannerAttempt;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.execution.tool.AccessLevel;
import dev.friendai.execution.tool.Caller;
import dev.friendai.features.places.minecraft.PlaceService;
import dev.friendai.server.execution.tools.TaskToolDefinition;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * Asks the planner for one free-time shift's plan and applies the answer: queue it, fall back to a deterministic
 * template, or close the shift. One request per shift, and an answer that arrives after the shift moved is dropped.
 */
final class ShiftPlanRequester {
    /** The duplicate guard the driver owns. */
    interface Refusal {
        Optional<String> check(CompanionEntity companion, boolean ownerGoalActive, List<ReflexStep> steps);
    }

    /** The plan queue the driver owns, in both of the shapes a proposal and a template arrive in. */
    interface Queuer {
        Optional<Long> queueJson(
                UUID owner,
                CompanionEntity companion,
                String goal,
                JsonArray steps,
                int minutes,
                Map<String, TaskToolDefinition> tools);

        Optional<Long> queueSteps(
                UUID owner,
                CompanionEntity companion,
                String goal,
                List<ReflexStep> steps,
                int minutes,
                Map<String, TaskToolDefinition> tools);
    }

    /** Closing the shift the driver owns. */
    interface ShiftEnd {
        void finish(
                UUID owner,
                CompanionEntity companion,
                Shift shift,
                boolean success,
                String summary,
                ShiftStopWhen stopWhen,
                long nowMillis,
                boolean report);
    }

    private final CompanionSessions brains;
    private final FreeTimeService service;
    private final ShiftReporter reporter;
    private final AutonomyFactsBuilder facts;
    private final PlaceService placeService;
    private final Map<UUID, Integer> planEpoch;
    private final Set<UUID> planning;
    private final Function<UUID, ShiftBudget> budgets;
    private final Map<String, TaskToolDefinition> taskTools;
    private final Map<String, TaskToolDefinition> planTools;
    private final Refusal refusal;
    private final Queuer queuer;
    private final ShiftEnd end;

    ShiftPlanRequester(
            CompanionSessions brains,
            FreeTimeService service,
            ShiftReporter reporter,
            AutonomyFactsBuilder facts,
            PlaceService placeService,
            Map<UUID, Integer> planEpoch,
            Set<UUID> planning,
            Function<UUID, ShiftBudget> budgets,
            Map<String, TaskToolDefinition> taskTools,
            Map<String, TaskToolDefinition> planTools,
            Refusal refusal,
            Queuer queuer,
            ShiftEnd end) {
        this.brains = brains;
        this.service = service;
        this.reporter = reporter;
        this.facts = facts;
        this.placeService = placeService;
        this.planEpoch = planEpoch;
        this.planning = planning;
        this.budgets = budgets;
        this.taskTools = taskTools;
        this.planTools = planTools;
        this.refusal = refusal;
        this.queuer = queuer;
        this.end = end;
    }

    void request(UUID owner, CompanionEntity companion, long shiftId, int minutes, boolean speakFailure) {
        int epoch = planEpoch.getOrDefault(owner, 0);
        Caller caller = new Caller(owner, "", AccessLevel.OWNER);
        PlanSubject subject = new PlanSubject.FreeTimeShift(shiftId);
        Optional<PlannerInput> input = brains.plannerInputs(owner, companion).build(subject, caller, "");
        if (input.isEmpty()) {
            brains.plannerDebug(owner, "planner disposition=given up: the shift ended before it could be planned");
            logPlanner(owner, subject, "fallback", "no-input", 0L, 0L);
            return;
        }
        planning.add(owner);
        logPlanner(owner, subject, "request", "", -1L, 0L);
        long startedAt = System.nanoTime();
        brains.propose(owner, input.get(), true)
                .whenComplete((proposal, error) -> brains.onServerThread(() -> {
                    long durationMs = (System.nanoTime() - startedAt) / 1_000_000L;
                    if (epoch != planEpoch.getOrDefault(owner, 0)) {
                        logPlanner(owner, subject, "fallback", "stale", durationMs, 0L);
                        return; // a teleport, a dismissal or a stop made this answer stale
                    }
                    planning.remove(owner);
                    FreeTime state = service.state(owner);
                    if (!state.on() || state.shift() == null || state.shift().id() != shiftId) {
                        logPlanner(owner, subject, "fallback", "stale", durationMs, 0L);
                        return; // stopped or replaced while the planner was thinking
                    }
                    Optional<String> refused = error == null && proposal != null
                            ? refusal.check(companion, false, stepsOf(proposal.stepsJson()))
                            : Optional.empty();
                    if (refused.isPresent()) {
                        Shift shift = state.shift();
                        end.finish(
                                owner,
                                companion,
                                shift,
                                false,
                                "the queue already holds this work",
                                ShiftStopWhen.WORK_CONFLICTS,
                                FreeTimeDriver.now(),
                                true);
                        brains.plannerDebug(owner, "planner disposition=refused: " + refused.get());
                        logPlanner(owner, subject, "fallback", "refused", durationMs, 0L);
                        return;
                    }
                    Optional<Long> plan = error != null || proposal == null
                            ? Optional.empty()
                            : queuer.queueJson(
                                    owner, companion, state.shift().goal(), proposal.stepsJson(), minutes, planTools);
                    if (plan.isEmpty()) {
                        long now = FreeTimeDriver.now();
                        Throwable ownerError = error;
                        if (ownerError == null && proposal != null) {
                            ownerError = new PlannerException(new PlanFailure(
                                    PlanFailure.Code.STEPS_INVALID, "the proposed plan was refused by the queue", 1));
                        }
                        Throwable cause = PlanFailure.unwrap(error);
                        String reason = cause instanceof PlannerException failure
                                ? "planner failed: " + failure.failure().code()
                                : "planner could not produce a usable plan";
                        brains.plannerDebug(
                                owner,
                                error == null && proposal != null
                                        ? "planner disposition=queue refused: the proposed plan was refused by the queue"
                                        : "planner disposition=given up");
                        String code = cause instanceof PlannerException failure
                                ? failure.failure().code().name()
                                : (proposal != null ? "STEPS_INVALID" : "REQUEST_FAILED");
                        logPlanner(owner, subject, "failed", code, durationMs, 0L);
                        // The director may keep its hour moving with a deterministic template. An owner-requested
                        // draft must report the planner failure and end without guessing at a different plan.
                        AutonomyFacts f = facts.build(companion, owner, now, placeService);
                        Optional<List<ReflexStep>> template = speakFailure
                                ? Optional.empty()
                                : ReflexPlans.fallback(state.shift().need(), f);
                        if (template.isPresent()
                                && refusal.check(companion, false, template.get())
                                        .isEmpty()) {
                            Optional<Long> fallback = queuer.queueSteps(
                                    owner, companion, state.shift().goal(), template.get(), minutes, taskTools);
                            if (fallback.isPresent()) {
                                service.linkPlan(owner, fallback.get());
                                brains.logFreeTime(FreeTimeLogEvents.start(
                                        owner,
                                        companion.getUUID(),
                                        brains.logSession(owner),
                                        fallback.get(),
                                        state.shift().need().name(),
                                        state.shift().goal()));
                                facts.invalidate(owner);
                                budgets.apply(owner).failed();
                                brains.plannerDebug(owner, "planner disposition=queued fallback");
                                logPlanner(owner, subject, "fallback", "template", durationMs, fallback.get());
                                return;
                            }
                        }
                        // The need did not fail, the planner could not draft work for it: only the director backs off.
                        Shift shift = state.shift();
                        // One line, not two: when the owner asked for this shift they get the specific failure reason
                        // instead of the generic end report, which would say the same thing twice.
                        end.finish(
                                owner,
                                companion,
                                shift,
                                false,
                                "no plan: " + reason,
                                ShiftStopWhen.WORK_CONFLICTS,
                                now,
                                !speakFailure);
                        if (speakFailure) {
                            reporter.tell(owner, companion, PlanFailure.ownerMessage(ownerError));
                        }
                        budgets.apply(owner).failed();
                        logPlanner(owner, subject, "fallback", "none", durationMs, 0L);
                        return;
                    }
                    logPlanner(owner, subject, "delivered", "", durationMs, plan.get());
                    service.linkPlan(owner, plan.get());
                    brains.logFreeTime(FreeTimeLogEvents.start(
                            owner,
                            companion.getUUID(),
                            brains.logSession(owner),
                            plan.get(),
                            state.shift().need().name(),
                            state.shift().goal()));
                    facts.invalidate(owner);
                    brains.plannerDebug(owner, "planner disposition=queued plan #" + plan.get());
                }));
    }

    /** One durable planner record for the shift's consultation; the subject carries the shift id, never its text. */
    private void logPlanner(
            UUID owner, PlanSubject subject, String disposition, String code, long durationMs, long planId) {
        brains.logPlanner(owner, new PlannerAttempt(subject, 1, disposition, code, durationMs, planId));
    }

    /** A planner proposal's steps in the shape the duplicate guard reads: a tool and its named arguments. */
    static List<ReflexStep> stepsOf(JsonArray steps) {
        List<ReflexStep> out = new java.util.ArrayList<>();
        for (JsonElement element : steps) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject step = element.getAsJsonObject();
            if (!step.has("tool") || step.get("tool").isJsonNull()) {
                continue;
            }
            JsonObject args = new JsonObject();
            JsonElement raw = step.get("args");
            if (raw != null && raw.isJsonObject()) {
                args = raw.getAsJsonObject();
            } else if (raw != null && raw.isJsonPrimitive()) {
                try {
                    args = JsonParser.parseString(raw.getAsString()).getAsJsonObject();
                } catch (RuntimeException ignored) {
                    args = new JsonObject();
                }
            }
            out.add(new ReflexStep(step.get("tool").getAsString(), args));
        }
        return out;
    }
}
