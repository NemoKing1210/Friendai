package dev.friendai.features.navigation.minecraft.tool;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.friendai.agent.tools.Cost;
import dev.friendai.agent.tools.ToolGroup;
import dev.friendai.agent.tools.ToolMeta;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.execution.tool.AccessLevel;
import dev.friendai.execution.tool.SchemaBuilder;
import dev.friendai.execution.tool.Tool;
import dev.friendai.execution.tool.ToolResult;
import dev.friendai.features.navigation.minecraft.task.FollowTask;
import dev.friendai.features.navigation.minecraft.task.GoToTask;
import dev.friendai.features.navigation.minecraft.task.JumpTask;
import dev.friendai.features.navigation.minecraft.task.NavigationCheckpoints;
import dev.friendai.features.navigation.minecraft.task.StayTask;
import dev.friendai.features.navigation.minecraft.task.TravelThroughPortalTask;
import dev.friendai.foundation.movement.PaceMode;
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
    private static final String FOLLOW_HINT =
            "- Use follow_player when the owner asks you to come along and keep up; use go_to for one named destination.";
    private static final String STAY_HINT =
            "- Use stay to wait where you are; it ends early when queued work is ready, and it is not a way to refuse following.";
    private static final String GO_TO_HINT =
            "- Use go_to for one destination the owner named; it may dig and bridge, and reports a failure when no route exists.";
    private static final String PORTAL_HINT =
            "- Use travel_through_portal to go to the nether or back to the overworld. It walks into a lit portal within"
                    + " 16 blocks, builds and lights one from 10 obsidian and flint and steel when none is near, and"
                    + " records a return portal before leaving.";
    private static final String JUMP_HINT =
            "- Use jump for a single hop; put several jump steps in one plan for repeats.";

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
                        new TaskToolDefinition.QueueTraits(true, null),
                        FOLLOW_HINT)
                .withCheckpoint("follow", NavigationCheckpoints::follow));
        registry.registerTask(task(
                        "stay",
                        "Wait where you are until the wait ends or until queued work is ready. Not for 'don't follow me': "
                                + "that is set_follow_mode with mode=off, which leaves your work running.",
                        SchemaBuilder.object().build(),
                        (c, a) -> new StayTask(),
                        false,
                        new TaskToolDefinition.QueueTraits(true, null),
                        STAY_HINT)
                .withCheckpoint("stay", NavigationCheckpoints::stay)
                .asCreativeSafe());
        registry.registerTask(task(
                        "go_to",
                        "Walk to the given coordinates, or to where the owner stands right now with target=owner. To keep "
                                + "following them, use follow_player instead.",
                        SchemaBuilder.object()
                                .number("x", "Target X coordinate")
                                .number("y", "Target Y coordinate")
                                .number("z", "Target Z coordinate")
                                .enumeration("target", "Use 'owner' to walk to the owner", "owner")
                                .bool("sneak", "Crouch while walking, for quiet or careful movement")
                                .build(),
                        MovementTools::goTo,
                        true,
                        new TaskToolDefinition.QueueTraits(true, null),
                        GO_TO_HINT)
                .withCheckpoint("go_to", NavigationCheckpoints::goTo));
        registry.registerTask(task(
                        "travel_through_portal",
                        "Travel to another dimension through a portal. Enter a lit portal within "
                                + "16 blocks, or build and light a nether portal from 10 obsidian and "
                                + "flint and steel when there is none; a build without those materials "
                                + "fails plainly. The trip records a return portal before leaving and "
                                + "never teleports you directly. The End is not supported.",
                        SchemaBuilder.object()
                                .enumeration("dimension", "Destination", "the_nether", "overworld")
                                .required("dimension")
                                .build(),
                        MovementTools::portal,
                        false,
                        new TaskToolDefinition.QueueTraits(true, null),
                        PORTAL_HINT,
                        AccessLevel.OWNER)
                .withCheckpoint("travel_through_portal", NavigationCheckpoints::travelThroughPortal));
        registry.registerTask(task(
                        "jump",
                        "Jump once from the ground. Use it when the player asks you to jump or hop; for several jumps in a row, put several jump steps in one plan.",
                        SchemaBuilder.object().build(),
                        (c, a) -> new JumpTask(),
                        true,
                        TaskToolDefinition.QueueTraits.DEFAULT,
                        JUMP_HINT)
                .asCreativeSafe()
                .withCheckpoint("jump", NavigationCheckpoints::jump));
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
                        PaceMode mode = PaceMode.parseStrict(args.get("mode").getAsString());
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
            TaskToolDefinition.QueueTraits queueTraits,
            String hint) {
        return task(name, description, schema, factory, plannable, queueTraits, hint, AccessLevel.TRUSTED);
    }

    /**
     * A task tool with an explicit minimum access. A trip that may build and cross a dimension is owner work, not
     * something a trusted guest's order should start.
     */
    private static TaskToolDefinition task(
            String name,
            String description,
            JsonObject schema,
            TaskToolDefinition.Factory factory,
            boolean plannable,
            TaskToolDefinition.QueueTraits queueTraits,
            String hint,
            AccessLevel minAccess) {
        return new TaskToolDefinition(
                name,
                description,
                schema,
                factory,
                null,
                plannable,
                minAccess,
                queueTraits,
                true,
                true,
                metadata(name, hint));
    }

    private static ToolMeta metadata(String name, String hint) {
        return new ToolMeta(name, ToolGroup.MOVEMENT, false, hint, List.of(), Cost.MEDIUM);
    }

    /**
     * A portal trip is checked before the walk: a destination the game cannot deliver must not send the companion into
     * a portal to find out, which would move it a world away from where the order expected it.
     */
    static Task portal(CompanionEntity companion, JsonObject args) {
        String dimension = args.get("dimension").getAsString();
        if (!TravelThroughPortalTask.isSupportedDestination(dimension)) {
            throw new IllegalArgumentException("destination must be the_nether or overworld");
        }
        String source = companion.level().dimension().identifier().toString();
        if (!TravelThroughPortalTask.isSupportedSource(source)) {
            throw new IllegalArgumentException(
                    "portal travel is not supported from " + source + "; only the overworld and the nether");
        }
        return new TravelThroughPortalTask(dimension);
    }

    static Task goTo(CompanionEntity companion, JsonObject args) {
        boolean sneak = args.has("sneak") && args.get("sneak").getAsBoolean();
        if (args.has("target")) {
            if (args.has("x") || args.has("y") || args.has("z")) {
                throw new IllegalArgumentException("give either target=owner or x,y,z, not both");
            }
            Vec3 owner = companion
                    .owner()
                    .map(player -> player.position())
                    .orElseThrow(() -> new IllegalArgumentException("the owner is not around"));
            return new GoToTask(owner, sneak);
        }
        return new GoToTask(
                point(args, companion.level().getMinY(), companion.level().getMaxY()), sneak);
    }

    /**
     * The coordinate triple a walk asks for, checked before a task exists: a non-finite or out-of-world target would
     * otherwise spend a whole search budget, a material probe and a gathering round before failing.
     */
    static Vec3 point(JsonObject args, int minY, int maxY) {
        double x = coordinate(args, "x");
        double y = coordinate(args, "y");
        double z = coordinate(args, "z");
        if (y < minY || y > maxY) {
            throw new IllegalArgumentException(
                    "y must be within the current dimension height (" + minY + ".." + maxY + ")");
        }
        return new Vec3(x, y, z);
    }

    private static double coordinate(JsonObject args, String field) {
        JsonElement value = args.get(field);
        if (value == null) {
            throw new IllegalArgumentException("need x,y,z or target=owner");
        }
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException(field + " must be a finite number");
        }
        double number = value.getAsDouble();
        if (!Double.isFinite(number)) {
            throw new IllegalArgumentException(field + " must be a finite number");
        }
        return number;
    }
}
