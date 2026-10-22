package dev.friedai.tool.builtin;

import com.google.gson.JsonObject;
import dev.friedai.entity.CompanionEntity;
import dev.friedai.task.Task;

/**
 * A built-in skill backed by a task: its LLM-facing metadata plus how to build the task for a call.
 *
 * @param plannable whether {@code run_plan} may use this tool as a step (tasks that finish on their own and
 *                  whose result the LLM can wait for)
 */
public record TaskToolDef(String name, String description, JsonObject schema, Factory factory, boolean plannable) {
    @FunctionalInterface
    public interface Factory {
        Task create(CompanionEntity companion, JsonObject args);
    }
}
