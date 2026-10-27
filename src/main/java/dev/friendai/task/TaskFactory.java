package dev.friendai.task;

import dev.friendai.build.Blueprint;
import dev.friendai.build.PartKind;
import dev.friendai.task.build.BlueprintStore;
import dev.friendai.task.build.Placement;
import dev.friendai.task.builtin.BuildTask;
import dev.friendai.task.builtin.FollowTask;
import dev.friendai.task.builtin.GoToTask;
import dev.friendai.task.builtin.StayTask;
import dev.friendai.task.builtin.TendFarmTask;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.phys.Vec3;

public final class TaskFactory {
    private TaskFactory() {}

    /** Rebuilds a task from {@link Task#saveData()}; tasks that need the world cannot be restored this way. */
    public static Optional<Task> restore(String saved) {
        return restore(saved, null);
    }

    /**
     * Rebuilds a task from {@link Task#saveData()}. A build in progress also needs the level: its blueprint and
     * scaffold live in the world's files, and a missing or broken file means the build is simply not restored.
     */
    public static Optional<Task> restore(String saved, ServerLevel level) {
        if (saved == null) {
            return Optional.empty();
        }
        if (saved.equals("follow")) {
            return Optional.of(new FollowTask(true));
        }
        if (saved.equals("stay")) {
            return Optional.of(new StayTask());
        }
        if (saved.startsWith("goto:")) {
            try {
                String[] p = saved.substring(5).split(",");
                return Optional.of(new GoToTask(new Vec3(Double.parseDouble(p[0]), Double.parseDouble(p[1]), Double.parseDouble(p[2]))));
            } catch (RuntimeException e) {
                return Optional.empty();
            }
        }
        if (saved.startsWith("tend_farm:")) {
            try {
                String[] p = saved.substring(10).split(",");
                return Optional.of(new TendFarmTask(
                        new BlockPos(Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2])),
                        Integer.parseInt(p[3])));
            } catch (RuntimeException e) {
                return Optional.empty();
            }
        }
        if (saved.startsWith("build:")) {
            return level == null ? Optional.empty() : restoreBuild(saved, level);
        }
        return Optional.empty();
    }

    /** {@code build:<id>,<x>,<y>,<z>,<facing>,<clearSite>,<gather>[,<part>+<part>…]} plus the in-progress file. */
    private static Optional<Task> restoreBuild(String saved, ServerLevel level) {
        try {
            String[] p = saved.substring("build:".length()).split(",");
            if (p.length != 7 && p.length != 8) {
                return Optional.empty();
            }
            String id = p[0];
            if (!BlueprintStore.validBuildId(id)) {
                return Optional.empty();
            }
            BlockPos origin = new BlockPos(Integer.parseInt(p[1]), Integer.parseInt(p[2]), Integer.parseInt(p[3]));
            Direction facing = facing(p[4]);
            if (facing == null) {
                return Optional.empty();
            }
            boolean clearSite = bool(p[5]);
            boolean gather = bool(p[6]);
            Set<PartKind> parts = new HashSet<>();
            if (p.length == 8 && !p[7].isBlank()) {
                for (String name : p[7].split("\\+")) {
                    PartKind.parse(name).ifPresent(parts::add);
                }
            }
            Rotation rotation = Placement.rotationFor(facing);
            BlueprintStore.InProgress inProgress =
                    BlueprintStore.forLevel(level).loadInProgress(id).orElse(null);
            if (inProgress == null || inProgress.rotation() != rotation) {
                return Optional.empty();
            }
            Blueprint blueprint = inProgress.blueprint();
            return Optional.of(BuildTask.resume(
                    blueprint, origin, rotation, clearSite, gather, inProgress.scaffold(), id, Set.copyOf(parts)));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    private static Direction facing(String name) {
        return switch (name) {
            case "north" -> Direction.NORTH;
            case "south" -> Direction.SOUTH;
            case "east" -> Direction.EAST;
            case "west" -> Direction.WEST;
            default -> null;
        };
    }

    /** {@code true}/{@code false} only, so a broken save is not silently read as "false". */
    private static boolean bool(String text) {
        if (text.equals("true")) {
            return true;
        }
        if (text.equals("false")) {
            return false;
        }
        throw new IllegalArgumentException("bad boolean " + text);
    }
}
