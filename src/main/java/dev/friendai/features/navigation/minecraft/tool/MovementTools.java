package dev.friendai.features.navigation.minecraft.tool;

import com.google.gson.JsonObject;
import dev.friendai.agent.tools.Cost;
import dev.friendai.agent.tools.ToolGroup;
import dev.friendai.agent.tools.ToolMeta;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.execution.tool.SchemaBuilder;
import dev.friendai.execution.tool.Tool;
import dev.friendai.execution.tool.ToolResult;
import dev.friendai.features.navigation.minecraft.task.FollowTask;
import dev.friendai.features.navigation.minecraft.task.GoToTask;
import dev.friendai.features.navigation.minecraft.task.JumpTask;
import dev.friendai.features.navigation.minecraft.task.StayTask;
import dev.friendai.features.navigation.movement.PaceMode;
import dev.friendai.server.execution.tools.FeatureToolContributor;
import dev.friendai.server.execution.tools.FeatureToolRegistry;
import dev.friendai.server.execution.tools.TaskToolDefinition;
import dev.friendai.task.Task;
import java.util.List;
import net.minecraft.world.phys.Vec3;

/** Where the companion goes: following, staying put, walking somewhere. */
public final class MovementTools implements FeatureToolContributor {
    private static final String SET_PACE_HINT =
            "- To change how fast you travel, call set_pace: run when asked to hurry or run, walk when asked to slow down, auto to go back to the default.";

    public MovementTools() {}

    @Override
    public void register(FeatureToolRegistry registry) {
        registry.registerTask(task(
                "follow_player",
                "Follow the owner and stay close to them, and turn following back on for later. Use it when the owner asks you to follow or come along, not to answer a message.",
                SchemaBuilder.object().build(),
                (c, a) -> {
                    c.setFollowMode(true);
                    return new FollowTask(false);
                },
                false,
                new TaskToolDefinition.QueueTraits(true, null)));
        registry.registerTask(task(
                "stay",
                "Wait in this spot for a few minutes; queued work resumes when the wait ends. Not for 'don't follow me': "
                        + "that is set_follow_mode with mode=off, which leaves your work running.",
                SchemaBuilder.object().build(),
                (c, a) -> new StayTask(),
                false,
                new TaskToolDefinition.QueueTraits(true, null)));
        registry.registerTask(task(
                "go_to",
                "Walk to the given coordinates, or to the owner with target=owner.",
                SchemaBuilder.object()
                        .number("x", "Target X coordinate")
                        .number("y", "Target Y coordinate")
                        .number("z", "Target Z coordinate")
                        .enumeration("target", "Use 'owner' to walk to the owner", "owner")
                        .bool("sneak", "Crouch while walking, for quiet or careful movement")
                        .build(),
                MovementTools::goTo,
                true,
                new TaskToolDefinition.QueueTraits(true, null)));
        registry.registerTask(task(
                "jump",
                "Jump once from the ground. Use it when the player asks you to jump or hop; for several jumps in a row, put several jump steps in one plan.",
                SchemaBuilder.object().build(),
                (c, a) -> new JumpTask(),
                true,
                TaskToolDefinition.QueueTraits.DEFAULT));
        registry.registerTool(
                new Tool<>() {
                    @Override
                    public String name() {
                        return "set_pace";
                    }

                    @Override
                    public String description() {
                        return "Set how fast the companion travels: auto (runs when the owner is far ahead, walks otherwise), walk (never sprint), run (sprint whenever it can).";
                    }

                    @Override
                    public JsonObject schema() {
                        return SchemaBuilder.object()
                                .enumeration("mode", "Pace mode", "auto", "walk", "run")
                                .required("mode")
                                .build();
                    }

                    @Override
                    public ToolResult execute(CompanionEntity companion, JsonObject args) {
                        PaceMode mode = PaceMode.parse(args.get("mode").getAsString());
                        companion.setPaceMode(mode);
                        return ToolResult.ok("pace: " + mode.label());
                    }
                },
                metadata("set_pace", SET_PACE_HINT));
    }

    private static TaskToolDefinition task(
            String name,
            String description,
            JsonObject schema,
            TaskToolDefinition.Factory factory,
            boolean plannable,
            TaskToolDefinition.QueueTraits queueTraits) {
        return new TaskToolDefinition(name, description, schema, factory, plannable, queueTraits, metadata(name, ""));
    }

    private static ToolMeta metadata(String name, String hint) {
        return new ToolMeta(name, ToolGroup.MOVEMENT, false, hint, List.of(), Cost.MEDIUM);
    }

    private static Task goTo(CompanionEntity companion, JsonObject args) {
        boolean sneak = args.has("sneak") && args.get("sneak").getAsBoolean();
        if (args.has("target")) {
            Vec3 owner = companion
                    .owner()
                    .map(player -> player.position())
                    .orElseThrow(() -> new IllegalArgumentException("the owner is not around"));
            return new GoToTask(owner, sneak);
        }
        if (args.has("x") && args.has("y") && args.has("z")) {
            return new GoToTask(
                    new Vec3(
                            args.get("x").getAsDouble(),
                            args.get("y").getAsDouble(),
                            args.get("z").getAsDouble()),
                    sneak);
        }
        throw new IllegalArgumentException("need x,y,z or target=owner");
    }
}
