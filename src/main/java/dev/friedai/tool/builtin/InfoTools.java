package dev.friedai.tool.builtin;

import dev.friedai.entity.CompanionEntity;
import dev.friedai.perception.PerceptionBuilder;
import dev.friedai.tool.ToolRegistry;
import dev.friedai.tool.ToolResult;
import java.util.List;

/** Looking at the world: descriptions the LLM uses to plan. */
final class InfoTools {
    private InfoTools() {}

    static void register(ToolRegistry<CompanionEntity> registry, List<TaskToolDef> defs) {
        registry.register(ToolKit.instantTool("look_around", "Describe the surroundings in detail.",
                (c, a) -> ToolResult.ok(PerceptionBuilder.detailed(c).toPromptText())));
    }
}
