package dev.friendai.tool.builtin;

import dev.friendai.agent.tools.ToolGroup;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.tool.SchemaBuilder;
import dev.friendai.tool.ToolRegistry;
import dev.friendai.tool.ToolResult;
import java.util.Arrays;
import java.util.Locale;

/**
 * {@code request_tools}: lets the model unlock a family of tools the turn did not start with. The brain handles the
 * call itself (it owns the per-turn visibility); this registration only puts the tool in the specs and keeps a manual
 * run harmless.
 */
final class RoutingTools {
    private RoutingTools() {}

    static void register(ToolRegistry<CompanionEntity> registry) {
        String[] groups = Arrays.stream(ToolGroup.values())
                .filter(g -> g != ToolGroup.CORE)
                .map(g -> g.name().toLowerCase(Locale.ROOT))
                .toArray(String[]::new);
        registry.register(ToolKit.instantTool(
                "request_tools",
                "Unlock another family of tools for the rest of this turn when the ones you can see do not cover "
                        + "the request. Name the group, for example gathering, crafting, farming or blueprint.",
                SchemaBuilder.object()
                        .enumeration("group", "The tool family to unlock", groups)
                        .required("group")
                        .build(),
                (c, args) -> ToolResult.ok("tools are unlocked by the brain during a turn")));
    }
}
