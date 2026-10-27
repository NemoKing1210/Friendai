package dev.friendai.queue;

import com.google.gson.JsonObject;
import java.util.List;
import java.util.UUID;

public record QueuedStep(String tool, JsonObject args, Integer priority, List<Guard> guards,
                         GuardAction onUnmet, FailurePolicy onFailure, int maxAttempts,
                         List<Integer> afterSteps, UUID principal, boolean creativeSensitive) {
    public QueuedStep(String tool, JsonObject args, Integer priority, List<Guard> guards,
                      GuardAction onUnmet, FailurePolicy onFailure, int maxAttempts,
                      List<Integer> afterSteps) {
        this(tool, args, priority, guards, onUnmet, onFailure, maxAttempts, afterSteps, null, true);
    }
    public QueuedStep(String tool, JsonObject args, Integer priority, List<Guard> guards,
                      GuardAction onUnmet, FailurePolicy onFailure, int maxAttempts,
                      List<Integer> afterSteps, UUID principal) {
        this(tool, args, priority, guards, onUnmet, onFailure, maxAttempts, afterSteps, principal, true);
    }
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
