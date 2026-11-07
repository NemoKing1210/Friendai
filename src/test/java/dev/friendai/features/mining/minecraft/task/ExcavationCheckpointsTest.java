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
        ExcavateTask task = new ExcavateTask(spec, 3, progress(2, 1, "minecraft:cobblestone", 4), 2);

        String data = task.saveData();
        Optional<Task> restored = ExcavationCheckpoints.excavate(new TaskState("excavate", data), null);

        assertTrue(restored.isPresent());
        ExcavateTask resumed = (ExcavateTask) restored.get();
        assertEquals(5, resumed.spec().count());
        assertEquals(3, resumed.nextIndex());
        assertEquals(2, resumed.progress().broken());
        assertEquals(1, resumed.progress().skipped());
        assertEquals(4, resumed.progress().taken().get("minecraft:cobblestone"));
        assertEquals(2, resumed.fallbackDescents());
    }

    @Test
    void aPayloadFromBeforeTheDescentCounterReadsZeroDescents() {
        ExcavationSpec spec = ExcavationSpec.of(
                        ExcavationGeometry.box(0, 0, 0, 2, 1, 1).orElseThrow(), 5, null, 0, true, false, false, true, 0)
                .orElseThrow();
        ExcavateTask task = new ExcavateTask(spec, 1, progress(1, 0, "minecraft:cobblestone", 2), 3);
        String data = task.saveData();
        assertEquals(28, data.split("\\|", -1).length);
        String beforeTheCounter = data.substring(0, data.lastIndexOf('|'));
        assertEquals(27, beforeTheCounter.split("\\|", -1).length);

        Optional<Task> restored = ExcavationCheckpoints.excavate(new TaskState("excavate", beforeTheCounter), null);

        assertTrue(restored.isPresent());
        ExcavateTask resumed = (ExcavateTask) restored.get();
        assertEquals(1, resumed.nextIndex());
        assertEquals(1, resumed.progress().broken());
        assertEquals(2, resumed.progress().taken().get("minecraft:cobblestone"));
        assertEquals(0, resumed.fallbackDescents());
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
