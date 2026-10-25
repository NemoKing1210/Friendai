package dev.friendai.queue;

import com.google.gson.JsonObject;
import java.util.List;

public record QueuedStep(String tool, JsonObject args, Integer priority, List<Guard> guards,
                         GuardAction onUnmet, FailurePolicy onFailure, int maxAttempts,
                         List<Integer> afterSteps) {
    public QueuedStep {
        tool = tool == null ? "" : tool;
        args = args == null ? new JsonObject() : args.deepCopy();
        guards = guards == null ? List.of() : List.copyOf(guards);
        onUnmet = onUnmet == null ? GuardAction.WAIT : onUnmet;
        maxAttempts = Math.clamp(maxAttempts, 1, 3);
        afterSteps = afterSteps == null ? List.of() : List.copyOf(afterSteps);
    }
    @Override public JsonObject args() { return args.deepCopy(); }
}
