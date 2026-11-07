package dev.friendai.features.mining.minecraft.task;

import dev.friendai.features.mining.excavation.ExcavationDirection;
import dev.friendai.features.mining.excavation.ExcavationGeometry;
import dev.friendai.features.mining.excavation.ExcavationProgress;
import dev.friendai.features.mining.excavation.ExcavationShape;
import dev.friendai.features.mining.excavation.ExcavationSpec;
import dev.friendai.task.Task;
import dev.friendai.task.TaskContext;
import dev.friendai.task.TaskState;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** Versioned checkpoint for {@link ExcavateTask}; the deterministic order is rebuilt from the spec. */
public final class ExcavationCheckpoints {
    private static final String VERSION = "v2";
    private static final int FIELDS = 28;

    private ExcavationCheckpoints() {}

    static String encode(ExcavateTask task) {
        ExcavationSpec spec = task.spec();
        ExcavationGeometry g = spec.geometry();
        StringBuilder text = new StringBuilder(VERSION);
        append(text, g.shape().name());
        append(text, g.direction() == null ? "-" : g.direction().name());
        append(text, g.x1()).append('|').append(g.y1()).append('|').append(g.z1());
        append(text, g.x2()).append('|').append(g.y2()).append('|').append(g.z2());
        append(text, g.length()).append('|').append(g.width()).append('|').append(g.height());
        append(text, g.branches()).append('|').append(g.spacing()).append('|').append(g.branchLength());
        append(text, spec.count());
        append(text, spec.untilItem() == null ? "-" : spec.untilItem());
        append(text, spec.untilCount());
        append(text, spec.collect())
                .append('|')
                .append(spec.containers())
                .append('|')
                .append(spec.allowLiquids());
        append(text, spec.requireTool()).append('|').append(spec.reserveDurability());
        append(text, task.nextIndex());
        append(text, task.progress().broken())
                .append('|')
                .append(task.progress().skipped());
        StringBuilder taken = new StringBuilder();
        int entries = 0;
        for (Map.Entry<String, Integer> entry : task.progress().taken().entrySet()) {
            if (entries++ >= 64) {
                break;
            }
            if (taken.length() > 0) {
                taken.append(',');
            }
            taken.append(entry.getKey()).append('=').append(entry.getValue());
        }
        text.append('|').append(taken);
        append(text, task.fallbackDescents());
        return text.toString();
    }

    private static StringBuilder append(StringBuilder text, Object value) {
        return text.append('|').append(value);
    }

    public static Optional<Task> excavate(TaskState state, TaskContext context) {
        if (state == null || !"excavate".equals(state.type()) || state.data().isBlank()) {
            return Optional.empty();
        }
        try {
            String[] p = state.data().split("\\|", -1);
            if (p.length != FIELDS || !VERSION.equals(p[0])) {
                return Optional.empty();
            }
            ExcavationShape shape = ExcavationShape.parse(p[1]).orElse(null);
            if (shape == null) {
                return Optional.empty();
            }
            ExcavationDirection direction =
                    "-".equals(p[2]) ? null : ExcavationDirection.parse(p[2]).orElse(null);
            if (!"-".equals(p[2]) && direction == null) {
                return Optional.empty();
            }
            ExcavationGeometry geometry = new ExcavationGeometry(
                    shape,
                    intValue(p[3]),
                    intValue(p[4]),
                    intValue(p[5]),
                    intValue(p[6]),
                    intValue(p[7]),
                    intValue(p[8]),
                    direction,
                    intValue(p[9]),
                    intValue(p[10]),
                    intValue(p[11]),
                    intValue(p[12]),
                    intValue(p[13]),
                    intValue(p[14]));
            String untilItem = "-".equals(p[16]) ? null : p[16];
            ExcavationSpec spec = ExcavationSpec.of(
                            geometry,
                            intValue(p[15]),
                            untilItem,
                            intValue(p[17]),
                            Boolean.parseBoolean(p[18]),
                            Boolean.parseBoolean(p[19]),
                            Boolean.parseBoolean(p[20]),
                            Boolean.parseBoolean(p[21]),
                            intValue(p[22]))
                    .orElse(null);
            if (spec == null) {
                return Optional.empty();
            }
            ExcavationProgress progress = new ExcavationProgress();
            progress.restore(intValue(p[24]), intValue(p[25]), taken(p[26]));
            return Optional.of(ExcavateTask.resume(spec, intValue(p[23]), progress, intValue(p[27])));
        } catch (RuntimeException invalid) {
            return Optional.empty();
        }
    }

    private static Map<String, Integer> taken(String text) {
        Map<String, Integer> taken = new LinkedHashMap<>();
        if (text.isBlank()) {
            return taken;
        }
        for (String part : text.split(",")) {
            int equals = part.lastIndexOf('=');
            if (equals <= 0 || equals == part.length() - 1) {
                throw new IllegalArgumentException("bad taken entry");
            }
            taken.put(part.substring(0, equals), Integer.parseInt(part.substring(equals + 1)));
        }
        return taken;
    }

    private static int intValue(String value) {
        return Integer.parseInt(value);
    }
}
