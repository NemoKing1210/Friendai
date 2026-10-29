package dev.friendai.server.agent.tools;

import dev.friendai.agent.tools.Cost;
import dev.friendai.agent.tools.ToolGroup;
import dev.friendai.agent.tools.ToolMeta;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.execution.tool.SchemaBuilder;
import dev.friendai.execution.tool.Tool;
import dev.friendai.execution.tool.ToolResult;
import dev.friendai.server.execution.tools.FeatureToolContributor;
import dev.friendai.server.execution.tools.FeatureToolRegistry;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** Registers the tool that asks the agent runtime to reveal another tool family for the current turn. */
public final class RoutingTools implements FeatureToolContributor {
    private static final String NAME = "request_tools";

    @Override
    public void register(FeatureToolRegistry registry) {
        String[] groups = Arrays.stream(ToolGroup.values())
                .filter(group -> group != ToolGroup.CORE)
                .map(group -> group.name().toLowerCase(Locale.ROOT))
                .toArray(String[]::new);
        ToolMeta metadata = new ToolMeta(NAME, ToolGroup.CORE, true, "", List.of(), Cost.LOW);
        registry.registerTool(new RequestTools(groups), metadata);
    }

    private static final class RequestTools implements Tool<CompanionEntity> {
        private final String[] groups;

        private RequestTools(String[] groups) {
            this.groups = groups;
        }

        @Override
        public String name() {
            return NAME;
        }

        @Override
        public String description() {
            return "Unlock another family of tools for the rest of this turn when the ones you can see do not cover "
                    + "the request. Name the group, for example gathering, crafting, farming or blueprint.";
        }

        @Override
        public com.google.gson.JsonObject schema() {
            return SchemaBuilder.object()
                    .enumeration("group", "The tool family to unlock", groups)
                    .required("group")
                    .build();
        }

        @Override
        public ToolResult execute(CompanionEntity companion, com.google.gson.JsonObject args) {
            return ToolResult.ok("tools are unlocked by the brain during a turn");
        }
    }
}
