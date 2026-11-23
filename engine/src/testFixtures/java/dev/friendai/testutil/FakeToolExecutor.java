package dev.friendai.testutil;

import com.google.gson.JsonObject;
import dev.friendai.agent.eval.OutcomeReview;
import dev.friendai.agent.llm.model.ToolCall;
import dev.friendai.agent.llm.model.ToolSpec;
import dev.friendai.execution.tool.AccessLevel;
import dev.friendai.execution.tool.Caller;
import dev.friendai.execution.tool.ToolExecutor;
import dev.friendai.execution.tool.ToolResult;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Records executed calls and answers "ok <name>"; also records the access level of each call. */
public final class FakeToolExecutor implements ToolExecutor {
    public final List<String> executed = new ArrayList<>();
    public final List<AccessLevel> specAccess = new ArrayList<>();
    public final List<AccessLevel> executeAccess = new ArrayList<>();
    public final List<Caller> executeCallers = new ArrayList<>();
    public final List<OutcomeReview.Observation> observations = new ArrayList<>();
    /** Tool calls that had no scripted answer and therefore received the fake default success. */
    public final List<String> unscripted = new ArrayList<>();

    public final List<ToolSpec> available = new ArrayList<>(List.of(staySpec()));
    /** Scripted answers per tool, consumed in order before the default {@code ok <name>}. */
    public final Map<String, Deque<ToolResult>> results = new HashMap<>();

    public boolean failSpecs;

    /**
     * Lists a minimal schema for each named tool, as a real registry lists what a caller may use. A call to a tool
     * whose schema this does not include is refused by the brain before it reaches {@link #execute}.
     */
    public FakeToolExecutor offers(String... names) {
        for (String name : names) {
            JsonObject schema = new JsonObject();
            schema.addProperty("type", "object");
            schema.add("properties", new JsonObject());
            available.add(new ToolSpec(name, name, schema));
        }
        return this;
    }

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
        Deque<ToolResult> scripted = results.get(call.name());
        boolean unscriptedCall = scripted == null || scripted.isEmpty();
        if (unscriptedCall) {
            unscripted.add(call.name());
        }
        ToolResult result = unscriptedCall ? ToolResult.ok("ok " + call.name()) : scripted.removeFirst();
        observations.add(new OutcomeReview.Observation(
                call.name(), OutcomeReview.targetKey(call.arguments()), !result.error(), result.content()));
        return result;
    }
}
