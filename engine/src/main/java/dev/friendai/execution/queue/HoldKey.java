package dev.friendai.execution.queue;

/**
 * The target of one local hold: a plan or a job, named explicitly.
 *
 * <p>Plans and jobs keep separate counters that both start at one, so a bare number cannot say which of the two a
 * pause, deferral or resume means. Every caller that already knows its target keeps that scope, and persistence writes
 * it beside the id.
 */
public record HoldKey(Scope scope, long id) {
    public enum Scope {
        PLAN,
        JOB
    }

    public HoldKey {
        if (scope == null) throw new IllegalArgumentException("a hold needs a scope");
        if (id <= 0) throw new IllegalArgumentException("a hold needs a positive id");
    }

    public static HoldKey plan(long id) {
        return new HoldKey(Scope.PLAN, id);
    }

    public static HoldKey job(long id) {
        return new HoldKey(Scope.JOB, id);
    }

    public boolean isPlan() {
        return scope == Scope.PLAN;
    }

    /** One bounded phrase for text that names the held target. */
    public String describe() {
        return (isPlan() ? "plan #" : "job #") + id;
    }
}
