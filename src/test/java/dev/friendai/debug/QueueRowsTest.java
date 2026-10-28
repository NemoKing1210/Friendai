package dev.friendai.debug;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import dev.friendai.queue.FailurePolicy;
import dev.friendai.queue.Guard;
import dev.friendai.queue.GuardAction;
import dev.friendai.queue.GuardFacts;
import dev.friendai.queue.JobQueue;
import dev.friendai.queue.PlanOptions;
import dev.friendai.queue.QueueLimits;
import dev.friendai.queue.QueuedStep;
import java.util.List;
import org.junit.jupiter.api.Test;

class QueueRowsTest {
    private static final GuardFacts FACTS = new GuardFacts() {
        @Override
        public int countItems(String idOrTag) {
            return 0;
        }

        @Override
        public int emptySlots() {
            return 10;
        }

        @Override
        public float healthFraction() {
            return 1;
        }

        @Override
        public int ownerDistance() {
            return 0;
        }

        @Override
        public int countBlocks(String idOrTag, int radius) {
            return 0;
        }

        @Override
        public String dimension() {
            return "minecraft:overworld";
        }
    };

    private static QueuedStep step(String tool, List<Guard> guards, List<Integer> after) {
        return new QueuedStep(tool, new JsonObject(), null, guards, GuardAction.WAIT, FailurePolicy.ASK, 1, after);
    }

    @Test
    void emptyQueueHasNoRows() {
        assertTrue(
                QueueRows.build(new JobQueue(QueueLimits.DEFAULTS), FACTS, null).isEmpty());
    }

    @Test
    void directJobsComeBeforePlansAndPlanStepsKeepTheirOrder() {
        JobQueue q = new JobQueue(QueueLimits.DEFAULTS);
        List<Long> ids = q.addPlan(
                        "build",
                        List.of(
                                step("mine_blocks", List.of(), List.of()),
                                step("place_block", List.of(new Guard.HasItem("minecraft:stone", 3)), List.of(1))),
                        PlanOptions.DEFAULT)
                .jobIds();
        long direct = q.addDirect(step("go_to", List.of(), List.of())).id();
        q.markRunning(ids.getFirst());

        List<QueueRow> rows = QueueRows.build(q, FACTS, "mining stone");

        assertEquals(
                List.of(direct, ids.get(0), ids.get(1)),
                rows.stream().map(QueueRow::id).toList());
        assertEquals("ready", rows.get(0).state());
        assertEquals("", rows.get(0).planGoal());
        assertEquals("running", rows.get(1).state());
        assertEquals("mining stone", rows.get(1).note());
        assertEquals("build", rows.get(1).planGoal());
        assertEquals("blocked", rows.get(2).state());
        assertTrue(rows.get(2).note().contains("depends on #" + ids.get(0)));
        assertEquals(List.of(ids.get(0)), rows.get(2).after());
    }

    @Test
    void rowCountIsBounded() {
        JobQueue q = new JobQueue(new QueueLimits(QueueRows.MAX_ROWS + 20, 4, 8, 4096, 4));
        for (int i = 0; i < QueueRows.MAX_ROWS + 20; i++) {
            q.addDirect(step("stay", List.of(), List.of()));
        }
        assertEquals(QueueRows.MAX_ROWS, QueueRows.build(q, FACTS, null).size());
    }
}
