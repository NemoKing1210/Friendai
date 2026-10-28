package dev.friendai.tool.builtin;

import com.google.gson.JsonObject;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.execution.tool.SchemaBuilder;
import dev.friendai.execution.tool.ToolRegistry;
import dev.friendai.execution.tool.ToolResult;
import dev.friendai.movement.PaceMode;
import dev.friendai.task.Task;
import dev.friendai.task.builtin.FollowTask;
import dev.friendai.task.builtin.GoToTask;
import dev.friendai.task.builtin.JumpTask;
import dev.friendai.task.builtin.StayTask;
import java.util.List;
import net.minecraft.world.phys.Vec3;

/** Where the companion goes: following, staying put, walking somewhere. */
final class MovementTools {
    private MovementTools() {}

    static void register(ToolRegistry<CompanionEntity> registry, List<TaskToolDef> defs) {
        ToolKit.add(
                registry,
                defs,
                new TaskToolDef(
                        "follow_player",
                        "Follow the owner and stay close to them, and turn following back on for later. Use it when the owner asks you to follow or come along, not to answer a message.",
                        SchemaBuilder.object().build(),
                        (c, a) -> {
                            c.setFollowMode(true);
                            return new FollowTask(false);
                        },
                        false,
                        new TaskToolDef.QueueTraits(true, null)));
        ToolKit.add(
                registry,
                defs,
                new TaskToolDef(
                        "stay",
                        "Wait in this spot for a few minutes; queued work resumes when the wait ends. Not for 'don't follow me': "
                                + "that is set_follow_mode with mode=off, which leaves your work running.",
                        SchemaBuilder.object().build(),
                        (c, a) -> new StayTask(),
                        false,
                        new TaskToolDef.QueueTraits(true, null)));
        ToolKit.add(
                registry,
                defs,
                new TaskToolDef(
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
                        new TaskToolDef.QueueTraits(true, null)));
        ToolKit.add(
                registry,
                defs,
                new TaskToolDef(
                        "jump",
                        "Jump once from the ground. Use it when the player asks you to jump or hop; for several jumps in a row, put several jump steps in one plan.",
                        SchemaBuilder.object().build(),
                        (c, a) -> new JumpTask(),
                        true));
        registry.register(ToolKit.instantTool(
                "set_pace",
                "Set how fast the companion travels: auto (runs when the owner is far ahead, walks otherwise), walk (never sprint), run (sprint whenever it can).",
                SchemaBuilder.object()
                        .enumeration("mode", "Pace mode", "auto", "walk", "run")
                        .required("mode")
                        .build(),
                (c, a) -> {
                    PaceMode mode = PaceMode.parse(a.get("mode").getAsString());
                    c.setPaceMode(mode);
                    return ToolResult.ok("pace: " + mode.label());
                }));
    }

    private static Task goTo(CompanionEntity c, JsonObject args) {
        boolean sneak = args.has("sneak") && args.get("sneak").getAsBoolean();
        if (args.has("target")) {
            Vec3 owner = c.owner()
                    .map(p -> p.position())
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
