package dev.friendai.execution.queue;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** Compact, bounded LLM-facing queue rendering. */
public final class QueueText {
    public static final int MAX_LINE_ARGS = 160,
            MAX_RESULT = 200,
            MAX_SHOW_CHARS = 4096,
            MAX_SUMMARY_REASON = 160,
            MAX_BLOCKED_REASON = 300,
            MAX_PLAN_RESULTS = 600,
            MAX_ARCHIVES_SHOWN = 5;

    /** Results listed for one finished plan; a longer plan says how many were left out. */
    static final int MAX_RESULT_ENTRIES = 8;

    private QueueText() {}

    public static String truncate(String text, int max) {
        if (text == null) return "";
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }

    /**
     * What each finished step of a plan reported, newest last by job id, as {@code #id tool: result}. Cancelled and
     * skipped steps are left out: their "result" is the cancellation text, not something the step produced.
     */
    public static String planResults(JobQueue q, long planId, int maxChars) {
        List<Job> jobs = new ArrayList<>(q.jobsOfPlan(planId));
        jobs.sort(Comparator.comparingLong(Job::id));
        StringBuilder out = new StringBuilder();
        int entries = 0;
        boolean dropped = false;
        for (Job job : jobs) {
            if (job.state() != JobState.DONE && job.state() != JobState.FAILED) continue;
            String result = job.result();
            if (result == null || result.isBlank()) continue;
            if (entries == MAX_RESULT_ENTRIES) {
                dropped = true;
                break;
            }
            if (!out.isEmpty()) out.append("; ");
            out.append('#')
                    .append(job.id())
                    .append(' ')
                    .append(job.tool())
                    .append(": ")
                    .append(result);
            entries++;
        }
        if (out.isEmpty()) return "";
        if (!dropped) return truncate(out.toString(), maxChars);
        String room = truncate(out.toString(), Math.max(0, maxChars - "; …".length()));
        return room + "; …";
    }

    /** The plan summary a finished plan reports, with its steps' own results appended once. */
    public static String finishedSummary(JobQueue q, long planId, String summary) {
        String base = summary == null ? "" : summary;
        String results = planResults(q, planId, MAX_PLAN_RESULTS);
        if (results.isEmpty()) return base;
        if (base.isEmpty()) return results;
        if (base.contains(results)) return base;
        return base + "; " + results;
    }

    public static String show(JobQueue q, GuardFacts facts, Long planId, int limit, boolean includeDone) {
        var snapshot = QueuePolicy.snapshot(q, facts);
        if (q.jobCount() == 0 && q.plans().stream().noneMatch(p -> p.cursor() != null)) {
            String archives = archiveLines(q, planId, true);
            return archives.isEmpty() ? "queue: empty" : "queue: no jobs" + archives;
        }
        StringBuilder out = new StringBuilder("queue: ")
                .append(snapshot.running().size())
                .append(" running, ")
                .append(snapshot.pending())
                .append(" pending; plans: ")
                .append(q.planCount());
        for (PlanRecord plan : q.plans()) {
            if (plan.cursor() == null || planId != null && plan.id() != planId) continue;
            out.append("\nplan #")
                    .append(plan.id())
                    .append(" \"")
                    .append(truncate(plan.goal(), 80))
                    .append("\": ")
                    .append(planProgress(plan));
            q.hold(plan.id())
                    .ifPresent(hold ->
                            out.append(" [").append(hold.describe(q.clock())).append(']'));
            out.append("\ntree: ").append(planTree(plan));
        }
        List<Job> jobs = q.jobs().stream()
                .filter(j -> planId == null || j.planId() == planId)
                .toList();
        int shown = 0;
        for (Job j : jobs) {
            boolean problem = j.state() == JobState.FAILED
                    || (j.state() == JobState.CANCELLED && Job.MISSING_TOOL_RESULT.equals(j.result()));
            if (!includeDone && JobQueue.terminal(j) && !problem) continue;
            if (!problem && shown++ >= limit) continue;
            out.append('\n').append(line(q, j, snapshot.waitingReasons().get(j.id())));
        }
        out.append(archiveLines(q, planId, includeDone));
        return truncate(out.toString(), MAX_SHOW_CHARS);
    }

