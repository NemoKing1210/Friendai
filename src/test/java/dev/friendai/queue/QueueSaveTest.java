package dev.friendai.queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import java.util.List;
import java.util.UUID;
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

    @Test
    void roundTripsInitiatingPrincipalWithoutPersistingAuthority() {
        UUID principal = UUID.fromString("46fab118-0a82-4f7e-9e7b-a9b48bf34827");
        QueuedStep step = new QueuedStep("place_block", new JsonObject(), null, List.of(),
                GuardAction.WAIT, FailurePolicy.STOP_PLAN, 1, List.of(), principal, false);
        JobQueue queue = new JobQueue(QueueLimits.DEFAULTS);
        queue.addDirect(step);

        String saved = QueueSave.encode(queue, null);
        Job restored = QueueSave.decode(saved, QueueLimits.DEFAULTS).orElseThrow()
                .queue().job(1).orElseThrow();

        assertEquals(principal, restored.principal());
        assertTrue(!restored.creativeSensitive());
        assertTrue(saved.contains(principal.toString()));
        assertTrue(!saved.contains("access"));
    }

    @Test
    void oldQueueSaveHasNoInitiatingPrincipal() {
        JobQueue queue = new JobQueue(QueueLimits.DEFAULTS);
        queue.addDirect(new QueuedStep("place_block", new JsonObject(), null, List.of(),
                GuardAction.WAIT, FailurePolicy.STOP_PLAN, 1, List.of()));

        Job restored = QueueSave.decode(QueueSave.encode(queue, null), QueueLimits.DEFAULTS)
                .orElseThrow().queue().job(1).orElseThrow();

        assertEquals(null, restored.principal());    }
}
