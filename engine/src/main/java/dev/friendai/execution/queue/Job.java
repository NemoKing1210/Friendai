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
        LogContext diagnostic) {
    public static final String MISSING_TOOL_RESULT = "tool is no longer available";
    /** A saved task whose committed progress cannot be read: repeating it could apply its effects twice. */
    public static final String UNREADABLE_PROGRESS_RESULT = "saved progress could not be read";

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
                LogContext.NONE);
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
                LogContext.NONE);
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
                LogContext.NONE);
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
                context == null ? LogContext.NONE : context);
    }

    public Job {
        if (planId < 0) throw new IllegalArgumentException("planId");
        args = args == null ? new JsonObject() : args.deepCopy();
        guards = guards == null ? List.of() : List.copyOf(guards);
        after = after == null ? List.of() : List.copyOf(after);
        tool = tool == null ? "" : tool;
        result = result == null ? "" : result;
        diagnostic = diagnostic == null ? LogContext.NONE : diagnostic;
    }

    @Override
    public JsonObject args() {
        return args.deepCopy();
    }
}
