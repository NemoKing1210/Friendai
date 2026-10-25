package dev.friendai.queue;

import com.google.gson.JsonObject;
import java.util.List;

public record Job(long id, long planId, String tool, JsonObject args, int priority, boolean priorityExplicit,
                  long order, JobState state, List<Guard> guards, GuardAction onUnmet, FailurePolicy onFailure,
                  int maxAttempts, int attempts, List<Long> after, String result, long retryAtTick) {
    public static final String MISSING_TOOL_RESULT = "tool is no longer available";
    public Job {
        if (planId < 0) throw new IllegalArgumentException("planId");
        args = args == null ? new JsonObject() : args.deepCopy();
        guards = guards == null ? List.of() : List.copyOf(guards);
        after = after == null ? List.of() : List.copyOf(after);
        tool = tool == null ? "" : tool;
        result = result == null ? "" : result;
    }
    @Override public JsonObject args() { return args.deepCopy(); }
}