    /**
     * The newest archived plans, newest first; a plan named by {@code planId} is shown whatever the flags say, and an
     * unnarrowed listing is shown only when the caller asked for finished work.
     */
    private static String archiveLines(JobQueue q, Long planId, boolean unnamedListing) {
        List<PlanArchive> archives = q.archives();
        StringBuilder out = new StringBuilder();
        int shown = 0;
        for (int i = archives.size() - 1; i >= 0; i--) {
            PlanArchive archive = archives.get(i);
            if (planId != null) {
                if (archive.id() != planId) continue;
            } else if (!unnamedListing || shown >= MAX_ARCHIVES_SHOWN) {
                continue;
            }
            shown++;
            out.append("\nplan #")
                    .append(archive.id())
                    .append(" \"")
                    .append(truncate(archive.goal(), 80))
                    .append("\": ")
                    .append(archive.success() ? "success" : "failed")
                    .append(", ")
                    .append(archive.stepCount())
                    .append(archive.stepCount() == 1 ? " step, " : " steps, ")
                    .append(truncate(archive.summary(), MAX_RESULT))
                    .append(" (finished tick ")
                    .append(archive.finishedTick())
                    .append(')');
        }
        return out.toString();
    }

    public static String summary(JobQueue q, GuardFacts facts, String runningDescription) {
        if (q.jobCount() == 0 && q.plans().stream().noneMatch(p -> p.cursor() != null)) return "Task queue: empty";
        var s = QueuePolicy.snapshot(q, facts);
        StringBuilder out = new StringBuilder("Task queue: ")
                .append(s.running().size())
                .append(" running, ")
                .append(s.pending())
                .append(" pending");
        if (!s.waiting().isEmpty()) out.append(", ").append(s.waiting().size()).append(" blocked");
        out.append("; plans: ").append(q.planCount());
        if (!s.running().isEmpty()) {
            Job j = s.running().getFirst();
            out.append("\nRunning: #")
                    .append(j.id())
                    .append(' ')
                    .append(planPart(q, j))
                    .append(": ")
                    .append(truncate(runningDescription == null ? j.tool() : runningDescription, MAX_LINE_ARGS));
        }
        if (!s.ready().isEmpty()) {
            out.append("\nNext: ");
            for (int i = 0; i < Math.min(2, s.ready().size()); i++) {
                if (i > 0) out.append("; ");
                Job j = s.ready().get(i);
                out.append('#')
                        .append(j.id())
                        .append(" (")
                        .append(tag(q, j))
                        .append(") ")
                        .append(j.tool());
            }
        }
        if (!s.waiting().isEmpty()) {
            Job j = s.waiting().getFirst();
            out.append("\nBlocked: #")
                    .append(j.id())
                    .append(' ')
                    .append(planPart(q, j))
                    .append(' ')
                    .append(j.tool())
                    .append(": ")
                    .append(truncate(QueuePolicy.summaryReason(q, j, facts), MAX_SUMMARY_REASON));
        }
        for (PlanRecord plan : q.plans())
            if (plan.cursor() != null && q.jobsOfPlan(plan.id()).isEmpty()) {
                out.append("\nPlan #")
                        .append(plan.id())
                        .append(' ')
                        .append(truncate(plan.goal(), 80))
                        .append(": ")
                        .append(planProgress(plan));
            }
        return truncate(out.toString(), 1200);
    }
    /** What a clear removed, counted before the plans were archived and their jobs removed. */
    public static String cleared(int plans, int jobs) {
        if (plans == 0 && jobs == 0) return "queue is already empty";
        return "cleared " + plans + (plans == 1 ? " plan and " : " plans and ") + jobs + (jobs == 1 ? " job" : " jobs");
    }

    /** How much work a blocked plan still has, as the model reads it; a lower bound says so. */
    public static String remainingSteps(int steps, boolean exact) {
        return (exact ? "" : "at least ") + steps + " step(s) remain";
    }

