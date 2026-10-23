package dev.friendai.tool.builtin;

import com.google.gson.JsonObject;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.task.Task;
import dev.friendai.tool.AccessLevel;

/**
 * A built-in skill backed by a task: its LLM-facing metadata plus how to build the task for a call.
 *
 * @param plannable whether {@code run_plan} may use this tool as a step (tasks that finish on their own and
 *                  whose result the LLM can wait for)
 * @param minAccess the lowest access level whose turn may see and run this tool
 */
public record TaskToolDef(
        String name,
        String description,
        JsonObject schema,
        Factory factory,
        boolean plannable,
        AccessLevel minAccess) {
    /** A task tool available to trusted speakers. */
    public TaskToolDef(String name, String description, JsonObject schema, Factory factory, boolean plannable) {
        this(name, description, schema, factory, plannable, AccessLevel.TRUSTED);
    }

    @FunctionalInterface
    public interface Factory {
        Task create(CompanionEntity companion, JsonObject args);
    }
}
