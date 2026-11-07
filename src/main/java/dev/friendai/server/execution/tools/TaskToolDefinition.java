package dev.friendai.server.execution.tools;

import com.google.gson.JsonObject;
import dev.friendai.agent.tools.ToolMeta;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.execution.tool.AccessLevel;
import dev.friendai.execution.tool.Caller;
import dev.friendai.task.Task;
import java.util.Objects;
import java.util.function.UnaryOperator;

/** Runtime, task-factory, restore and catalog metadata contributed for one task-backed tool. */
public record TaskToolDefinition(
        String name,
        String description,
        JsonObject schema,
        Factory factory,
        CallerFactory callerFactory,
        boolean plannable,
        AccessLevel minAccess,
        QueueTraits queueTraits,
        boolean restorable,
        boolean creativeSensitive,
        ToolMeta catalogMeta,
        String checkpointType,
        CheckpointRestorer checkpointRestorer,
        UnaryOperator<JsonObject> normalizer) {
    public TaskToolDefinition(
            String name,
            String description,
            JsonObject schema,
            Factory factory,
            boolean plannable,
            QueueTraits queueTraits,
            ToolMeta catalogMeta) {
        this(
                name,
                description,
                schema,
                factory,
                null,
                plannable,
                AccessLevel.TRUSTED,
                queueTraits,
                true,
                true,
                catalogMeta,
                null,
                null,
                null);
    }

    public TaskToolDefinition(
            String name,
            String description,
            JsonObject schema,
            Factory factory,
            boolean plannable,
            ToolMeta catalogMeta) {
        this(name, description, schema, factory, plannable, QueueTraits.DEFAULT, catalogMeta);
    }

    public TaskToolDefinition {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("task tool name is required");
        if (description == null || description.isBlank())
            throw new IllegalArgumentException("task tool description is required");
        Objects.requireNonNull(schema, "task tool schema");
        if (factory == null && callerFactory == null)
            throw new IllegalArgumentException("task tool requires a task factory");
        Objects.requireNonNull(minAccess, "task tool minimum access");
        Objects.requireNonNull(queueTraits, "task tool queue traits");
        Objects.requireNonNull(catalogMeta, "task tool catalog metadata");
        if (!name.equals(catalogMeta.name()))
            throw new IllegalArgumentException("task tool and catalog names must match: " + name);
        if (checkpointRestorer == null) {
            if (checkpointType != null && !checkpointType.isBlank())
                throw new IllegalArgumentException("checkpoint type requires a restorer: " + name);
            checkpointType = null;
        } else if (checkpointType == null || checkpointType.isBlank()) {
            throw new IllegalArgumentException("checkpoint restorer requires a stable type: " + name);
        }
        if (checkpointRestorer != null && !restorable)
            throw new IllegalArgumentException("a checkpoint restorer requires a restorable task: " + name);
        if (normalizer == null) normalizer = args -> args;
    }

    /** The legacy shape without a contributed checkpoint; restorable tasks add one with {@link #withCheckpoint}. */
    public TaskToolDefinition(
            String name,
            String description,
            JsonObject schema,
            Factory factory,
            CallerFactory callerFactory,
            boolean plannable,
            AccessLevel minAccess,
            QueueTraits queueTraits,
            boolean restorable,
            boolean creativeSensitive,
            ToolMeta catalogMeta) {
        this(
                name,
                description,
                schema,
                factory,
                callerFactory,
                plannable,
                minAccess,
                queueTraits,
                restorable,
                creativeSensitive,
                catalogMeta,
                null,
                null,
                null);
    }

    /** Adds or replaces the contributed checkpoint decoder for this task. */
    public TaskToolDefinition withCheckpoint(String type, CheckpointRestorer restorer) {
        return new TaskToolDefinition(
                name,
                description,
                schema,
                factory,
                callerFactory,
                plannable,
                minAccess,
                queueTraits,
                restorable,
                creativeSensitive,
                catalogMeta,
                type,
                restorer,
                normalizer);
    }

    /**
     * Declares the documented argument aliases this tool accepts. The runtime applies the mapping before validation,
     * so an alias must produce the canonical schema shape rather than bypassing it.
     */
    public TaskToolDefinition withNormalizer(UnaryOperator<JsonObject> mapper) {
        return new TaskToolDefinition(
                name,
                description,
                schema,
                factory,
                callerFactory,
                plannable,
                minAccess,
                queueTraits,
                restorable,
                creativeSensitive,
                catalogMeta,
                checkpointType,
                checkpointRestorer,
                mapper);
    }

    public TaskToolDefinition asCreativeSafe() {
        return new TaskToolDefinition(
                name,
                description,
                schema,
                factory,
                callerFactory,
                plannable,
                minAccess,
                queueTraits,
                restorable,
                false,
                catalogMeta,
                checkpointType,
                checkpointRestorer,
                normalizer);
    }

    @FunctionalInterface
    public interface Factory {
        Task create(CompanionEntity companion, JsonObject args);
    }

    @FunctionalInterface
    public interface CallerFactory {
        Task create(CompanionEntity companion, Caller caller, JsonObject args);
    }

    public record QueueTraits(boolean interrupts, String mergeableCountArg, boolean requiresTradingScope) {
        public static final QueueTraits DEFAULT = new QueueTraits(false, null, false);

        public QueueTraits(boolean interrupts, String mergeableCountArg) {
            this(interrupts, mergeableCountArg, false);
        }
    }
}
