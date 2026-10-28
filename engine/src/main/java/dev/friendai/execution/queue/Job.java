package dev.friendai.execution.queue;

import com.google.gson.JsonObject;
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
        boolean creativeSensitive) {
    public static final String MISSING_TOOL_RESULT = "tool is no longer available";

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
                true);
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
                true);
    }

    public Job {
        if (planId < 0) throw new IllegalArgumentException("planId");
        args = args == null ? new JsonObject() : args.deepCopy();
        guards = guards == null ? List.of() : List.copyOf(guards);
        after = after == null ? List.of() : List.copyOf(after);
        tool = tool == null ? "" : tool;
        result = result == null ? "" : result;
    }

    @Override
    public JsonObject args() {
        return args.deepCopy();
    }
}
