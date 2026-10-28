package dev.friendai.features.interaction.minecraft.tool;

import com.google.gson.JsonObject;
import dev.friendai.agent.tools.Cost;
import dev.friendai.agent.tools.ToolGroup;
import dev.friendai.agent.tools.ToolMeta;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.execution.tool.SchemaBuilder;
import dev.friendai.execution.tool.Tool;
import dev.friendai.execution.tool.ToolResult;
import dev.friendai.features.interaction.minecraft.task.EmoteTask;
import dev.friendai.features.interaction.minecraft.task.SitTask;
import dev.friendai.features.interaction.minecraft.task.WaitTask;
import dev.friendai.features.navigation.minecraft.nav.EntityTargets;
import dev.friendai.features.navigation.minecraft.task.TurnTask;
import dev.friendai.features.navigation.movement.Emotes;
import dev.friendai.features.navigation.movement.Facing;
import dev.friendai.server.execution.tools.FeatureToolContributor;
import dev.friendai.server.execution.tools.FeatureToolRegistry;
import dev.friendai.server.execution.tools.TaskToolDefinition;
import dev.friendai.task.Task;
import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.phys.Vec3;

/** Body language: where the companion looks, how it stands, small gestures. None of it needs a long task. */
public final class BodyTools implements FeatureToolContributor {
    private static final int DEFAULT_LOOK_TICKS = 100;
    private static final int MAX_LOOK_TICKS = 1200;
    private static final int POINT_TICKS = 60;
    private static final double GAZE_DISTANCE = 8.0;

    public BodyTools() {}

