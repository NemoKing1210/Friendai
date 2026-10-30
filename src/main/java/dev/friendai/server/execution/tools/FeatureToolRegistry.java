package dev.friendai.server.execution.tools;

import dev.friendai.agent.tools.ToolMeta;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.execution.tool.Tool;
import dev.friendai.execution.tool.ToolRegistry;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * Registration boundary passed to feature contributors. It keeps task factories and catalog metadata beside their
 * runtime tools while the bootstrap bridges task definitions into the current restore and plannable registries.
 */
public final class FeatureToolRegistry {
    private final ToolRegistry<CompanionEntity> tools;
    private final Function<TaskToolDefinition, Tool<CompanionEntity>> taskToolFactory;
    private final Map<String, TaskToolDefinition> taskDefinitions = new LinkedHashMap<>();
    private final Map<String, ToolMeta> catalogMetadata = new LinkedHashMap<>();

    public FeatureToolRegistry(
            ToolRegistry<CompanionEntity> tools, Function<TaskToolDefinition, Tool<CompanionEntity>> taskToolFactory) {
        this.tools = Objects.requireNonNull(tools);
        this.taskToolFactory = Objects.requireNonNull(taskToolFactory);
    }

    public void registerTask(TaskToolDefinition definition) {
        Objects.requireNonNull(definition);
        ensureNameAvailable(definition.name());
        if (definition.checkpointType() != null
                && taskDefinitions.values().stream()
                        .anyMatch(existing -> definition.checkpointType().equals(existing.checkpointType())))
            throw new IllegalArgumentException("checkpoint type already contributed: " + definition.checkpointType());
        Tool<CompanionEntity> tool = Objects.requireNonNull(taskToolFactory.apply(definition));
        if (!definition.name().equals(tool.name())) {
            throw new IllegalArgumentException("task tool factory returned a different name for " + definition.name());
        }
        tools.register(tool);
        taskDefinitions.put(definition.name(), definition);
        catalogMetadata.put(definition.name(), definition.catalogMeta());
    }

    public void registerTool(Tool<CompanionEntity> tool, ToolMeta metadata) {
        Objects.requireNonNull(tool);
        Objects.requireNonNull(metadata);
        if (!tool.name().equals(metadata.name())) {
            throw new IllegalArgumentException("tool and catalog names must match: " + tool.name());
        }
        ensureNameAvailable(tool.name());
        tools.register(tool);
        catalogMetadata.put(tool.name(), metadata);
    }

    public Map<String, TaskToolDefinition> taskDefinitions() {
        return Map.copyOf(taskDefinitions);
    }

    public List<ToolMeta> catalogMetadata() {
        return List.copyOf(catalogMetadata.values());
    }

    /** Checkpoint decoders contributed with their task definitions, keyed by the stable persisted task type. */
    public Map<String, CheckpointRestorer> checkpointRestorers() {
        Map<String, CheckpointRestorer> restorers = new LinkedHashMap<>();
        taskDefinitions.forEach((name, definition) -> {
            if (definition.checkpointRestorer() != null)
                restorers.put(definition.checkpointType(), definition.checkpointRestorer());
        });
        return Map.copyOf(restorers);
    }

    public Set<String> restorableTaskNames() {
        Set<String> names = new LinkedHashSet<>();
        taskDefinitions.forEach((name, definition) -> {
            if (definition.restorable()) names.add(name);
        });
        return Set.copyOf(names);
    }

    private void ensureNameAvailable(String name) {
        if (catalogMetadata.containsKey(name) || taskDefinitions.containsKey(name)) {
            throw new IllegalArgumentException("Tool already registered: " + name);
        }
    }
}
