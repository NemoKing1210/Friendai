package dev.friedai.tool.builtin;

import com.google.gson.JsonObject;
import dev.friedai.entity.CompanionEntity;
import dev.friedai.task.Task;
import dev.friedai.task.inventory.ItemMatcher;
import dev.friedai.task.mining.BlockMatcher;
import dev.friedai.tool.AccessLevel;
import dev.friedai.tool.SchemaBuilder;
import dev.friedai.tool.Tool;
import dev.friedai.tool.ToolRegistry;
import dev.friedai.tool.ToolResult;
import java.util.List;

/** Helpers shared by the tool groups: turning a definition into a {@link Tool} and parsing item/block arguments. */
final class ToolKit {
    private ToolKit() {}

    /** Registers a task tool and contributes its definition, so {@code run_plan} can reuse its schema and factory. */
    static void add(ToolRegistry<CompanionEntity> registry, List<TaskToolDef> defs, TaskToolDef def) {
        defs.add(def);
        registry.register(taskTool(def));
    }

    static Tool<CompanionEntity> taskTool(TaskToolDef def) {
        return tool(def.name(), def.description(), def.schema(), AccessLevel.TRUSTED, (c, args) -> {
            Task task;
            try {
                task = def.factory().create(c, args);
            } catch (IllegalArgumentException e) {
                return ToolResult.error(e.getMessage());
            }
            int id = c.taskRunner().start(task, def.name());
            return ToolResult.ok("task #" + id + " started: " + task.describe());
        });
    }

    static Tool<CompanionEntity> instantTool(String name, String description, JsonObject schema, Action action) {
        return tool(name, description, schema, AccessLevel.TRUSTED, action);
    }

    static Tool<CompanionEntity> instantTool(String name, String description, Action action) {
        return tool(name, description, SchemaBuilder.object().build(), AccessLevel.TRUSTED, action);
    }

    static Tool<CompanionEntity> instantTool(
            String name, String description, AccessLevel minAccess, Action action) {
        return tool(name, description, SchemaBuilder.object().build(), minAccess, action);
    }

    static ItemMatcher parseItemMatcher(String idOrTag) {
        return ItemMatcher.parse(idOrTag)
                .orElseThrow(() -> new IllegalArgumentException("unknown item or tag: " + idOrTag));
    }

    static BlockMatcher parseBlockMatcher(String idOrTag) {
        return BlockMatcher.parse(idOrTag)
                .orElseThrow(() -> new IllegalArgumentException("unknown block or tag: " + idOrTag));
    }

    private static Tool<CompanionEntity> tool(
            String name, String description, JsonObject schema, AccessLevel minAccess, Action action) {
        return new Tool<>() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public AccessLevel minAccess() {
                return minAccess;
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
            public ToolResult execute(CompanionEntity ctx, JsonObject args) {
                return action.run(ctx, args);
            }
        };
    }

    @FunctionalInterface
    interface Action {
        ToolResult run(CompanionEntity companion, JsonObject args);
    }
}
