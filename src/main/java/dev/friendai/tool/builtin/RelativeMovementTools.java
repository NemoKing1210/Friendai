package dev.friendai.tool.builtin;

import com.google.gson.JsonObject;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.execution.tool.SchemaBuilder;
import dev.friendai.execution.tool.ToolRegistry;
import dev.friendai.features.navigation.movement.Facing;
import dev.friendai.task.Task;
import dev.friendai.task.builtin.ApproachTask;
import dev.friendai.task.builtin.CircleTask;
import dev.friendai.task.builtin.RelativeMoveTask;
import dev.friendai.task.interact.EntityTargets;
import java.util.List;
import java.util.function.Supplier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/** Small moves measured from where the companion stands or from something it is near. */
final class RelativeMovementTools {
    private static final int DEFAULT_BACK_AWAY_BLOCKS = 4;
    private static final double DEFAULT_APPROACH_DISTANCE = 2.0;
    private static final double DEFAULT_CIRCLE_RADIUS = 4.0;

    private RelativeMovementTools() {}

    static void register(ToolRegistry<CompanionEntity> registry, List<TaskToolDef> defs) {
        ToolKit.add(
                registry,
                defs,
                new TaskToolDef(
                        "step",
                        "Take a few steps in a direction relative to where you are facing (forward, back, left, right), "
                                + "without turning to face it. Use it for small adjustments like 'step back' or 'move left'.",
                        SchemaBuilder.object()
                                .enumeration(
                                        "direction",
                                        "Which way, relative to your facing",
                                        "forward",
                                        "back",
                                        "left",
                                        "right")
                                .integer("blocks", "How many blocks, 1 to 16", 1, 16)
                                .bool("sneak", "Crouch while moving")
                                .required("direction", "blocks")
                                .build(),
                        RelativeMovementTools::step,
                        true));
        ToolKit.add(
                registry,
                defs,
                new TaskToolDef(
                        "back_away",
                        "Move away from something or someone (owner, a player's name, an animal/mob type) to make room. "
                                + "Without a target, back off straight behind you.",
                        SchemaBuilder.object()
                                .string("from", "owner, a player name, or an entity type such as zombie")
                                .integer("blocks", "How far to move away; default 4", 1, 16)
                                .build(),
                        RelativeMovementTools::backAway,
                        true));
        ToolKit.add(
                registry,
                defs,
                new TaskToolDef(
                        "approach",
                        "Walk up to something (owner, a player's name, an animal/mob type), stop at the given distance and look at it.",
                        SchemaBuilder.object()
                                .string("target", "owner, a player name, or an entity type such as cow")
                                .number("distance", "How close to get, in blocks (1 to 8); default 2")
                                .required("target")
                                .build(),
                        RelativeMovementTools::approach,
                        true));
        ToolKit.add(
                registry,
                defs,
                new TaskToolDef(
                        "circle",
                        "Walk in a circle around something (owner, a player's name, an animal/mob type) or around a point, "
                                + "looking at its centre.",
                        SchemaBuilder.object()
                                .string("target", "owner, a player name, or an entity type such as cow")
                                .number("x", "Centre X, if there is no target")
                                .number("y", "Centre Y, if there is no target")
                                .number("z", "Centre Z, if there is no target")
                                .number("radius", "Circle radius in blocks (2 to 16); default 4")
                                .integer("laps", "How many times around (1 to 5); default 1", 1, 5)
                                .build(),
                        RelativeMovementTools::circle,
                        true));
    }

    private static Task step(CompanionEntity c, JsonObject a) {
        String direction = a.get("direction").getAsString();
        int blocks = a.get("blocks").getAsInt();
        boolean sneak = a.has("sneak") && a.get("sneak").getAsBoolean();
        return new RelativeMoveTask(
                "step",
                "stepping " + direction + " " + blocks + " blocks",
                companion -> {
                    Facing.Offset offset = Facing.relative(companion.getYRot(), direction, blocks)
                            .orElseThrow(() -> new IllegalArgumentException("unknown direction: " + direction));
                    return new Vec3(companion.getX() + offset.dx(), companion.getY(), companion.getZ() + offset.dz());
                },
                sneak);
    }

    private static Task backAway(CompanionEntity c, JsonObject a) {
        int blocks = a.has("blocks") ? a.get("blocks").getAsInt() : DEFAULT_BACK_AWAY_BLOCKS;
        Entity from = a.has("from") ? named(c, a.get("from").getAsString()) : null;
        String label = from == null ? "behind me" : "the " + EntityTargets.label(from);
        return new RelativeMoveTask(
                "back_away",
                "backing away from " + label,
                companion -> {
                    double dx;
                    double dz;
                    if (from != null && from.isAlive()) {
                        dx = companion.getX() - from.getX();
                        dz = companion.getZ() - from.getZ();
                    } else {
                        Facing.Offset behind =
                                Facing.relative(companion.getYRot(), "back", 1).orElseThrow();
                        dx = behind.dx();
                        dz = behind.dz();
                    }
                    double length = Math.hypot(dx, dz);
                    if (length < 1.0e-3) {
                        Facing.Offset behind =
                                Facing.relative(companion.getYRot(), "back", 1).orElseThrow();
                        dx = behind.dx();
                        dz = behind.dz();
                        length = 1.0;
                    }
                    return new Vec3(
                            companion.getX() + dx / length * blocks,
                            companion.getY(),
                            companion.getZ() + dz / length * blocks);
                },
                false);
    }

    private static Task approach(CompanionEntity c, JsonObject a) {
        Entity target = named(c, a.get("target").getAsString());
        double distance = a.has("distance")
                ? Math.max(1.0, Math.min(8.0, a.get("distance").getAsDouble()))
                : DEFAULT_APPROACH_DISTANCE;
        return new ApproachTask(target, distance);
    }

    private static Task circle(CompanionEntity c, JsonObject a) {
        double radius =
                a.has("radius") ? Math.max(2.0, Math.min(16.0, a.get("radius").getAsDouble())) : DEFAULT_CIRCLE_RADIUS;
        int laps = a.has("laps") ? a.get("laps").getAsInt() : 1;
        if (a.has("target")) {
            Entity target = named(c, a.get("target").getAsString());
            Supplier<Vec3> center = () -> target.isAlive() && !target.isRemoved() ? target.position() : null;
            return new CircleTask(center, EntityTargets.label(target), radius, laps);
        }
        if (a.has("x") && a.has("y") && a.has("z")) {
            Vec3 fixed = new Vec3(
                    a.get("x").getAsDouble(),
                    a.get("y").getAsDouble(),
                    a.get("z").getAsDouble());
            return new CircleTask(() -> fixed, "point", radius, laps);
        }
        throw new IllegalArgumentException("name a target or give x, y, z for the centre");
    }

    private static Entity named(CompanionEntity c, String name) {
        return EntityTargets.find(c, name).orElseThrow(() -> new IllegalArgumentException("I can't see any " + name));
    }
}
