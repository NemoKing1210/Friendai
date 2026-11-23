package dev.friendai.presentation.companion;

import dev.friendai.execution.queue.GuardFacts;
import dev.friendai.execution.queue.HoldKey;
import dev.friendai.execution.queue.Job;
import dev.friendai.execution.queue.JobQueue;
import dev.friendai.execution.queue.JobState;
import dev.friendai.execution.queue.PlanStatus;
import dev.friendai.execution.queue.QueuePolicy;
import dev.friendai.execution.queue.QueueSnapshot;
import dev.friendai.execution.queue.QueueText;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** Flattens the job queue into the bounded, ordered rows the menu draws as a chain. */
public final class QueueRows {
    public static final int MAX_ROWS = 60;
    private static final int MAX_ARGS = 120;
    private static final int MAX_NOTE = 160;

    private QueueRows() {}

    /**
     * Direct jobs first, then each plan in id order; inside a group the jobs follow their queue order.
     * {@code runningDescription} replaces the note of the running job.
     */
    public static List<QueueRow> build(JobQueue queue, GuardFacts facts, String runningDescription) {
        QueueSnapshot snapshot = QueuePolicy.snapshot(queue, facts);
        Map<Long, PlanStatus> statuses = QueuePolicy.statuses(queue);
        List<Long> readyIds = snapshot.ready().stream().map(Job::id).toList();
        List<Job> jobs = new ArrayList<>(queue.jobs());
        jobs.sort(Comparator.comparingLong(Job::planId).thenComparingLong(Job::order));
        List<QueueRow> rows = new ArrayList<>();
        for (Job job : jobs) {
            if (rows.size() >= MAX_ROWS) {
                break;
            }
            String state = job.state().name().toLowerCase(java.util.Locale.ROOT);
            String note = job.result();
            if (job.state() == JobState.PENDING) {
                boolean ready = readyIds.contains(job.id());
                state = ready ? "ready" : "blocked";
                // A job that already failed keeps that failure beside the reason it waits now: the countdown alone
                // says when the next attempt comes, never what went wrong, and it is gone once the retry is ready.
                String wait = ready ? "" : snapshot.waitingReasons().getOrDefault(job.id(), "");
                note = append(wait, lastAttempt(job));
            } else if (job.state() == JobState.RUNNING && runningDescription != null && !runningDescription.isBlank()) {
                note = runningDescription;
            }
            if (job.state() == JobState.RUNNING) {
                // A running step the owner has paused is parked at its tick boundary, and the row says so.
                note = append(
                        note,
                        QueuePolicy.blockingHold(queue, job, facts)
                                .map(hold -> hold.describe(queue.clock()))
                                .orElse(""));
            }
            String goal = job.planId() == 0
                    ? ""
                    : queue.plan(job.planId()).map(p -> p.goal()).orElse("");
            PlanStatus status = job.planId() == 0 ? null : statuses.get(job.planId());
            if (job.planId() > 0) {
                String progress = queue.plan(job.planId())
                        .filter(p -> p.cursor() != null)
                        .map(QueueText::planProgress)
                        .orElse("");
                if (!progress.isBlank()) note = note == null || note.isBlank() ? progress : note + "; " + progress;
            }
            rows.add(new QueueRow(
                    job.id(),
                    job.planId(),
                    goal,
                    status == null ? "" : status.name().toLowerCase(java.util.Locale.ROOT),
                    job.tool(),
                    QueueText.truncate(job.args().toString(), MAX_ARGS),
                    state,
                    QueueText.truncate(note, MAX_NOTE),
                    job.after(),
                    job.attempts(),
                    job.maxAttempts(),
                    job.priority()));
        }
        for (var plan : queue.plans()) {
            if (rows.size() >= MAX_ROWS) break;
            if (plan.cursor() == null || !queue.jobsOfPlan(plan.id()).isEmpty()) continue;
            PlanStatus status = statuses.get(plan.id());
            String note = queue.hold(HoldKey.plan(plan.id()))
                    .map(hold -> hold.describe(queue.clock()))
                    .orElse("");
            String progress = QueueText.planProgress(plan);
            rows.add(new QueueRow(
                    0,
                    plan.id(),
                    plan.goal(),
                    status == null ? "" : status.name().toLowerCase(java.util.Locale.ROOT),
                    "Conditional plan",
                    "",
                    "active",
                    QueueText.truncate(append(progress, note), MAX_NOTE),
                    List.of(),
                    0,
                    0,
                    plan.priority()));
        }
        return rows;
    }

    /** The stored failure of the last attempt, as one line the row can wrap; empty for a job that never ran. */
    private static String lastAttempt(Job job) {
        if (job.attempts() <= 0) return "";
        String result = oneLine(job.result());
        return result.isEmpty() ? "" : "last attempt: " + result;
    }

    private static String append(String first, String second) {
        if (first == null || first.isBlank()) return second;
        return second.isEmpty() ? first : first + "; " + second;
    }

    private static String oneLine(String text) {
        return text == null ? "" : text.replaceAll("\\s+", " ").trim();
    }
}
