package dev.friendai.tool.builtin;

import com.google.gson.JsonObject;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.movement.PaceMode;
import dev.friendai.task.Task;
import dev.friendai.task.builtin.FollowTask;
import dev.friendai.task.builtin.GoToTask;
import dev.friendai.task.builtin.StayTask;
import dev.friendai.tool.SchemaBuilder;
import dev.friendai.tool.ToolRegistry;
import dev.friendai.tool.ToolResult;
import java.util.List;
import net.minecraft.world.phys.Vec3;

/** Where the companion goes: following, staying put, walking somewhere. */
final class MovementTools {
    private MovementTools() {}

    static void register(ToolRegistry<CompanionEntity> registry, List<TaskToolDef> defs) {
        ToolKit.add(registry, defs, new TaskToolDef("follow_player", "Follow the owner and stay close to them.",
                SchemaBuilder.object().build(), (c, a) -> new FollowTask(false), false));
        ToolKit.add(registry, defs, new TaskToolDef("stay", "Stay in the current spot until told otherwise.",
                SchemaBuilder.object().build(), (c, a) -> new StayTask(), false));
        ToolKit.add(registry, defs, new TaskToolDef("go_to", "Walk to the given coordinates, or to the owner with target=owner.",
                SchemaBuilder.object()
                        .number("x", "Target X coordinate")
                        .number("y", "Target Y coordinate")
                        .number("z", "Target Z coordinate")
                        .enumeration("target", "Use 'owner' to walk to the owner", "owner")
                        .build(),
                MovementTools::goTo, true));
        registry.register(ToolKit.instantTool("set_pace",
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
        if (args.has("target")) {
            Vec3 owner = c.owner().map(p -> p.position()).orElseThrow(() -> new IllegalArgumentException("the owner is not around"));
            return new GoToTask(owner);
        }
        if (args.has("x") && args.has("y") && args.has("z")) {
            return new GoToTask(new Vec3(args.get("x").getAsDouble(), args.get("y").getAsDouble(), args.get("z").getAsDouble()));
        }
        throw new IllegalArgumentException("need x,y,z or target=owner");
    }
}
