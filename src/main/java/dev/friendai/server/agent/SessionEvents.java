package dev.friendai.server.agent;

import dev.friendai.FriendaiMod;
import dev.friendai.agent.goal.GoalService;
import dev.friendai.agent.recover.FailureHandler;
import dev.friendai.agent.recover.RecoveryAdvisor;
import dev.friendai.agent.verify.Fact;
import dev.friendai.agent.verify.Outcome;
import dev.friendai.agent.verify.OutcomeChecker;
import dev.friendai.agent.verify.WorldSnapshot;
import dev.friendai.debug.DebugService;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.execution.TaskWorkResult;
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
    private WorldSnapshot last;

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
        this.last = OutcomeFacts.snapshot(companion);
    }

    /** The text to append to an event line; empty when there is nothing verified to add. */
    String extra(boolean success, long planId, String tool, String text) {
        return extra(success, planId, tool, text, Optional.empty());
    }

    /** The text to append to an event line, with optional measured facts from the completed task. */
    String extra(boolean success, long planId, String tool, String text, Optional<TaskWorkResult> workResult) {
        try {
            WorldSnapshot after = OutcomeFacts.snapshot(companion);
            var board = goals.board(owner);
            // The goal that owns this plan is what the task was for: its criteria are the proof to check.
            List<dev.friendai.agent.goal.Criterion> criteria = planId == 0
                    ? List.of()
                    : board.all().stream()
                            .filter(goal -> goal.planId() == planId)
                            .findFirst()
                            .map(dev.friendai.agent.goal.Goal::criteria)
                            .orElse(List.of());
            var outcome = OutcomeChecker.check(
                    last, after, success, criteria, new CompanionGuardFacts(companion), workResult);
            emitOutcomeCheck(outcome, success, criteria.isEmpty(), planId, tool);
            last = after;
            session.noteVerified(outcome.facts().stream().map(Fact::text).toList());
            if (planId != 0) {
                // Only the goal that owns this plan inherited this task, so only it may cite these facts.
                board.all().stream()
                        .filter(goal -> goal.planId() == planId)
                        .findFirst()
                        .ifPresent(goal -> goalOutcomes.noteFacts(owner, goal.id(), outcome.facts()));
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
            if (success) {
                var facts = new CompanionGuardFacts(companion);
                facts.refresh();
                extra = handler.onSuccess(board, planId, factsLine, facts);
            } else {
                extra = handler.onFailure(board, planId, tool, text, factsLine);
            }
            goals.save(owner);
            for (var prior : priorGoals) {
                if (board.find(prior.id()).isEmpty()) goalOutcome.posted(prior.id(), success, text);
            }
            return extra;
        } catch (RuntimeException e) {
            FriendaiMod.LOGGER.warn("Could not build event facts", e);
            return "";
        }
    }

    /** A passive verdict record for the existing check; delivered speech is never treated as proof. */
    private void emitOutcomeCheck(
            Outcome outcome, boolean taskSucceeded, boolean hasCriteria, long planId, String tool) {
        String verdict;
        if (!taskSucceeded) {
            verdict = "unmet";
        } else if (!hasCriteria) {
            verdict = "unknown";
        } else {
            verdict = outcome.success() ? "met" : "unmet";
        }
        Map<String, String> data = new LinkedHashMap<>();
        data.put("verdict", verdict);
        data.put("subject", "companion");
        data.put("source", "outcome_checker");
        data.put("plan", Long.toString(planId));
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
                        0L,
                        0L,
                        tool == null ? "" : tool,
                        0L,
                        -1L,
                        "outcome: " + verdict,
                        data,
                        null));
    }
}
