package dev.friendai.debug;

import dev.friendai.queue.GuardFacts;
import dev.friendai.queue.Job;
import dev.friendai.queue.JobQueue;
import dev.friendai.queue.JobState;
import dev.friendai.queue.PlanStatus;
import dev.friendai.queue.QueuePolicy;
import dev.friendai.queue.QueueSnapshot;
import dev.friendai.queue.QueueText;
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
                note = ready ? "" : snapshot.waitingReasons().getOrDefault(job.id(), "");
            } else if (job.state() == JobState.RUNNING && runningDescription != null && !runningDescription.isBlank()) {
                note = runningDescription;
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
            rows.add(new QueueRow(
                    0,
                    plan.id(),
                    plan.goal(),
                    status == null ? "" : status.name().toLowerCase(java.util.Locale.ROOT),
                    "Conditional plan",
                    "",
                    "active",
                    QueueText.truncate(QueueText.planProgress(plan), MAX_NOTE),
                    List.of(),
                    0,
                    0,
                    plan.priority()));
        }
        return rows;
    }
}
