package dev.friendai.queue;

import java.util.List;

/** Compact, bounded LLM-facing queue rendering. */
public final class QueueText {
    public static final int MAX_LINE_ARGS = 160, MAX_RESULT = 200, MAX_SHOW_CHARS = 4096,
            MAX_SUMMARY_REASON = 160, MAX_BLOCKED_REASON = 300;
    private QueueText() {}
    public static String truncate(String text, int max) { if (text == null) return ""; return text.length() <= max ? text : text.substring(0, max) + "…"; }
    public static String show(JobQueue q, GuardFacts facts, Long planId, int limit, boolean includeDone) {
        var snapshot = QueuePolicy.snapshot(q, facts);
        if (q.jobCount() == 0) return q.archives().isEmpty() ? "queue: empty" : "queue: no jobs; " + q.archives().size() + " archived plan(s)";
        StringBuilder out = new StringBuilder("queue: ").append(snapshot.running().size()).append(" running, ").append(snapshot.pending()).append(" pending; plans: ").append(q.planCount());
        List<Job> jobs = q.jobs().stream().filter(j -> planId == null || j.planId() == planId).toList(); int shown = 0;
        for (Job j : jobs) { if (!includeDone && JobQueue.terminal(j) && j.state() != JobState.FAILED) continue; if (shown++ >= limit) break; out.append('\n').append(line(q, j, snapshot.waitingReasons().get(j.id()))); }
        return truncate(out.toString(), MAX_SHOW_CHARS);
    }
    public static String summary(JobQueue q, GuardFacts facts, String runningDescription) {
        if (q.jobCount() == 0) return "Task queue: empty";
        var s = QueuePolicy.snapshot(q, facts); StringBuilder out = new StringBuilder("Task queue: ").append(s.running().size()).append(" running, ").append(s.pending()).append(" pending");
        if (!s.waiting().isEmpty()) out.append(", ").append(s.waiting().size()).append(" blocked"); out.append("; plans: ").append(q.planCount());
        if (!s.running().isEmpty()) { Job j = s.running().getFirst(); out.append("\nRunning: #").append(j.id()).append(' ').append(planPart(q, j)).append(": ").append(truncate(runningDescription == null ? j.tool() : runningDescription, MAX_LINE_ARGS)); }
        if (!s.ready().isEmpty()) { out.append("\nNext: "); for (int i=0;i<Math.min(2,s.ready().size());i++) { if(i>0) out.append("; "); Job j=s.ready().get(i); out.append('#').append(j.id()).append(" (").append(tag(q,j)).append(") ").append(j.tool()); } }
        if (!s.waiting().isEmpty()) { Job j=s.waiting().getFirst(); out.append("\nBlocked: #").append(j.id()).append(' ').append(planPart(q,j)).append(' ').append(j.tool()).append(": ").append(truncate(QueuePolicy.summaryReason(q,j,facts),MAX_SUMMARY_REASON)); }
        return truncate(out.toString(), 1200);
    }
    public static String planSummary(JobQueue q, long planId) { var jobs=q.jobsOfPlan(planId); long done=jobs.stream().filter(j->j.state()==JobState.DONE).count(), failed=jobs.stream().filter(j->j.state()==JobState.FAILED).count(), pending=jobs.stream().filter(j->j.state()==JobState.PENDING).count(); return jobs.size()+" steps, "+done+" done, "+failed+" failed, "+pending+" pending"; }
    private static String line(JobQueue q, Job j, String reason) { String suffix=reason==null? (JobQueue.terminal(j)&&!j.result().isBlank()?": "+truncate(j.result(),MAX_RESULT):"") : ": "+truncate(reason,MAX_SUMMARY_REASON); return "#"+j.id()+" "+j.state().name().toLowerCase()+" "+planPart(q,j)+" "+j.tool()+suffix; }
    private static String planPart(JobQueue q, Job j) { return j.planId()==0?"direct":q.plan(j.planId()).map(p->"plan \""+p.goal()+"\"").orElse("plan"); }
    private static String tag(JobQueue q, Job j) { return j.planId()==0?"direct":q.plan(j.planId()).map(p->p.goal().isBlank()?"plan "+p.id():truncate(p.goal(),40)).orElse("plan"); }
}
