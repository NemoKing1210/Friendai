package dev.friendai.execution.queue;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import java.util.List;
import org.junit.jupiter.api.Test;

class PlanConsentTest {
    private static QueuedStep step() {
        return new QueuedStep(
                "wait", new JsonObject(), 0, List.of(), GuardAction.WAIT, FailurePolicy.ASK, 1, List.of());
    }

    @Test
    void repairConsentDefaultsOnAndCanBeTurnedOff() {
        assertTrue(PlanOptions.DEFAULT.autoRepair());
        assertTrue(new PlanOptions(0, FailurePolicy.ASK, 0).autoRepair(), "legacy shape defaults on");
        assertFalse(new PlanOptions(0, FailurePolicy.ASK, 0, false).autoRepair());
    }

    @Test
    void thePlanCarriesConsentAndSurvivesPriorityAndSave() {
        JobQueue queue = new JobQueue(QueueLimits.DEFAULTS);
        long planId = queue.addPlan("p", List.of(step()), new PlanOptions(0, FailurePolicy.ASK, 0, false))
                .planId();
        PlanRecord plan = queue.plan(planId).orElseThrow();
        assertFalse(plan.autoRepair());
        assertNotNull(plan.definition(), "a flat plan has a definition");

        queue.setPlanPriority(planId, 7);
        PlanRecord prioritized = queue.plan(planId).orElseThrow();
        assertFalse(prioritized.autoRepair(), "priority must not reset consent");
        assertNotNull(prioritized.definition(), "priority must not drop the definition");
        assertNull(prioritized.cursor(), "a flat plan keeps its null cursor");

        JsonObject saved = QueueSave.encode(queue, 0);
        JobQueue loaded =
                QueueSave.decode(saved, QueueLimits.DEFAULTS).orElseThrow().queue();
        assertFalse(loaded.plan(planId).orElseThrow().autoRepair(), "consent survives save/load");
    }
}