    /** Human-readable, bounded view of the interpreter position and repeat counters. */
    public static String planProgress(PlanRecord plan) {
        var cursor = plan.cursor();
        if (cursor == null) return "flat plan";
        String path = !cursor.pendingActionPath().isEmpty()
                ? cursor.pendingActionPath()
                : cursor.stack().isEmpty()
                        ? "complete"
                        : currentPath(cursor.stack().getLast());
        StringBuilder out = new StringBuilder("current ").append(path);
        cursor.selectedBranches().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> out.append("; ")
                        .append(entry.getKey())
                        .append(" selected ")
                        .append(entry.getValue() ? "then" : "else"));
        cursor.repeatIterations().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    int maximum = repeatMaximum(plan.definition(), entry.getKey());
                    out.append("; ")
                            .append(entry.getKey())
                            .append(" iteration ")
                            .append(entry.getValue())
                            .append('/')
                            .append(maximum);
                });
        return truncate(out.toString(), MAX_SUMMARY_REASON);
    }

    private static String planTree(PlanRecord plan) {
        StringBuilder out = new StringBuilder();
        appendNodes(
                out, plan.definition() == null ? List.of() : plan.definition().nodes(), "step");
        return truncate(out.toString(), 1400);
    }

    private static void appendNodes(StringBuilder out, List<PlanNode> nodes, String prefix) {
        out.append('[');
        for (int i = 0; i < nodes.size() && out.length() < 1400; i++) {
            if (i > 0) out.append(", ");
            String path = prefix.equals("step")
                    ? prefix + " " + (i + 1)
                    : prefix.endsWith(" → then") || prefix.endsWith(" → else")
                            ? prefix + " #" + (i + 1)
                            : prefix + " → step " + (i + 1);
            PlanNode node = nodes.get(i);
            if (node instanceof PlanNode.Action action) {
                out.append(path).append(':').append(action.step().tool());
            } else if (node instanceof PlanNode.Conditional conditional) {
                out.append("if(")
                        .append(path)
                        .append(',')
                        .append(conditional.condition().toJson())
                        .append(") then=");
                appendNodes(out, conditional.thenNodes(), path + " → then");
                out.append(" else=");
                appendNodes(out, conditional.elseNodes(), path + " → else");
            } else {
                PlanNode.Repeat repeat = (PlanNode.Repeat) node;
                out.append("repeat(")
                        .append(path)
                        .append(',')
                        .append(repeat.until().toJson())
                        .append(",cap=")
                        .append(repeat.maxIterations())
                        .append(") body=");
                appendNodes(out, repeat.body(), path + " → repeat");
            }
        }
        out.append(']');
    }

    private static String currentPath(PlanCursor.Frame frame) {
        String prefix = frame.pathPrefix().isEmpty() ? "plan" : frame.pathPrefix();
        return prefix + " → step " + (frame.nextIndex() + 1);
    }

    private static int repeatMaximum(PlanDefinition definition, String path) {
        if (definition == null) return 0;
        PlanNode node = nodeAt(definition.nodes(), path, "step");
        return node instanceof PlanNode.Repeat repeat ? repeat.maxIterations() : 0;
    }

    private static PlanNode nodeAt(List<PlanNode> nodes, String path, String prefix) {
        for (int i = 0; i < nodes.size(); i++) {
            String candidate = prefix.equals("step")
                    ? prefix + " " + (i + 1)
                    : prefix.endsWith(" → then") || prefix.endsWith(" → else")
                            ? prefix + " #" + (i + 1)
                            : prefix + " → step " + (i + 1);
            PlanNode node = nodes.get(i);
            if (candidate.equals(path)) return node;
            if (node instanceof PlanNode.Conditional conditional) {
                PlanNode found = nodeAt(conditional.thenNodes(), path, candidate + " → then");
                if (found != null) return found;
                found = nodeAt(conditional.elseNodes(), path, candidate + " → else");
                if (found != null) return found;
            } else if (node instanceof PlanNode.Repeat repeat) {
                PlanNode found = nodeAt(repeat.body(), path, candidate + " → repeat");
                if (found != null) return found;
            }
        }
        return null;
    }

    public static String planSummary(JobQueue q, long planId) {
        var jobs = q.jobsOfPlan(planId);
        StringBuilder out = new StringBuilder().append(jobs.size()).append(" steps");
        for (JobState state :
                new JobState[] {JobState.DONE, JobState.FAILED, JobState.SKIPPED, JobState.PENDING, JobState.CANCELLED
                }) {
            long n = jobs.stream().filter(j -> j.state() == state).count();
            if (n > 0)
                out.append(", ").append(n).append(' ').append(state.name().toLowerCase());
        }
        jobs.stream()
                .filter(j -> j.state() == JobState.FAILED)
                .findFirst()
                .ifPresent(j -> out.append("; failed #")
                        .append(j.id())
                        .append(' ')
                        .append(j.tool())
                        .append(": ")
                        .append(truncate(j.result(), MAX_RESULT)));
        return out.toString();
    }

    private static String line(JobQueue q, Job j, String reason) {
        QueueHold hold = j.planId() == 0 ? null : q.hold(j.planId()).orElse(null);
        if (hold == null) hold = q.hold(j.id()).orElse(null);
        String suffix = hold != null
                ? ": " + truncate(hold.describe(q.clock()), MAX_SUMMARY_REASON)
                : reason == null
                        ? (JobQueue.terminal(j) && !j.result().isBlank() ? ": " + truncate(j.result(), MAX_RESULT) : "")
                        : ": " + truncate(reason, MAX_SUMMARY_REASON);
        return "#" + j.id() + " " + j.state().name().toLowerCase() + " " + planPart(q, j) + " " + j.tool() + suffix;
    }

    private static String planPart(JobQueue q, Job j) {
        return j.planId() == 0
                ? "direct"
                : q.plan(j.planId()).map(p -> "plan \"" + p.goal() + "\"").orElse("plan");
    }

    private static String tag(JobQueue q, Job j) {
        return j.planId() == 0
                ? "direct"
                : q.plan(j.planId())
                        .map(p -> p.goal().isBlank() ? "plan " + p.id() : truncate(p.goal(), 40))
                        .orElse("plan");
    }
}
