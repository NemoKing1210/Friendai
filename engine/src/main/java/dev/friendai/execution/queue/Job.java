package dev.friendai.execution.queue;

import com.google.gson.JsonObject;
import dev.friendai.foundation.diagnostics.LogContext;
import java.util.List;
import java.util.UUID;

public record Job(
        long id,
        long planId,
        String tool,
        JsonObject args,
        int priority,
        boolean priorityExplicit,
        long order,
        JobState state,
        List<Guard> guards,
        GuardAction onUnmet,
        FailurePolicy onFailure,
        int maxAttempts,
        int attempts,
        List<Long> after,
        String result,
        long retryAtTick,
        UUID principal,
        boolean creativeSensitive,
        LogContext diagnostic,
        /**
         * The tick the job was queued at, so a `when` guard's clock starts with the work, not with the world.
         * Negative when the baseline is unknown, as in a record written before the queue kept it.
         */
        long queuedTick,
        /** The odometer reading when the job was queued, so a distance guard measures its own travel; negative if unknown. */
        long queuedDistance) {
    public static final String MISSING_TOOL_RESULT = "tool is no longer available";
    /** A saved task whose committed progress cannot be read: repeating it could apply its effects twice. */
    public static final String UNREADABLE_PROGRESS_RESULT = "saved progress could not be read";
    /** A step the owner chose not to run; it is not the same as a step the plan did not need. */
    public static final String SKIP_OWNER = "skipped by the owner";
    /** A step whose guard was confirmed unmet, so the plan never needed it. */
    public static final String SKIP_CONDITION = "condition not met";

    /** Why a step was left out, kept apart because the three mean different things to a dependent and a report. */
    public enum Omission {
        /** The owner skipped it by hand. */
        OWNER,
        /** Its guard was confirmed unmet, so the plan did not need it. */
        NOT_APPLICABLE,
        /** It failed and its own policy let the plan continue past it; the row keeps the failure. */
        CONTINUED
    }

    /** The omission this skipped job records, or null for a job that is not skipped. */
    public static Omission omission(Job job) {
        if (job == null || job.state() != JobState.SKIPPED) return null;
        if (SKIP_OWNER.equals(job.result())) return Omission.OWNER;
        if (SKIP_CONDITION.equals(job.result())) return Omission.NOT_APPLICABLE;
        return Omission.CONTINUED;
    }

    /** True for a cancelled job that records a blocker the owner has to resolve before the work continues. */
    public static boolean needsDecision(Job job) {
        return job != null
                && job.state() == JobState.CANCELLED
                && (MISSING_TOOL_RESULT.equals(job.result()) || UNREADABLE_PROGRESS_RESULT.equals(job.result()));
    }

    public Job(
            long id,
            long planId,
            String tool,
            JsonObject args,
            int priority,
            boolean priorityExplicit,
            long order,
            JobState state,
            List<Guard> guards,
            GuardAction onUnmet,
            FailurePolicy onFailure,
            int maxAttempts,
            int attempts,
            List<Long> after,
            String result,
            long retryAtTick) {
        this(
                id,
                planId,
                tool,
                args,
                priority,
                priorityExplicit,
                order,
                state,
                guards,
                onUnmet,
                onFailure,
                maxAttempts,
                attempts,
                after,
                result,
                retryAtTick,
                null,
                true,
                LogContext.NONE,
                0,
                0);
    }

    public Job(
            long id,
            long planId,
            String tool,
            JsonObject args,
            int priority,
            boolean priorityExplicit,
            long order,
            JobState state,
            List<Guard> guards,
            GuardAction onUnmet,
            FailurePolicy onFailure,
            int maxAttempts,
            int attempts,
            List<Long> after,
            String result,
            long retryAtTick,
            UUID principal) {
        this(
                id,
                planId,
                tool,
                args,
                priority,
                priorityExplicit,
                order,
                state,
                guards,
                onUnmet,
                onFailure,
                maxAttempts,
                attempts,
                after,
                result,
                retryAtTick,
                principal,
                true,
                LogContext.NONE,
                0,
                0);
    }

    /** A loaded job whose queue never stored the tick it was queued at. */
    public Job(
            long id,
            long planId,
            String tool,
            JsonObject args,
            int priority,
            boolean priorityExplicit,
            long order,
            JobState state,
            List<Guard> guards,
            GuardAction onUnmet,
            FailurePolicy onFailure,
            int maxAttempts,
            int attempts,
            List<Long> after,
            String result,
            long retryAtTick,
            UUID principal,
            boolean creativeSensitive,
            long queuedTick,
            long queuedDistance) {
        this(
                id,
                planId,
                tool,
                args,
                priority,
                priorityExplicit,
                order,
                state,
                guards,
                onUnmet,
                onFailure,
                maxAttempts,
                attempts,
                after,
                result,
                retryAtTick,
                principal,
                creativeSensitive,
                LogContext.NONE,
                queuedTick,
                queuedDistance);
    }

    /** The historical shape: a job without captured diagnostic provenance. */
    public Job(
            long id,
            long planId,
            String tool,
            JsonObject args,
            int priority,
            boolean priorityExplicit,
            long order,
            JobState state,
            List<Guard> guards,
            GuardAction onUnmet,
            FailurePolicy onFailure,
            int maxAttempts,
            int attempts,
            List<Long> after,
            String result,
            long retryAtTick,
            UUID principal,
            boolean creativeSensitive) {
        this(
                id,
                planId,
                tool,
                args,
                priority,
                priorityExplicit,
                order,
                state,
                guards,
                onUnmet,
                onFailure,
                maxAttempts,
                attempts,
                after,
                result,
                retryAtTick,
                principal,
                creativeSensitive,
                LogContext.NONE,
                0,
                0);
    }

    /** A copy that carries the invocation this job was created from; the save codec never reads it. */
    public Job withDiagnostic(LogContext context) {
        return new Job(
                id,
                planId,
                tool,
                args,
                priority,
                priorityExplicit,
                order,
                state,
                guards,
                onUnmet,
                onFailure,
                maxAttempts,
                attempts,
                after,
                result,
                retryAtTick,
                principal,
                creativeSensitive,
                context == null ? LogContext.NONE : context,
                queuedTick,
                queuedDistance);
    }

    public Job {
        if (planId < 0) throw new IllegalArgumentException("planId");
        args = args == null ? new JsonObject() : args.deepCopy();
        guards = guards == null ? List.of() : List.copyOf(guards);
        after = after == null ? List.of() : List.copyOf(after);
        tool = tool == null ? "" : tool;
        result = result == null ? "" : result;
        diagnostic = diagnostic == null ? LogContext.NONE : diagnostic;
        queuedTick = Math.max(-1, queuedTick);
        queuedDistance = Math.max(-1, queuedDistance);
    }

    @Override
    public JsonObject args() {
        return args.deepCopy();
    }
}
