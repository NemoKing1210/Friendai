package dev.friendai.features.navigation.minecraft.tool;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.friendai.agent.tools.Cost;
import dev.friendai.agent.tools.ToolGroup;
import dev.friendai.agent.tools.ToolMeta;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.execution.tool.AccessLevel;
import dev.friendai.execution.tool.Caller;
import dev.friendai.execution.tool.SchemaBuilder;
import dev.friendai.features.navigation.minecraft.task.FlyToTask;
import dev.friendai.server.execution.tools.FeatureToolContributor;
import dev.friendai.server.execution.tools.FeatureToolRegistry;
import dev.friendai.server.execution.tools.TaskToolDefinition;
import java.util.List;
import net.minecraft.world.phys.Vec3;

/** Collision-aware creative flight to a target in the current dimension. */
public final class FlightTools implements FeatureToolContributor {
    public FlightTools() {}

    @Override
    public void register(FeatureToolRegistry registry) {
        registry.registerTask(new TaskToolDefinition(
                "fly_to",
                "Fly to coordinates in the current dimension using a bounded route that respects collisions, world bounds, and loaded chunks.",
                SchemaBuilder.object()
                        .number("x", "Target X coordinate")
                        .number("y", "Target Y coordinate")
                        .number("z", "Target Z coordinate")
                        .required("x", "y", "z")
                        .build(),
                FlightTools::flyTask,
                FlightTools::flyTask,
                false,
                AccessLevel.OWNER,
                TaskToolDefinition.QueueTraits.DEFAULT,
                true,
                true,
                metadata("fly_to")));
    }

    private static FlyToTask flyTask(CompanionEntity companion, Caller caller, JsonObject args) {
        if (!companion.creativeMode()) {
            throw new IllegalArgumentException("creative mode must be active to fly");
        }
        double x = coordinate(args, "x");
        double y = coordinate(args, "y");
        double z = coordinate(args, "z");
        if (y < companion.level().getMinY() || y > companion.level().getMaxY()) {
            throw new IllegalArgumentException("target y must be within the current dimension height");
        }
        return new FlyToTask(new Vec3(x, y, z));
    }

    private static FlyToTask flyTask(CompanionEntity companion, JsonObject args) {
        return flyTask(companion, null, args);
    }

    private static double coordinate(JsonObject args, String field) {
        JsonElement value = args.get(field);
        if (value == null
                || !value.isJsonPrimitive()
                || !value.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException(field + " must be a finite number");
        }
        double number = value.getAsDouble();
        if (!Double.isFinite(number)) throw new IllegalArgumentException(field + " must be a finite number");
        return number;
    }

    private static ToolMeta metadata(String name) {
        return new ToolMeta(
                name,
                ToolGroup.CONTROL,
                false,
                "- In creative mode, use fly_to for an inaccessible target or a gap the ground route cannot cross. It reports failure when no collision-safe route through loaded chunks exists.",
                List.of(),
                Cost.LOW);
    }
}
