package dev.friendai.queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import java.util.List;
import org.junit.jupiter.api.Test;

class QueueSaveTest {
    @Test
    void roundTripsQueueAndActiveSave() {
        JobQueue q = new JobQueue(QueueLimits.DEFAULTS);
        long id = q.addPlan("build", List.of(new QueuedStep("place_block", new JsonObject(), null,
                List.of(new Guard.HasItem("minecraft:stone", 2)), GuardAction.WAIT, null, 1, List.of())), PlanOptions.DEFAULT).jobIds().getFirst();
        q.markRunning(id);
        String saved = QueueSave.encode(q, "build:progress");
        QueueSave.Decoded decoded = QueueSave.decode(saved, QueueLimits.DEFAULTS).orElseThrow();
        assertEquals("queue:v1:", saved.substring(0, 9));
        assertEquals("build", decoded.queue().plan(1).orElseThrow().goal());
        assertEquals(id, decoded.activeJobId());
        assertEquals("build:progress", decoded.activeTaskSave());
    }

    @Test
    void rejectsBrokenOrUnknownVersions() {
        assertTrue(QueueSave.decode("queue:v1:{bad", QueueLimits.DEFAULTS).isEmpty());
        assertTrue(QueueSave.decode("queue:v2:{}", QueueLimits.DEFAULTS).isEmpty());
    }
}
