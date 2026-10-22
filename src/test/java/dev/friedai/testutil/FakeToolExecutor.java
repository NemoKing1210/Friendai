package dev.friedai.testutil;

import com.google.gson.JsonObject;
import dev.friedai.llm.model.ToolCall;
import dev.friedai.llm.model.ToolSpec;
import dev.friedai.tool.ToolExecutor;
import dev.friedai.tool.ToolResult;
import java.util.ArrayList;
import java.util.List;

/** Records executed calls and answers "ok <name>". */
public final class FakeToolExecutor implements ToolExecutor {
    public final List<String> executed = new ArrayList<>();

    @Override
    public List<ToolSpec> specs() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", new JsonObject());
        return List.of(new ToolSpec("stay", "Stay put", schema));
    }

    @Override
    public ToolResult execute(ToolCall call) {
        executed.add(call.name());
        return ToolResult.ok("ok " + call.name());
    }
}
