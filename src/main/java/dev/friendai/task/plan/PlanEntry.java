package dev.friendai.task.plan;

import com.google.gson.JsonObject;
import dev.friendai.task.Task;

/**
 * One step of a plan: the tool it came from (for messages), the arguments it was called with (so a reload can
 * rebuild the step), and how to build its task.
 */
public record PlanEntry(String tool, JsonObject args, StepFactory factory) {
    /** A step that cannot be saved, for tests and steps built without arguments. */
    public PlanEntry(String tool, StepFactory factory) {
        this(tool, null, factory);
    }
}
