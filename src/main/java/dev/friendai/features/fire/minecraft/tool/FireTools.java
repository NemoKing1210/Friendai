package dev.friendai.features.fire.minecraft.tool;

import com.google.gson.JsonObject;
import dev.friendai.agent.tools.Cost;
import dev.friendai.agent.tools.ToolGroup;
import dev.friendai.agent.tools.ToolMeta;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.execution.tool.AccessLevel;
import dev.friendai.execution.tool.SchemaBuilder;
import dev.friendai.features.fire.FireTarget;
import dev.friendai.features.fire.minecraft.FireScan;
import dev.friendai.features.fire.minecraft.task.ExtinguishFireTask;
import dev.friendai.features.navigation.pathing.Pos;
import dev.friendai.server.execution.tools.FeatureToolContributor;
import dev.friendai.server.execution.tools.FeatureToolRegistry;
import dev.friendai.server.execution.tools.TaskToolDefinition;
import java.util.List;
import java.util.Locale;

/** The fire skill: one task, whichever target the player means. */
public final class FireTools implements FeatureToolContributor {
    @Override
    public void register(FeatureToolRegistry registry) {
        registry.registerTask(new TaskToolDefinition(
                "extinguish_fire",
                "Put a fire out: your own, the owner's, a burning mob's, one burning block, or every fire nearby.",
                SchemaBuilder.object()
                        .string("target", "What to put out: self, owner, entity or near (default near)")
                        .integer("radius", "How far to look and work, in blocks", 1, 16)
                        .integer("x", "Block X, for one burning block")
                        .integer("y", "Block Y, for one burning block")
                        .integer("z", "Block Z, for one burning block")
                        .build(),
                FireTools::create,
                null,
                true,
                AccessLevel.TRUSTED,
                new TaskToolDefinition.QueueTraits(true, null),
                true,
                true,
                metadata("extinguish_fire")));
    }

    private static ToolMeta metadata(String name) {
        return new ToolMeta(
                name,
                ToolGroup.FIRE,
                false,
                "- A fire is an emergency: call extinguish_fire at once for your own fire (target self), the owner's (owner), a burning mob (entity) or the fires around you (near). A water bucket is poured and taken back; without water a fire block is broken by hand. Never break a lit campfire — it is a cooking station — and never pour water in the nether.",
                List.of(),
                Cost.MEDIUM);
    }

    private static ExtinguishFireTask create(CompanionEntity companion, JsonObject args) {
        int radius = args.has("radius") && !args.get("radius").isJsonNull()
                ? args.get("radius").getAsInt()
                : FireScan.DEFAULT_RADIUS;
        if (args.has("x")
                && args.has("y")
                && args.has("z")
                && !args.get("x").isJsonNull()
                && !args.get("y").isJsonNull()
                && !args.get("z").isJsonNull()) {
            int y = args.get("y").getAsInt();
            if (y < companion.level().getMinY() || y > companion.level().getMaxY()) {
                throw new IllegalArgumentException("y is outside the world ("
                        + companion.level().getMinY() + ".." + companion.level().getMaxY() + ")");
            }
            return new ExtinguishFireTask(
                    new FireTarget.Block(
                            Pos.of(args.get("x").getAsInt(), y, args.get("z").getAsInt())),
                    radius);
        }
        String target = args.has("target") && !args.get("target").isJsonNull()
                ? args.get("target").getAsString().strip().toLowerCase(Locale.ROOT)
                : "near";
        return switch (target) {
            case "self" -> new ExtinguishFireTask(new FireTarget.Self(), radius);
            case "owner" -> new ExtinguishFireTask(new FireTarget.Owner(), radius);
            case "entity" ->
                new ExtinguishFireTask(
                        new FireTarget.BurningEntity(
                                FireScan.burningEntityNear(companion, radius)
                                        .orElseThrow(() -> new IllegalArgumentException(
                                                "no burning mob or animal within " + radius + " blocks")),
                                "burning mob"),
                        radius);
            case "near" -> ExtinguishFireTask.near(radius);
            default -> throw new IllegalArgumentException("target must be self, owner, entity or near");
        };
    }
}
