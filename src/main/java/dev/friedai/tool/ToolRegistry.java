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

    public List<ToolSpec> specs() {
        return tools.values().stream()
                .map(t -> new ToolSpec(t.name(), t.description(), t.schema()))
                .toList();
    }

    public ToolExecutor bind(C ctx) {
        return new ToolExecutor() {
            @Override
            public List<ToolSpec> specs() {
                return ToolRegistry.this.specs();
            }

            @Override
            public ToolResult execute(ToolCall call) {
                return run(ctx, call);
            }
        };
    }

    private ToolResult run(C ctx, ToolCall call) {
        Tool<C> tool = tools.get(call.name());
        if (tool == null) {
            return ToolResult.error("Unknown tool: " + call.name());
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
