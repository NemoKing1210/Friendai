package dev.friendai.tool.builtin;

import dev.friendai.entity.CompanionEntity;
import dev.friendai.fire.FireTarget;
import dev.friendai.pathing.Pos;
import dev.friendai.task.builtin.ExtinguishFireTask;
import dev.friendai.task.fire.FireScan;
import dev.friendai.tool.AccessLevel;
import dev.friendai.tool.SchemaBuilder;
import dev.friendai.tool.ToolRegistry;
import java.util.List;
import java.util.Locale;

/** The fire skill: one task, whichever target the player means. */
public final class FireTools {

    private FireTools() {}

    public static void register(ToolRegistry<CompanionEntity> registry, List<TaskToolDef> defs) {
        ToolKit.add(registry, defs, new TaskToolDef("extinguish_fire",
                "Put a fire out: your own, the owner's, a burning mob's, one burning block, or every fire nearby.",
                SchemaBuilder.object()
                        .string("target", "What to put out: self, owner, entity or near (default near)")
                        .integer("radius", "How far to look and work, in blocks", 1, 16)
                        .integer("x", "Block X, for one burning block")
                        .integer("y", "Block Y, for one burning block")
                        .integer("z", "Block Z, for one burning block")
                        .build(),
                (c, args) -> create(c, args),
                true, AccessLevel.TRUSTED, new TaskToolDef.QueueTraits(true, null)));
    }

    private static ExtinguishFireTask create(CompanionEntity companion, com.google.gson.JsonObject args) {
        int radius = args.has("radius") && !args.get("radius").isJsonNull()
                ? args.get("radius").getAsInt()
                : FireScan.DEFAULT_RADIUS;
        if (args.has("x") && args.has("y") && args.has("z")
                && !args.get("x").isJsonNull() && !args.get("y").isJsonNull() && !args.get("z").isJsonNull()) {
            int y = args.get("y").getAsInt();
            if (y < companion.level().getMinY() || y > companion.level().getMaxY()) {
                throw new IllegalArgumentException("y is outside the world ("
                        + companion.level().getMinY() + ".." + companion.level().getMaxY() + ")");
            }
            return new ExtinguishFireTask(new FireTarget.Block(Pos.of(
                    args.get("x").getAsInt(), y, args.get("z").getAsInt())), radius);
        }
        String target = args.has("target") && !args.get("target").isJsonNull()
                ? args.get("target").getAsString().strip().toLowerCase(Locale.ROOT)
                : "near";
        return switch (target) {
            case "self" -> new ExtinguishFireTask(new FireTarget.Self(), radius);
            case "owner" -> new ExtinguishFireTask(new FireTarget.Owner(), radius);
            case "entity" -> new ExtinguishFireTask(new FireTarget.BurningEntity(
                    FireScan.burningEntityNear(companion, radius)
                            .orElseThrow(() -> new IllegalArgumentException(
                                    "no burning mob or animal within " + radius + " blocks")),
                    "burning mob"), radius);
            case "near" -> ExtinguishFireTask.near(radius);
            default -> throw new IllegalArgumentException("target must be self, owner, entity or near");
        };
    }
}
