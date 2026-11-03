package dev.friendai.features.mining.minecraft.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.features.mining.excavation.ExcavationGeometry;
import dev.friendai.features.mining.excavation.ExcavationProgress;
import dev.friendai.features.mining.excavation.ExcavationSpec;
import dev.friendai.task.Task;
import dev.friendai.task.TaskState;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ExcavationCheckpointsTest {
    @Test
    void roundTripKeepsTheRegionIndexAndCounters() {
        ExcavationSpec spec = ExcavationSpec.of(
                        ExcavationGeometry.box(0, 0, 0, 2, 1, 1).orElseThrow(), 5, null, 0, true, false, false, true, 0)
                .orElseThrow();
        ExcavateTask task = new ExcavateTask(spec, 3, progress(2, 1, "minecraft:cobblestone", 4));

        String data = task.saveData();
        Optional<Task> restored = ExcavationCheckpoints.excavate(new TaskState("excavate", data), null);

        assertTrue(restored.isPresent());
        ExcavateTask resumed = (ExcavateTask) restored.get();
        assertEquals(5, resumed.spec().count());
        assertEquals(3, resumed.nextIndex());
        assertEquals(2, resumed.progress().broken());
        assertEquals(1, resumed.progress().skipped());
        assertEquals(4, resumed.progress().taken().get("minecraft:cobblestone"));
    }

    @Test
    void rejectsAnUnknownVersionOrMalformedPayload() {
        assertTrue(ExcavationCheckpoints.excavate(new TaskState("excavate", "v2|box"), null)
                .isEmpty());
        assertTrue(ExcavationCheckpoints.excavate(new TaskState("excavate", "v1|box"), null)
                .isEmpty());
        assertFalse(ExcavationCheckpoints.excavate(new TaskState("excavate", ""), null)
                .isPresent());
    }

    private static ExcavationProgress progress(int broken, int skipped, String item, int count) {
        ExcavationProgress progress = new ExcavationProgress();
        for (int i = 0; i < broken; i++) {
            progress.markBroken();
        }
        for (int i = 0; i < skipped; i++) {
            progress.markSkipped("protected");
        }
        progress.addTaken(item, count);
        return progress;
    }
}
