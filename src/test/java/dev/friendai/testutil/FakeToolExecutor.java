package dev.friendai.testutil;

import com.google.gson.JsonObject;
import dev.friendai.llm.model.ToolCall;
import dev.friendai.llm.model.ToolSpec;
import dev.friendai.tool.AccessLevel;
import dev.friendai.tool.Caller;
import dev.friendai.tool.ToolExecutor;
import dev.friendai.tool.ToolResult;
import java.util.ArrayList;
import java.util.List;

/** Records executed calls and answers "ok <name>"; also records the access level of each call. */
public final class FakeToolExecutor implements ToolExecutor {
    public final List<String> executed = new ArrayList<>();
    public final List<AccessLevel> specAccess = new ArrayList<>();
    public final List<AccessLevel> executeAccess = new ArrayList<>();
    public final List<Caller> executeCallers = new ArrayList<>();
    public final List<ToolSpec> available = new ArrayList<>(List.of(staySpec()));
    public boolean failSpecs;

    private static ToolSpec staySpec() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", new JsonObject());
        return new ToolSpec("stay", "Stay put", schema);
    }

    @Override
    public List<ToolSpec> specs(AccessLevel access) {
        specAccess.add(access);
        if (failSpecs) {
            throw new IllegalStateException("spec failure");
        }
        return List.copyOf(available);
    }

    @Override
    public ToolResult execute(ToolCall call, Caller caller) {
        executeAccess.add(caller.access());
        executeCallers.add(caller);
        executed.add(call.name());
        return ToolResult.ok("ok " + call.name());
    }
}
