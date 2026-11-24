package dev.friendai.server.agent;

import dev.friendai.FriendaiMod;
import dev.friendai.agent.goal.GoalService;
import dev.friendai.agent.recover.FailureHandler;
import dev.friendai.agent.recover.RecoveryAdvisor;
import dev.friendai.agent.verify.Fact;
import dev.friendai.agent.verify.Outcome;
import dev.friendai.agent.verify.OutcomeChecker;
import dev.friendai.agent.verify.VerifiedEvidence;
import dev.friendai.agent.verify.WorldSnapshot;
import dev.friendai.debug.DebugService;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.execution.TaskDisposition;
import dev.friendai.execution.TaskWorkResult;
import dev.friendai.execution.log.TaskLogCorrelation;
import dev.friendai.foundation.diagnostics.LogCategory;
import dev.friendai.foundation.diagnostics.LogEvents;
import dev.friendai.foundation.diagnostics.LogLevel;
import dev.friendai.foundation.diagnostics.LogRecord;
import dev.friendai.task.queue.CompanionGuardFacts;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The verified outcome of one companion's task or plan event: what the world showed before and after, the goal
 * criteria that evidence meets, the diagnosis and remedy a failure earns, and the durable record an investigator
 * reads. It never starts, stops or speaks anything; it turns one event into the facts and goal bookkeeping the
 * session's task-event sink needs.
 *
 * <p>One instance per companion session, constructed by {@code CompanionSessions.create} and called only from that
 * session's listener callbacks. Every call runs on the server thread, which is also the only thread that reads the
 * companion, its goal board and its queue.
 */
final class SessionEvents {
    /** A terminal goal this event finished, posted as a teaching outcome by the owning session. */
    @FunctionalInterface
    interface GoalOutcome {
        void posted(long goalId, boolean success, String text);
    }

    private final UUID owner;
    private final CompanionEntity companion;
    private final CompanionSession session;
    private final GoalService goals;
    private final GoalOutcomeRecorder goalOutcomes;
    private final DebugService debug;
    private final GoalOutcome goalOutcome;
    private final FailureHandler handler;
    private final Map<Long, WorldSnapshot> starts = new LinkedHashMap<>();

    SessionEvents(
            UUID owner,
            CompanionEntity companion,
            CompanionSession session,
            GoalService goals,
            GoalOutcomeRecorder goalOutcomes,
            DebugService debug,
            GoalOutcome goalOutcome) {
        this.owner = owner;
        this.companion = companion;
        this.session = session;
        this.goals = goals;
        this.goalOutcomes = goalOutcomes;
        this.debug = debug;
        this.goalOutcome = goalOutcome;
        // The coordinator owns the counters this handler feeds: a repeated remedy shows up in the owner's
        // debug-log loop window, not in a private tally nobody reads.
        this.handler = new FailureHandler(
                new RecoveryAdvisor(), companion.executionCoordinator().loopMetrics());
    }

    /** Captures the server-thread evidence window when the existing task lifecycle publishes its start. */
    void started(long execution) {
        if (execution <= 0) return;
        starts.remove(execution);
        starts.put(execution, OutcomeFacts.snapshot(companion));
        while (starts.size() > TaskLogCorrelation.MAX_EXECUTIONS) {
            starts.remove(starts.keySet().iterator().next());
        }
    }

    /** Drops a cancelled execution's evidence window; its partial receipts remain in task.cancel diagnostics. */
    void cancelled(long execution) {
        if (execution > 0) starts.remove(execution);
    }

    /** Records the queued activation's evidence without running plan recovery or updating goal criteria. */
    void jobSettled(
            TaskDisposition disposition,
            long execution,
            long planId,
            long jobId,
            String tool,
            Optional<TaskWorkResult> workResult) {
        WorldSnapshot before = starts.remove(execution);
        if (disposition == TaskDisposition.CANCELLED || execution <= 0) return;
        try {
            WorldSnapshot after = OutcomeFacts.snapshot(companion);
            boolean succeeded = disposition == TaskDisposition.SUCCESS;
            Outcome outcome = OutcomeChecker.check(
                    before == null ? after : before, after, succeeded, List.of(), null, workResult, false);
            emitOutcomeCheck(outcome, succeeded, true, execution, planId, jobId, tool);
        } catch (RuntimeException e) {
            FriendaiMod.LOGGER.warn("Could not build queued task evidence", e);
        }
    }

    /** The text to append to an event line; empty when there is nothing verified to add. */
    String extra(boolean success, long planId, String tool, String text) {
        return extra(success, planId, tool, text, Optional.empty());
    }

