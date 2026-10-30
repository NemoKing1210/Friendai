package dev.friendai.features.construction.minecraft.task;

import dev.friendai.features.construction.blueprint.Blueprint;
import dev.friendai.features.construction.blueprint.PartKind;
import dev.friendai.features.construction.minecraft.build.BlueprintStore;
import dev.friendai.features.construction.minecraft.build.Placement;
import dev.friendai.task.Task;
import dev.friendai.task.TaskContext;
import dev.friendai.task.TaskState;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Rotation;

/** Checkpoint decoding for a running build; contributed beside the build task definition. */
public final class BuildCheckpoints {
    private BuildCheckpoints() {}

    public static Optional<Task> build(TaskState state, TaskContext context, BuildTask.ObtainFactory obtainFactory) {
        if (context == null || context.level() == null) return Optional.empty();
        try {
            String[] p = state.data().split(",");
            if (p.length != 7 && p.length != 8 || !BlueprintStore.validBuildId(p[0])) return Optional.empty();
            BlockPos origin = new BlockPos(Integer.parseInt(p[1]), Integer.parseInt(p[2]), Integer.parseInt(p[3]));
            Direction facing = switch (p[4]) {
                case "north" -> Direction.NORTH;
                case "south" -> Direction.SOUTH;
                case "east" -> Direction.EAST;
                case "west" -> Direction.WEST;
                default -> null;
            };
            if (facing == null
                    || !(p[5].equals("true") || p[5].equals("false"))
                    || !(p[6].equals("true") || p[6].equals("false"))) return Optional.empty();
            Set<PartKind> parts = new HashSet<>();
            if (p.length == 8 && !p[7].isBlank()) {
                for (String name : p[7].split("\\+")) {
                    Optional<PartKind> kind = PartKind.parse(name);
                    if (kind.isEmpty()) return Optional.empty();
                    parts.add(kind.get());
                }
            }
            Rotation rotation = Placement.rotationFor(facing);
            BlueprintStore.InProgress inProgress = BlueprintStore.forLevel(context.level())
                    .loadInProgress(p[0])
                    .orElse(null);
            if (inProgress == null || inProgress.rotation() != rotation) return Optional.empty();
            Blueprint blueprint = inProgress.blueprint();
            return Optional.of(BuildTask.resume(
                    blueprint,
                    origin,
                    rotation,
                    Boolean.parseBoolean(p[5]),
                    Boolean.parseBoolean(p[6]),
                    inProgress.scaffold(),
                    p[0],
                    Set.copyOf(parts),
                    obtainFactory));
        } catch (RuntimeException invalid) {
            return Optional.empty();
        }
    }
}
