package dev.friedai.tool;

import com.google.gson.JsonObject;
import dev.friedai.llm.model.ToolCall;
import dev.friedai.llm.model.ToolSpec;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ToolRegistry<C> {
    private final Map<String, Tool<C>> tools = new LinkedHashMap<>();

    public ToolRegistry<C> register(Tool<C> tool) {
        tools.put(tool.name(), tool);
        return this;
    }

    /** Every registered tool, regardless of who is asking. */
    public List<ToolSpec> specs() {
        return tools.values().stream().map(ToolRegistry::spec).toList();
    }

    /** Only the tools a caller at {@code access} may use, in registration order. */
    public List<ToolSpec> specs(AccessLevel access) {
        return tools.values().stream()
                .filter(t -> access.allows(t.minAccess()))
                .map(ToolRegistry::spec)
                .toList();
    }

    private static <C> ToolSpec spec(Tool<C> tool) {
        return new ToolSpec(tool.name(), tool.description(), tool.schema());
    }

    public ToolExecutor bind(C ctx) {
        return new ToolExecutor() {
            @Override
            public List<ToolSpec> specs(AccessLevel access) {
                return ToolRegistry.this.specs(access);
            }

            @Override
            public ToolResult execute(ToolCall call, AccessLevel access) {
                return run(ctx, call, access);
            }
        };
    }

    private ToolResult run(C ctx, ToolCall call, AccessLevel access) {
        Tool<C> tool = tools.get(call.name());
        if (tool == null) {
            return ToolResult.error("Unknown tool: " + call.name());
        }
        if (!access.allows(tool.minAccess())) {
            return ToolResult.error(
                    "Tool " + call.name() + " is not available to this speaker (" + access.label() + ").");
        }
        JsonObject args = call.arguments();
        if (args == null) {
            return ToolResult.error("Tool " + call.name() + " got invalid JSON arguments: " + call.rawArguments());
        }
        List<String> problems = ArgValidator.validate(tool.schema(), args);
        if (!problems.isEmpty()) {
            return ToolResult.error("Invalid arguments for " + call.name() + ": " + String.join("; ", problems));
        }
        try {
            return tool.execute(ctx, args);
        } catch (RuntimeException e) {
            return ToolResult.error("Tool " + call.name() + " failed: " + e.getMessage());
        }
    }
}