    /** The text to append to an event line, with optional measured facts from the completed task. */
    String extra(boolean success, long planId, String tool, String text, Optional<TaskWorkResult> workResult) {
        return extra(success ? TaskDisposition.SUCCESS : TaskDisposition.FAILED, 0, planId, tool, text, workResult);
    }

    /** Builds facts against only the snapshot owned by this execution; a plan event has no task delta. */
    String extra(
            TaskDisposition disposition,
            long execution,
            long planId,
            String tool,
            String text,
            Optional<TaskWorkResult> workResult) {
        if (disposition == TaskDisposition.CANCELLED) {
            cancelled(execution);
            return "";
        }
        try {
            WorldSnapshot before = execution > 0 ? starts.remove(execution) : null;
            WorldSnapshot after = OutcomeFacts.snapshot(companion);
            if (before == null) before = after;
            var board = goals.board(owner);
            // The goal that owns this plan is what the task was for: its criteria are the proof to check.
            var owning = planId == 0
                    ? null
                    : board.all().stream()
                            .filter(goal -> goal.planId() == planId)
                            .findFirst()
                            .orElse(null);
            List<dev.friendai.agent.goal.Criterion> criteria = owning == null ? List.of() : owning.criteria();
            var outcome = OutcomeChecker.check(
                    before,
                    after,
                    disposition == TaskDisposition.SUCCESS,
                    criteria,
                    new CompanionGuardFacts(companion),
                    workResult,
                    false);
            emitOutcomeCheck(
                    outcome, disposition == TaskDisposition.SUCCESS, criteria.isEmpty(), execution, planId, 0, tool);
            // One receipt per verified item, tagged with the goal and plan it proves, so a later planner call for
            // other work cannot inherit it. A delivery is history; a stock count must still be checked against the
            // live inventory when the planner asks.
            long now = companion.level().getGameTime();
            long owningGoal = owning == null ? 0L : owning.id();
            for (VerifiedEvidence receipt :
                    VerifiedEvidence.fromWorkResult(workResult.orElse(null), owningGoal, planId, now)) {
                session.noteVerified(receipt);
            }
            if (owning != null) {
                // Only the goal that owns this plan inherited this task, so only it may cite these facts.
                goalOutcomes.noteFacts(owner, owning.id(), outcome.facts());
            }
            String factsLine = outcome.factsLine();
            if (!outcome.unmetCriteria().isEmpty()) {
                // A criterion the world cannot show is not a completion, and the model must be told so.
                factsLine = (factsLine.isBlank() ? "" : factsLine + " ")
                        + "not proven yet: "
                        + String.join("; ", outcome.unmetCriteria())
                        + ".";
            }
            var priorGoals = board.all();
            String extra;
            if (disposition == TaskDisposition.SUCCESS) {
                var facts = new CompanionGuardFacts(companion);
                facts.refresh();
                extra = handler.onSuccess(board, planId, factsLine, facts);
            } else {
                extra = handler.onTerminal(board, planId, tool, disposition, text, factsLine);
            }
            goals.save(owner);
            for (var prior : priorGoals) {
                if (board.find(prior.id()).isEmpty())
                    goalOutcome.posted(prior.id(), disposition == TaskDisposition.SUCCESS, text);
            }
            return extra;
        } catch (RuntimeException e) {
            FriendaiMod.LOGGER.warn("Could not build event facts", e);
            return "";
        }
    }

    /** A passive verdict record for the existing check; delivered speech is never treated as proof. */
    private void emitOutcomeCheck(
            Outcome outcome,
            boolean taskSucceeded,
            boolean hasNoCriteria,
            long execution,
            long planId,
            long jobId,
            String tool) {
        String verdict;
        if (!taskSucceeded) {
            verdict = "unmet";
        } else if (hasNoCriteria) {
            verdict = "unknown";
        } else {
            verdict = outcome.success() ? "met" : "unmet";
        }
        Map<String, String> data = new LinkedHashMap<>();
        data.put("verdict", verdict);
        data.put("subject", "companion");
        data.put("source", "outcome_checker");
        data.put("plan", Long.toString(planId));
        if (execution > 0) data.put("execution", Long.toString(execution));
        data.put("facts", outcome.facts().stream().map(Fact::text).collect(Collectors.joining("; ")));
        data.put("unmet", String.join("; ", outcome.unmetCriteria()));
        debug.pipeline()
                .emit(new LogRecord(
                        0L,
                        0L,
                        LogLevel.INFO,
                        LogCategory.TASK,
                        null,
                        LogEvents.OUTCOME_CHECK,
                        owner,
                        companion.getUUID(),
                        "",
                        0L,
                        planId,
                        jobId,
                        0L,
                        tool == null ? "" : tool,
                        0L,
                        -1L,
                        "outcome: " + verdict,
                        data,
                        null));
    }
}
