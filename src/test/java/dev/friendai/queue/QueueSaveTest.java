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

    @Test
    void serverTradingScopeMarkerSurvivesQueueSerializationForRestartInvalidation() {
        JobQueue q = new JobQueue(QueueLimits.DEFAULTS);
        JsonObject args = new JsonObject();
        String scope = "00000000-0000-0000-0000-000000000123";
        args.addProperty("_trade_scope", scope);
        long id = q.addDirect(new QueuedStep("obtain_item", args, null, List.of(), GuardAction.WAIT,
                FailurePolicy.ASK, 1, List.of())).id();
        q.markRunning(id);

        QueueSave.Decoded restored = QueueSave.decode(QueueSave.encode(q, ""), QueueLimits.DEFAULTS).orElseThrow();
        assertEquals(scope, restored.queue().job(id).orElseThrow().args().get("_trade_scope").getAsString());
    }

    @Test
    void scopedQueueUpdateAndManualRetryKeepTheOriginalServerMarker() {
        JobQueue q = new JobQueue(QueueLimits.DEFAULTS);
        JsonObject args = new JsonObject();
        String scope = "00000000-0000-0000-0000-000000000123";
        args.addProperty("_trade_scope", scope);
        args.addProperty("count", 1);
        long id = q.addDirect(new QueuedStep("obtain_item", args, null, List.of(), GuardAction.WAIT,
                FailurePolicy.ASK, 1, List.of())).id();

        JsonObject update = new JsonObject();
        update.addProperty("_trade_scope", scope);
        update.addProperty("count", 2);
        assertTrue(q.updateArgs(id, update).ok());
        q.markRunning(id);
        q.fail(id, "temporary failure");
        assertTrue(q.retry(id).ok());

        var retried = q.job(id).orElseThrow();
        assertEquals(scope, retried.args().get("_trade_scope").getAsString());
        assertEquals(2, retried.args().get("count").getAsInt());
    }
}
