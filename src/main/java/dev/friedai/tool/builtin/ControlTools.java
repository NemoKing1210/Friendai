package dev.friedai.tool.builtin;

import dev.friedai.entity.CompanionEntity;
import dev.friedai.tool.ToolRegistry;
import dev.friedai.tool.ToolResult;
import java.util.List;

/** Control over what the companion is doing. */
final class ControlTools {
    private ControlTools() {}

    static void register(ToolRegistry<CompanionEntity> registry, List<TaskToolDef> defs) {
        registry.register(ToolKit.instantTool("stop", "Cancel the current task and go back to the default activity.", (c, a) -> {
            c.taskRunner().stop("stopped by the LLM");
            return ToolResult.ok("stopped");
        }));
    }
}
