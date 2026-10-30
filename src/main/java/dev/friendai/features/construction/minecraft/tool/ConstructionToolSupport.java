package dev.friendai.features.construction.minecraft.tool;

import com.google.gson.JsonObject;
import dev.friendai.agent.tools.Cost;
import dev.friendai.agent.tools.ToolGroup;
import dev.friendai.agent.tools.ToolMeta;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.execution.tool.Tool;
import dev.friendai.execution.tool.ToolResult;
import dev.friendai.server.execution.tools.FeatureToolRegistry;
import dev.friendai.server.execution.tools.TaskToolDefinition;
import java.util.List;

/** Construction-owned factories for task and instant tool definitions. */
final class ConstructionToolSupport {
    private ConstructionToolSupport() {}

    static void registerTask(
            FeatureToolRegistry registry,
            ToolGroup group,
            String name,
            String description,
            JsonObject schema,
            TaskToolDefinition.Factory factory,
            boolean plannable) {
        registerTask(registry, group, name, description, schema, factory, plannable, null, null);
    }

    static void registerTask(
            FeatureToolRegistry registry,
            ToolGroup group,
            String name,
            String description,
            JsonObject schema,
            TaskToolDefinition.Factory factory,
            boolean plannable,
            String checkpointType,
            dev.friendai.server.execution.tools.CheckpointRestorer restorer) {
        TaskToolDefinition definition = new TaskToolDefinition(
                name,
                description,
                schema,
                factory,
                plannable,
                TaskToolDefinition.QueueTraits.DEFAULT,
                metadata(group, name));
        registry.registerTask(restorer == null ? definition : definition.withCheckpoint(checkpointType, restorer));
    }

    static void registerTool(
            FeatureToolRegistry registry,
            ToolGroup group,
            String name,
            String description,
            JsonObject schema,
            Action action) {
        registry.registerTool(
                new Tool<>() {
                    @Override
                    public String name() {
                        return name;
                    }

                    @Override
                    public String description() {
                        return description;
                    }

                    @Override
                    public JsonObject schema() {
                        return schema;
                    }

                    @Override
                    public ToolResult execute(CompanionEntity companion, JsonObject args) {
                        return action.execute(companion, args);
                    }
                },
                metadata(group, name));
    }

    private static ToolMeta metadata(ToolGroup group, String name) {
        Cost cost = group == ToolGroup.BUILDING ? Cost.MEDIUM : Cost.HIGH;
        String hint = switch (name) {
            case "build_structure" ->
                "- For buildings, prefer a generator for standard shapes, call blueprint_help for the format, and preview_structure before build_structure. preview_structure reports the parts (floor, walls, roof, entrance, window, interior); pass parts to build only some of them. A failed build stays half built at its origin: call build_structure again without coordinates to resume it there instead of choosing a new place.";
            case "clear_area" ->
                "- Use clear_area to empty a plot before building: it clears vegetation and snow by default, takes water sources when it carries an empty bucket, and breaks solid blocks only with blocks=true. Chests, lava and protected places are never touched.";
            default -> "";
        };
        return new ToolMeta(name, group, false, hint, List.of(), cost);
    }

    @FunctionalInterface
    interface Action {
        ToolResult execute(CompanionEntity companion, JsonObject args);
    }
}
