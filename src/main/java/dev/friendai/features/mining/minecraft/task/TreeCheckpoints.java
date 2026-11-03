package dev.friendai.features.mining.minecraft.task;

import dev.friendai.features.mining.minecraft.mining.BlockMatcher;
import dev.friendai.features.mining.minecraft.mining.TreeFinder;
import dev.friendai.task.Task;
import dev.friendai.task.TaskContext;
import dev.friendai.task.TaskState;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;

/** Versioned, bounded checkpoint for a felling: the tree base and the logs still to cut. */
public final class TreeCheckpoints {
    private static final String VERSION = "v1";
    private static final int MAX_LOGS = 256;

    private TreeCheckpoints() {}

    static String encode(FellTreeTask task) {
        Set<BlockPos> remaining = task.remainingLogs();
        if (remaining.isEmpty() || remaining.size() > MAX_LOGS) {
            return null;
        }
        BlockPos base = task.tree().base();
        StringBuilder text = new StringBuilder(VERSION);
        text.append('|').append(task.tree().type());
        text.append('|')
                .append(base.getX())
                .append('|')
                .append(base.getY())
                .append('|')
                .append(base.getZ());
        text.append('|').append(task.tree().height());
        text.append('|').append(task.limit()).append('|').append(task.collect());
        text.append('|').append(task.cutCount()).append('|').append(task.total());
        StringBuilder logs = new StringBuilder();
        for (BlockPos pos : remaining) {
            if (logs.length() > 0) {
                logs.append(';');
            }
            logs.append(pos.getX() - base.getX())
                    .append(',')
                    .append(pos.getY() - base.getY())
                    .append(',')
                    .append(pos.getZ() - base.getZ());
        }
        text.append('|').append(logs);
        return text.toString();
    }

    public static Optional<Task> fell(TaskState state, TaskContext context) {
        if (state == null || !"fell_tree".equals(state.type()) || state.data().isBlank()) {
            return Optional.empty();
        }
        try {
            String[] p = state.data().split("\\|", -1);
            if (p.length != 11 || !VERSION.equals(p[0])) {
                return Optional.empty();
            }
            BlockMatcher matcher = BlockMatcher.parse(p[1]).orElse(null);
            if (matcher == null) {
                return Optional.empty();
            }
            BlockPos base = new BlockPos(Integer.parseInt(p[2]), Integer.parseInt(p[3]), Integer.parseInt(p[4]));
            int height = Integer.parseInt(p[5]);
            int limit = Integer.parseInt(p[6]);
            boolean collect = Boolean.parseBoolean(p[7]);
            int cutCount = Integer.parseInt(p[8]);
            int total = Integer.parseInt(p[9]);
            Set<BlockPos> remaining = new LinkedHashSet<>();
            if (!p[10].isBlank()) {
                for (String part : p[10].split(";")) {
                    String[] xyz = part.split(",");
                    if (xyz.length != 3) {
                        return Optional.empty();
                    }
                    remaining.add(new BlockPos(
                            base.getX() + Integer.parseInt(xyz[0]),
                            base.getY() + Integer.parseInt(xyz[1]),
                            base.getZ() + Integer.parseInt(xyz[2])));
                }
            }
            if (remaining.isEmpty() || remaining.size() > MAX_LOGS) {
                return Optional.empty();
            }
            TreeFinder.TreeInfo tree = new TreeFinder.TreeInfo(
                    p[1], new ArrayList<>(remaining), base, height, TreeFinder.Kind.STANDING_TREE, 0, "");
            return Optional.of(new FellTreeTask(tree, matcher, limit, collect, cutCount, remaining, total));
        } catch (RuntimeException invalid) {
            return Optional.empty();
        }
    }
}