    @Override
    public void register(FeatureToolRegistry registry) {
        registerInstant(
                registry,
                "look_at",
                "Turn your head toward something and keep looking at it for a while, without stopping what you are doing. "
                        + "Name the target (owner, a player's name, or an animal/mob type such as cow), give x, y, z, "
                        + "or give only pitch to look up (negative) or down (positive).",
                SchemaBuilder.object()
                        .string("target", "owner, a player name, or an entity type such as cow or minecraft:zombie")
                        .number("x", "Look at this X coordinate")
                        .number("y", "Look at this Y coordinate")
                        .number("z", "Look at this Z coordinate")
                        .number("pitch", "Head pitch in degrees: -90 straight up, 0 level, 90 straight down")
                        .integer(
                                "ticks",
                                "How long to keep looking, in ticks (20 per second); default 100",
                                1,
                                MAX_LOOK_TICKS)
                        .build(),
                guarded((c, a) -> {
                    Gaze gaze = gaze(c, a);
                    int ticks = a.has("ticks") ? a.get("ticks").getAsInt() : DEFAULT_LOOK_TICKS;
                    c.body().holdLook(gaze.point(), ticks);
                    applyLookNow(c, gaze.point());
                    return ToolResult.ok("looking at " + gaze.label() + " for " + ticks / 20.0 + " seconds");
                }));
        registerInstant(
                registry,
                "point_at",
                "Point something out to the player: look at it and swing your arm toward it. "
                        + "Name the target (owner, a player's name, an animal/mob type) or give x, y, z.",
                SchemaBuilder.object()
                        .string("target", "owner, a player name, or an entity type such as cow")
                        .number("x", "X coordinate to point at")
                        .number("y", "Y coordinate to point at")
                        .number("z", "Z coordinate to point at")
                        .build(),
                guarded((c, a) -> {
                    if (!a.has("target") && !a.has("x")) {
                        return ToolResult.error("name a target or give x, y, z");
                    }
                    Gaze gaze = gaze(c, a);
                    c.body().holdLook(gaze.point(), POINT_TICKS);
                    applyLookNow(c, gaze.point());
                    c.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT);
                    return ToolResult.ok("pointing at " + gaze.label());
                }));
        registerTask(
                registry,
                "turn",
                "Turn on the spot without walking: to a side (left, right, around), to a compass direction, "
                        + "or by an exact number of degrees (positive is to the right).",
                SchemaBuilder.object()
                        .enumeration(
                                "direction",
                                "Where to turn",
                                "left",
                                "right",
                                "around",
                                "north",
                                "south",
                                "east",
                                "west")
                        .number("degrees", "Turn by this many degrees instead; positive right, negative left")
                        .build(),
                BodyTools::turn,
                true);
        registerInstant(
                registry,
                "sneak",
                "Crouch (sneak) or stand up straight. While crouched you walk more slowly and quietly and do not sprint; "
                        + "it stays on until you turn it off, or until the given time runs out.",
                SchemaBuilder.object()
                        .bool("on", "true to crouch, false to stand up")
                        .integer("ticks", "Stand up by itself after this many ticks (20 per second)", 1, 6000)
                        .required("on")
                        .build(),
                (c, a) -> {
                    boolean on = a.get("on").getAsBoolean();
                    int ticks = a.has("ticks") ? a.get("ticks").getAsInt() : 0;
                    c.body().setSneaking(on, ticks);
                    return ToolResult.ok(on ? "crouching" : "standing up straight");
                });
        registerTask(
                registry,
                "emote",
                "Make a short gesture: wave (greeting), nod (agree), shake_head (disagree), jump_for_joy, bow, spin.",
                SchemaBuilder.object()
                        .enumeration("kind", "The gesture", labels())
                        .required("kind")
                        .build(),
                BodyTools::emote,
                true);
        registerTask(
                registry,
                "sit",
                "Sit down (a low crouch) and stay put until you are told to stand or get another task.",
                SchemaBuilder.object().build(),
                (c, a) -> new SitTask(),
                false,
                new TaskToolDefinition.QueueTraits(true, null));
        registerInstant(
                registry,
                "stand",
                "Stand up from sitting or crouching.",
                SchemaBuilder.object().build(),
                (c, a) -> {
                    boolean wasSitting = c.body().sitting();
                    c.body().setSitting(false);
                    c.body().setSneaking(false, 0);
                    return ToolResult.ok(wasSitting ? "stood up" : "standing");
                });
        registerTask(
                registry,
                "wait",
                "Do nothing for a number of seconds. Use it between the steps of a queue when something has to settle.",
                SchemaBuilder.object()
                        .number("seconds", "How long to wait, from 0.5 to 60")
                        .required("seconds")
                        .build(),
                (c, a) -> {
                    double seconds =
                            Math.max(0.5, Math.min(60.0, a.get("seconds").getAsDouble()));
                    return new WaitTask((int) Math.round(seconds * 20.0));
                },
                true);
        registerInstant(
                registry,
                "swap_hands",
                "Swap what you hold in your main hand with what is in your off hand.",
                (c, a) -> {
                    ItemStack main = c.getItemBySlot(EquipmentSlot.MAINHAND).copy();
                    ItemStack off = c.getItemBySlot(EquipmentSlot.OFFHAND).copy();
                    if (main.isEmpty() && off.isEmpty()) {
                        return ToolResult.error("both hands are empty");
                    }
                    c.setItemSlot(EquipmentSlot.MAINHAND, off);
                    c.setItemSlot(EquipmentSlot.OFFHAND, main);
                    return ToolResult.ok("swapped hands. " + c.equipmentText());
                });
        registerInstant(
                registry,
                "set_idle_behavior",
                "Choose how you behave while idle beside the owner: idle_look makes you glance at them now and then; "
                        + "idle_gestures lets you make small gestures such as a nod on your own (off by default).",
                SchemaBuilder.object()
                        .bool("idle_look", "Glance at the owner while idle")
                        .bool("idle_gestures", "Make small gestures on your own while idle")
                        .build(),
                (c, a) -> {
                    if (!a.has("idle_look") && !a.has("idle_gestures")) {
                        return ToolResult.error("give idle_look, idle_gestures or both");
                    }
                    if (a.has("idle_look")) {
                        c.body().setIdleLook(a.get("idle_look").getAsBoolean());
                    }
                    if (a.has("idle_gestures")) {
                        c.body().setIdleGestures(a.get("idle_gestures").getAsBoolean());
                    }
                    return ToolResult.ok("idle look: " + (c.body().idleLook() ? "on" : "off") + ", idle gestures: "
                            + (c.body().idleGestures() ? "on" : "off"));
                });
        registerInstant(
                registry,
                "set_follow_distance",
                "Set how close you keep to the owner while following, in blocks (1 hugging them, 8 giving them room).",
                SchemaBuilder.object()
                        .number("blocks", "Distance in blocks, from 1 to 8")
                        .required("blocks")
                        .build(),
                (c, a) -> {
                    c.body().setFollowDistance(a.get("blocks").getAsDouble());
                    return ToolResult.ok("following at " + c.body().followDistance() + " blocks");
                });
    }

    /** What to look at: a point (re-read every tick, null when the target is gone) and a name for the report. */
    private record Gaze(Supplier<Vec3> point, String label) {}

    private static Gaze gaze(CompanionEntity c, JsonObject a) {
        if (a.has("target")) {
            String name = a.get("target").getAsString();
            Entity entity = EntityTargets.find(c, name).orElse(null);
            if (entity == null) {
                throw new IllegalArgumentException("I can't see any " + name);
            }
            return new Gaze(
                    () -> entity.isAlive() && !entity.isRemoved() ? entity.getEyePosition() : null,
                    EntityTargets.label(entity));
        }
        if (a.has("x") || a.has("y") || a.has("z")) {
            if (!(a.has("x") && a.has("y") && a.has("z"))) {
                throw new IllegalArgumentException("give all of x, y, z");
            }
            Vec3 fixed = new Vec3(
                    a.get("x").getAsDouble(),
                    a.get("y").getAsDouble(),
                    a.get("z").getAsDouble());
            return new Gaze(
                    () -> fixed,
                    (int) Math.floor(fixed.x) + " " + (int) Math.floor(fixed.y) + " " + (int) Math.floor(fixed.z));
        }
        if (a.has("pitch")) {
            double pitch = Math.max(-90.0, Math.min(90.0, a.get("pitch").getAsDouble()));
            double yaw = Math.toRadians(c.getYRot());
            double pitchRad = Math.toRadians(pitch);
            Vec3 eye = c.getEyePosition();
            Vec3 fixed = new Vec3(
                    eye.x - Math.sin(yaw) * Math.cos(pitchRad) * GAZE_DISTANCE,
                    eye.y - Math.sin(pitchRad) * GAZE_DISTANCE,
                    eye.z + Math.cos(yaw) * Math.cos(pitchRad) * GAZE_DISTANCE);
            String label = pitch < -10.0 ? "up" : pitch > 10.0 ? "down" : "straight ahead";
            return new Gaze(() -> fixed, label);
        }
        throw new IllegalArgumentException("name a target, give x, y, z, or give a pitch");
    }

    private static void applyLookNow(CompanionEntity c, Supplier<Vec3> point) {
        Vec3 p = point.get();
        if (p != null) {
            c.facing().lookAt(p, 2);
        }
    }

    private static Task turn(CompanionEntity c, JsonObject a) {
        if (a.has("degrees")) {
            return TurnTask.by((float) a.get("degrees").getAsDouble());
        }
        if (!a.has("direction")) {
            throw new IllegalArgumentException("give a direction or degrees");
        }
        String direction = a.get("direction").getAsString();
        return switch (direction) {
            case "left" -> TurnTask.by(-90.0f);
            case "right" -> TurnTask.by(90.0f);
            case "around" -> TurnTask.by(180.0f);
            default ->
                TurnTask.toYaw(
                        Facing.compass(direction)
                                .orElseThrow(() -> new IllegalArgumentException("unknown direction: " + direction)),
                        direction);
        };
    }

    private static Task emote(CompanionEntity c, JsonObject a) {
        String kind = a.get("kind").getAsString();
        return new EmoteTask(
                Emotes.Kind.parse(kind).orElseThrow(() -> new IllegalArgumentException("unknown gesture: " + kind)));
    }

    private static String[] labels() {
        return Arrays.stream(Emotes.Kind.values()).map(Emotes.Kind::label).toArray(String[]::new);
    }

    /** Reports an unusable argument as a tool error with the plain message, not as a crash. */
    private static Action guarded(Action action) {
        return (c, a) -> {
            try {
                return action.run(c, a);
            } catch (IllegalArgumentException e) {
                return ToolResult.error(e.getMessage());
            }
        };
    }

    private static void registerTask(
            FeatureToolRegistry registry,
            String name,
            String description,
            JsonObject schema,
            TaskToolDefinition.Factory factory,
            boolean plannable) {
        registerTask(registry, name, description, schema, factory, plannable, TaskToolDefinition.QueueTraits.DEFAULT);
    }

    private static void registerTask(
            FeatureToolRegistry registry,
            String name,
            String description,
            JsonObject schema,
            TaskToolDefinition.Factory factory,
            boolean plannable,
            TaskToolDefinition.QueueTraits queueTraits) {
        registry.registerTask(
                new TaskToolDefinition(name, description, schema, factory, plannable, queueTraits, metadata(name)));
    }

    private static void registerInstant(FeatureToolRegistry registry, String name, String description, Action action) {
        registerInstant(registry, name, description, SchemaBuilder.object().build(), action);
    }

    private static void registerInstant(
            FeatureToolRegistry registry, String name, String description, JsonObject schema, Action action) {
        registry.registerTool(
                new Tool<>() {
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
                    public ToolResult execute(CompanionEntity companion, JsonObject args) {
                        return action.run(companion, args);
                    }
                },
                metadata(name));
    }

    private static ToolMeta metadata(String name) {
        return new ToolMeta(name, ToolGroup.BODY, false, "", List.of(), Cost.LOW);
    }

    @FunctionalInterface
    private interface Action {
        ToolResult run(CompanionEntity companion, JsonObject args);
    }
}
