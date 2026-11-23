package dev.friendai.agent.goal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.friendai.execution.queue.Guard;
import dev.friendai.execution.queue.PlanDefinition;
import dev.friendai.execution.queue.PlanNode;
import dev.friendai.execution.queue.QueuedStep;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class GoalCoverageTest {
    private static final String PICK = "minecraft:stone_pickaxe";
    private static final String AXE = "minecraft:stone_axe";
    private static final String SHOVEL = "minecraft:stone_shovel";
    private static final String SWORD = "minecraft:stone_sword";

    private static PlanDefinition plan(QueuedStep... steps) {
        return new PlanDefinition(java.util.Arrays.stream(steps)
                .map(step -> (PlanNode) new PlanNode.Action(step))
                .toList());
    }

    private static QueuedStep step(String tool, String json) {
        return new QueuedStep(
                tool,
                com.google.gson.JsonParser.parseString(json).getAsJsonObject(),
                0,
                List.of(),
                null,
                null,
                1,
                List.of());
    }

    private static Criterion criterion(String text, Guard guard) {
        return new Criterion(text, Optional.ofNullable(guard));
    }

    @Test
    void oneToolCannotCoverAFourToolOrderEvenWhenTheModelSaysSo() {
        OrderIntent intent = OrderIntent.parse("make one stone tool set for each of us");
        List<Criterion> narrow = List.of(criterion("one pickaxe", new Guard.HandedOver(PICK, 1, 10)));

        var result = GoalCoverage.checkProof(intent, narrow);

        assertFalse(result.covered());
        assertEquals(4, result.missing().size(), result.describeMissing());
    }

    @Test
    void aPlanWithOneDeliveryDoesNotCoverAFourToolOrder() {
        OrderIntent intent = OrderIntent.parse("make one stone tool set for each of us");
        var plan = plan(step("give_items", "{\"item\":\"" + PICK + "\",\"count\":1,\"retain\":1,\"strict\":true}"));

        var result = GoalCoverage.checkPlan(intent, plan);

        assertFalse(result.covered());
        assertEquals(3, result.missing().size(), result.describeMissing());
    }

    @Test
    void fourStrictTransfersCoverTheKitAndKeepTheRetainedShare() {
        OrderIntent intent = OrderIntent.parse("make one stone tool set for each of us");
        QueuedStep[] steps = List.of(PICK, AXE, SHOVEL, SWORD).stream()
                .map(item -> step("give_items", "{\"item\":\"" + item + "\",\"count\":1,\"retain\":1,\"strict\":true}"))
                .toArray(QueuedStep[]::new);

        var result = GoalCoverage.checkPlan(intent, plan(steps));

        assertTrue(result.covered(), result.describeMissing());
    }

    @Test
    void aPartialDeliveryIsNarrowerThanTheRequestedCount() {
        OrderIntent intent = OrderIntent.parse("give me 8 bread");
        var plan = plan(step("give_items", "{\"item\":\"minecraft:bread\",\"count\":4}"));

        var result = GoalCoverage.checkPlan(intent, plan);

        assertFalse(result.covered());
        assertTrue(result.describeMissing().contains("owner 8"), result.describeMissing());
    }

    @Test
    void aBuildMissingARequestedPartIsRefused() {
        OrderIntent intent = OrderIntent.parse("build a small house with a roof and a door");
        JsonArray parts = new JsonArray();
        parts.add("floor");
        parts.add("walls");
        JsonObject args = new JsonObject();
        args.add("parts", parts);
        args.addProperty("generator", "{\"type\":\"house\"}");
        var plan = plan(new QueuedStep("build_structure", args, 0, List.of(), null, null, 1, List.of()));

        var result = GoalCoverage.checkPlan(intent, plan);

        assertFalse(result.covered());
        assertTrue(result.describeMissing().contains("roof"), result.describeMissing());
    }

    @Test
    void aPossessionQuestionNeedsOnlyTheOwnerInventoryCriterion() {
        OrderIntent intent = OrderIntent.parse("do I have 3 diamonds?");
        var result = GoalCoverage.checkProof(
                intent, List.of(criterion("owner holds diamonds", new Guard.Possesses("minecraft:diamond", 3))));

        assertTrue(result.covered(), result.describeMissing());
    }

    /** A queued step is intent, not evidence: only a hand-over or pickup can complete an order. */
    @Test
    void aQueuedDeliveryStepDoesNotProveTheOwnerReceivedIt() {
        OrderIntent intent = OrderIntent.parse("give me 8 bread");
        var plan = plan(step("give_items", "{\"item\":\"minecraft:bread\",\"count\":8}"));

        assertTrue(GoalCoverage.checkPlan(intent, plan).covered(), "the plan admits the full quantity");
        assertFalse(
                GoalCoverage.checkProof(intent, List.of()).covered(),
                "until the hand-over is recorded the order is not proved");
    }

    /** Existing stock answers "do I have", never "bring me": the two orders read different guards. */
    @Test
    void aPossessionGuardCannotCompleteADeliveryOrder() {
        OrderIntent intent = OrderIntent.parse("bring me 2 diamonds");
        Criterion existingStock = criterion("owner has diamonds", new Guard.Possesses("minecraft:diamond", 2));

        var result = GoalCoverage.checkProof(intent, List.of(existingStock));

        assertFalse(result.covered(), result.describeMissing());
        assertTrue(result.describeMissing().contains("no proven delivery"), result.describeMissing());
    }

    /** A build order closes on a block the world can inspect, not on the model's report. */
    @Test
    void aBuildOrderNeedsABlockCriterionAtCompletion() {
        OrderIntent intent = OrderIntent.parse("build a small house with a roof and a door");
        Criterion narrative = criterion("the house looks right", null);
        Criterion block = criterion("a plank at the origin", new Guard.BlockAt("minecraft:oak_planks", 1, 64, 1));

        var withoutBlocks = GoalCoverage.checkProof(intent, List.of(narrative));
        var withBlocks = GoalCoverage.checkProof(intent, List.of(block));

        assertFalse(withoutBlocks.covered(), "a free-form criterion proves no structure");
        assertTrue(withoutBlocks.describeMissing().contains("no block criterion"), withoutBlocks.describeMissing());
        assertTrue(withBlocks.covered(), withBlocks.describeMissing());
    }
}
