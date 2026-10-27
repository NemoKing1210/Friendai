package dev.friendai.tool.builtin;

import com.google.gson.JsonObject;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.task.Task;
import dev.friendai.tool.AccessLevel;
import dev.friendai.tool.Caller;

/**
 * A built-in skill backed by a task: its LLM-facing metadata plus how to build the task for a call.
 *
 * @param plannable whether {@code queue_add} may use this tool as a step (tasks that finish on their own and
 *                  whose result the LLM can wait for)
 * @param minAccess the lowest access level whose turn may see and run this tool
 */
public record TaskToolDef(
        String name,
        String description,
        JsonObject schema,
        Factory factory,
        boolean plannable,
        AccessLevel minAccess,
        QueueTraits queueTraits,
        CallerFactory callerFactory) {
    public record QueueTraits(boolean interrupts, String mergeableCountArg, boolean requiresTradingScope) {
        public QueueTraits(boolean interrupts, String mergeableCountArg) {
            this(interrupts, mergeableCountArg, false);
        }
        public static final QueueTraits DEFAULT = new QueueTraits(false, null, false);
    }
    public TaskToolDef(String name, String description, JsonObject schema, Factory factory, boolean plannable,
                       AccessLevel minAccess, QueueTraits queueTraits) {
        this(name, description, schema, factory, plannable, minAccess, queueTraits, null);
    }
    public TaskToolDef(String name, String description, JsonObject schema, Factory factory, boolean plannable, AccessLevel minAccess) {
        this(name, description, schema, factory, plannable, minAccess, QueueTraits.DEFAULT, null);
    }
    /** A task tool available to trusted speakers. */
    public TaskToolDef(String name, String description, JsonObject schema, Factory factory, boolean plannable) {
        this(name, description, schema, factory, plannable, AccessLevel.TRUSTED, QueueTraits.DEFAULT, null);
    }
    public TaskToolDef(String name, String description, JsonObject schema, Factory factory, boolean plannable, QueueTraits queueTraits) {
        this(name, description, schema, factory, plannable, AccessLevel.TRUSTED, queueTraits, null);
    }

    @FunctionalInterface
    public interface Factory {
        Task create(CompanionEntity companion, JsonObject args);
    }

    @FunctionalInterface
    public interface CallerFactory {
        Task create(CompanionEntity companion, Caller caller, JsonObject args);
    }
}
