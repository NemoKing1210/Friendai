package dev.friendai.server.agent;

import dev.friendai.agent.autonomy.AutonomyFacts;
import dev.friendai.agent.autonomy.AutonomySettings;
import dev.friendai.agent.autonomy.Executor;
import dev.friendai.agent.autonomy.FreeTime;
import dev.friendai.agent.autonomy.FreeTimeLogEvents;
import dev.friendai.agent.autonomy.FreeTimeService;
import dev.friendai.agent.autonomy.Need;
import dev.friendai.agent.autonomy.NeedKind;
import dev.friendai.agent.autonomy.NeedsAssessor;
import dev.friendai.agent.autonomy.ReflexPlans;
import dev.friendai.agent.autonomy.ReflexStep;
import dev.friendai.agent.autonomy.ShiftPolicy;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.server.execution.tools.TaskToolDefinition;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Queues the deterministic free-time templates: one named need, or the first agenda entry that has one. */
final class ReflexQueue {
    /** What the queue needs from the driver: the duplicate guard, the plan queue and the shift bookkeeping. */
    interface Host {
        Optional<String> refusalToStart(CompanionEntity companion, boolean ownerGoalActive, List<ReflexStep> steps);

        Optional<Long> queue(
                UUID owner,
                CompanionEntity companion,
                String goal,
                List<ReflexStep> steps,
                int minutes,
                Map<String, TaskToolDefinition> tools);

        /** The stage a shift started from no longer applies. */
        void stageCleared(UUID owner);

        void factsInvalidated(UUID owner);
    }

    private final Host host;
    private final FreeTimeService service;
    private final CompanionSessions brains;
    private final Map<String, TaskToolDefinition> taskTools;

    ReflexQueue(
            Host host, FreeTimeService service, CompanionSessions brains, Map<String, TaskToolDefinition> taskTools) {
        this.host = host;
        this.service = service;
        this.brains = brains;
        this.taskTools = taskTools;
    }

    boolean template(
            UUID owner,
            CompanionEntity companion,
            Need need,
            Optional<List<ReflexStep>> steps,
            AutonomySettings s,
            long nowMillis,
            boolean ownerGoalActive) {
        if (steps.isEmpty()) {
            service.cooldown(owner, need.kind(), nowMillis + s.failCooldownMin() * 60_000L);
            return false;
        }
        if (host.refusalToStart(companion, ownerGoalActive, steps.get()).isPresent()) {
            return false; // the queue already has this work, or the owner's work comes first
        }
        boolean shelter = need.kind() == NeedKind.SHELTER || need.kind() == NeedKind.SURVIVE;
        int minutes = shelter ? FreeTimeDriver.SHELTER_MINUTES : s.shiftMinutes();
        Optional<Long> plan =
                host.queue(owner, companion, "free time: " + need.text(), steps.get(), minutes, taskTools);
        if (plan.isEmpty()) {
            service.cooldown(owner, need.kind(), nowMillis + s.failCooldownMin() * 60_000L);
            return false;
        }
        host.stageCleared(owner);
        service.beginShift(
                owner, need.text(), List.of(), "", need.kind(), true, nowMillis, nowMillis + minutes * 60_000L);
        service.linkPlan(owner, plan.get());
        brains.logFreeTime(FreeTimeLogEvents.start(
                owner,
                companion.getUUID(),
                brains.logSession(owner),
                plan.get(),
                need.kind().name(),
                need.text()));
        host.factsInvalidated(owner);
        return true;
    }

    /**
     * Starts the first agenda entry that has a deterministic template, reflex first. Used when the director is
     * silent, the planner failed, the budget is spent or the watchdog fired: the hour must keep moving.
     */
    boolean firstTemplate(
            UUID owner,
            CompanionEntity companion,
            AutonomyFacts f,
            AutonomySettings s,
            long nowMillis,
            boolean ownerGoalActive) {
        FreeTime state = service.state(owner);
        List<Need> needs = NeedsAssessor.assess(f, state, s);
        for (Need need : needs) {
            if (need.executor() != Executor.REFLEX) {
                continue;
            }
            Optional<List<ReflexStep>> steps = ReflexPlans.plan(need, f, s);
            if (steps.isPresent() && template(owner, companion, need, steps, s, nowMillis, ownerGoalActive)) {
                return true;
            }
        }
        for (NeedKind kind : ShiftPolicy.FALLBACK_ORDER) {
            for (Need need : needs) {
                if (need.kind() != kind) {
                    continue;
                }
                Optional<List<ReflexStep>> steps = ReflexPlans.fallback(need, f);
                if (steps.isPresent() && template(owner, companion, need, steps, s, nowMillis, ownerGoalActive)) {
                    return true;
                }
            }
        }
        return false;
    }
}
