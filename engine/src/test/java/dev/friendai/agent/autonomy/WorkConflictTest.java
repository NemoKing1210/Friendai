package dev.friendai.agent.autonomy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.friendai.execution.queue.FailurePolicy;
import dev.friendai.execution.queue.GuardAction;
import dev.friendai.execution.queue.Job;
import dev.friendai.execution.queue.JobState;
import java.util.List;
import org.junit.jupiter.api.Test;

class WorkConflictTest {
    @Test
    void theSameToolOnTheSameTargetIsADuplicate() {
        List<Job> queued = List.of(job(1, "go_to_place", args("name", "home"), JobState.PENDING));
        assertEquals(
                "go_to_place home",
                WorkConflict.duplicate(queued, List.of(new ReflexStep("go_to_place", args("name", "home"))))
                        .orElseThrow());
    }

    @Test
    void aDifferentCountIsStillTheSameTarget() {
        JsonObject bigger = args("item", "minecraft:torch");
        bigger.addProperty("count", 16);
        List<Job> queued = List.of(job(1, "obtain_item", args("item", "minecraft:torch"), JobState.RUNNING));
        assertTrue(
                WorkConflict.duplicate(queued, List.of(new ReflexStep("obtain_item", bigger)))
                        .isPresent(),
                "the target, not the amount, is what makes work the same");
    }

    @Test
    void anotherTargetOrToolIsNotADuplicate() {
        List<Job> queued = List.of(job(1, "go_to_place", args("name", "home"), JobState.PENDING));
        assertTrue(WorkConflict.duplicate(queued, List.of(new ReflexStep("go_to_place", args("name", "mine"))))
                .isEmpty());
        assertTrue(WorkConflict.duplicate(queued, List.of(new ReflexStep("sleep", new JsonObject())))
                .isEmpty());
    }

    @Test
    void theSameBatchItemIsADuplicateAndTheRefusalNamesIt() {
        List<Job> queued = List.of(job(1, "obtain_items", batch("minecraft:diamond"), JobState.RUNNING));

        assertEquals(
                "obtain_items minecraft:diamond",
                WorkConflict.duplicate(queued, List.of(new ReflexStep("obtain_items", batch("minecraft:diamond"))))
                        .orElseThrow());
    }

    @Test
    void aDifferentBatchItemIsNotADuplicate() {
        List<Job> queued = List.of(job(1, "obtain_items", batch("minecraft:diamond"), JobState.RUNNING));

        assertTrue(
                WorkConflict.duplicate(queued, List.of(new ReflexStep("obtain_items", batch("minecraft:stone"))))
                        .isEmpty(),
                "an unrelated item is still free work");
    }

    @Test
    void aMixedItemAndBatchPairResolvesTheSameWay() {
        List<Job> queued = List.of(job(1, "obtain_items", batch("minecraft:diamond"), JobState.RUNNING));

        assertEquals(
                "obtain_items minecraft:diamond",
                WorkConflict.duplicate(
                                queued, List.of(new ReflexStep("obtain_items", args("item", "minecraft:diamond"))))
                        .orElseThrow());
        assertTrue(
                WorkConflict.duplicate(queued, List.of(new ReflexStep("obtain_items", args("item", "minecraft:stone"))))
                        .isEmpty(),
                "the single-item form names the same set as the batch form");
    }

    @Test
    void overlappingBatchItemsCollideOnTheSharedItem() {
        List<Job> queued =
                List.of(job(1, "obtain_items", batch("minecraft:diamond", "minecraft:iron_ingot"), JobState.RUNNING));

        assertEquals(
                "obtain_items minecraft:diamond, minecraft:iron_ingot",
                WorkConflict.duplicate(
                                queued,
                                List.of(new ReflexStep(
                                        "obtain_items", batch("minecraft:iron_ingot", "minecraft:gold_ingot"))))
                        .orElseThrow(),
                "a batch collides when it shares at least one requested item");
    }

    @Test
    void anotherCompanionsLiveQueueAlsoHoldsTheStep() {
        List<Job> other = List.of(job(1, "mine_block", args("name", "oak_log"), JobState.RUNNING));
        assertEquals(
                "mine_block oak_log",
                WorkConflict.duplicateAcross(
                                List.of(),
                                List.of(other),
                                List.of(new ReflexStep("mine_block", args("name", "oak_log"))))
                        .orElseThrow());
        assertTrue(
                WorkConflict.duplicateAcross(
                                List.of(),
                                List.of(other),
                                List.of(new ReflexStep("mine_block", args("name", "birch_log"))))
                        .isEmpty(),
                "different targets never conflict");
    }

    @Test
    void targetlessActionsInAnotherCompanionsQueueAreIndependent() {
        List<Job> other = List.of(job(1, "jump", new JsonObject(), JobState.RUNNING));

        assertTrue(
                WorkConflict.duplicateAcross(
                                List.of(), List.of(other), List.of(new ReflexStep("jump", new JsonObject())))
                        .isEmpty(),
                "an unscoped action in another owner's queue has no shared target");
    }

    @Test
    void finishedWorkIsNotAReasonToHoldBack() {
        for (JobState state : List.of(JobState.DONE, JobState.FAILED, JobState.SKIPPED, JobState.CANCELLED)) {
            List<Job> queued = List.of(job(1, "stay", new JsonObject(), state));
            assertTrue(
                    WorkConflict.duplicate(queued, List.of(new ReflexStep("stay", new JsonObject())))
                            .isEmpty(),
                    state + " is not live work");
        }
    }

    private static JsonObject args(String key, String value) {
        JsonObject args = new JsonObject();
        args.addProperty(key, value);
        return args;
    }

    private static JsonObject batch(String... itemIds) {
        JsonArray items = new JsonArray();
        for (String itemId : itemIds) {
            JsonObject entry = new JsonObject();
            entry.addProperty("item", itemId);
            entry.addProperty("count", 1);
            items.add(entry);
        }
        JsonObject args = new JsonObject();
        args.add("items", items);
        return args;
    }

    private static Job job(long id, String tool, JsonObject args, JobState state) {
        return new Job(
                id,
                0,
                tool,
                args,
                0,
                false,
                id,
                state,
                List.of(),
                GuardAction.WAIT,
                FailurePolicy.ASK,
                1,
                0,
                List.of(),
                "",
                0);
    }
}
