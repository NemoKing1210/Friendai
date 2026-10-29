package dev.friendai.server.agent.tools;

import com.google.gson.JsonObject;
import dev.friendai.agent.tools.Cost;
import dev.friendai.agent.tools.ToolGroup;
import dev.friendai.agent.tools.ToolMeta;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.execution.tool.AccessLevel;
import dev.friendai.execution.tool.Caller;
import dev.friendai.execution.tool.SchemaBuilder;
import dev.friendai.execution.tool.Tool;
import dev.friendai.execution.tool.ToolResult;
import dev.friendai.perception.resource.ResourceCandidates;
import dev.friendai.server.agent.perception.AreaMapBuilder;
import dev.friendai.server.agent.perception.OwnerFocusBuilder;
import dev.friendai.server.agent.perception.PerceptionBuilder;
import dev.friendai.server.agent.perception.PerceptionContext;
import dev.friendai.server.execution.tools.FeatureToolContributor;
import dev.friendai.server.execution.tools.FeatureToolRegistry;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/** Registers the agent's perception tools, with runtime services supplied by server composition. */
public final class PerceptionTools implements FeatureToolContributor {
    private static final String LOOK_AROUND = "look_around";
    private static final String PLAYER_VIEW = "player_view";
    private static final String SURVEY_AREA = "survey_area";

    private final Function<CompanionEntity, PerceptionContext> perceptionContext;
    private final Function<CompanionEntity, ResourceCandidates> resourceCandidates;
    private final Function<CompanionEntity, String> searchHintLine;

    public PerceptionTools(
            Function<CompanionEntity, PerceptionContext> perceptionContext,
            Function<CompanionEntity, ResourceCandidates> resourceCandidates,
            Function<CompanionEntity, String> searchHintLine) {
        this.perceptionContext = Objects.requireNonNull(perceptionContext);
        this.resourceCandidates = Objects.requireNonNull(resourceCandidates);
        this.searchHintLine = Objects.requireNonNull(searchHintLine);
    }

    public PerceptionTools(
            java.util.function.Supplier<PerceptionContext> perceptionContext,
            java.util.function.Supplier<ResourceCandidates> resourceCandidates,
            Function<CompanionEntity, String> searchHintLine) {
        this(ignored -> perceptionContext.get(), ignored -> resourceCandidates.get(), searchHintLine);
    }

    @Override
    public void register(FeatureToolRegistry registry) {
        registry.registerTool(
                simpleTool(
                        LOOK_AROUND,
                        "Describe the surroundings in detail.",
                        SchemaBuilder.object().build(),
                        AccessLevel.GUEST,
                        (companion, args) -> lookAround(companion)),
                metadata(LOOK_AROUND));
        registry.registerTool(
                callerTool(
                        PLAYER_VIEW,
                        "Where the player who is talking to you stands and what they are looking at or standing on, "
                                + "right now. Use it when they say 'this block', 'the one I'm looking at' or 'where I "
                                + "stand', especially after they may have moved.",
                        SchemaBuilder.object().build(),
                        AccessLevel.TRUSTED,
                        (companion, caller, args) -> ToolResult.ok(
                                OwnerFocusBuilder.of(companion, caller.uuid()).toPromptText())),
                metadata(PLAYER_VIEW));
        registry.registerTool(
                callerTool(
                        SURVEY_AREA,
                        "Draw a top-down map of the area at your eye level. Legend: @ you, O the speaker, Z hostile, "
                                + "# solid, ~ water, ^ lava or fire, . air, ? unloaded chunk. Use it to find a way "
                                + "around walls or to see where a threat stands.",
                        SchemaBuilder.object()
                                .integer("radius", "Map radius in blocks", 3, 16)
                                .build(),
                        AccessLevel.TRUSTED,
                        (companion, caller, args) -> {
                            int radius = args.has("radius") ? args.get("radius").getAsInt() : 8;
                            return ToolResult.ok(AreaMapBuilder.of(
                                            companion, caller.uuid(), radius, resourceCandidates.apply(companion))
                                    + hintLine(companion));
                        }),
                metadata(SURVEY_AREA));
    }

    private ToolResult lookAround(CompanionEntity companion) {
        PerceptionContext context = perceptionContext.apply(companion);
        if (context == null) {
            return ToolResult.error("Perception is unavailable");
        }
        return ToolResult.ok(PerceptionBuilder.detailed(companion, context).toPromptText() + hintLine(companion));
    }

    private String hintLine(CompanionEntity companion) {
        String line = searchHintLine.apply(companion);
        return line == null ? "" : line;
    }

    private static ToolMeta metadata(String name) {
        return switch (name) {
            case LOOK_AROUND -> new ToolMeta(name, ToolGroup.CORE, true, "", List.of(), Cost.LOW);
            case PLAYER_VIEW, SURVEY_AREA -> new ToolMeta(name, ToolGroup.INFO, false, "", List.of(), Cost.LOW);
            default -> throw new IllegalArgumentException("No metadata declared for perception tool: " + name);
        };
    }

    private static Tool<CompanionEntity> simpleTool(
            String name, String description, JsonObject schema, AccessLevel minAccess, Action action) {
        return new Tool<>() {
            @Override
            public String name() {
                return name;
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
            public AccessLevel minAccess() {
                return minAccess;
            }

            @Override
            public ToolResult execute(CompanionEntity companion, JsonObject args) {
                return action.run(companion, args);
            }
        };
    }

    private static Tool<CompanionEntity> callerTool(
            String name, String description, JsonObject schema, AccessLevel minAccess, CallerAction action) {
        return new Tool<>() {
            @Override
            public String name() {
                return name;
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
            public AccessLevel minAccess() {
                return minAccess;
            }

            @Override
            public ToolResult execute(CompanionEntity companion, JsonObject args) {
                return action.run(companion, new Caller(null, "", minAccess), args);
            }

            @Override
            public ToolResult execute(CompanionEntity companion, Caller caller, JsonObject args) {
                return action.run(companion, caller, args);
            }
        };
    }

    @FunctionalInterface
    private interface Action {
        ToolResult run(CompanionEntity companion, JsonObject args);
    }

    @FunctionalInterface
    private interface CallerAction {
        ToolResult run(CompanionEntity companion, Caller caller, JsonObject args);
    }
}
