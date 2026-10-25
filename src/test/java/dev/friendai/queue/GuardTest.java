package dev.friendai.queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GuardTest {
    private static JsonObject json(String text) {
        return JsonParser.parseString(text).getAsJsonObject();
    }

    private static FakeGuardFacts facts(Map<String, Integer> items) {
        return new FakeGuardFacts(items, 2, 0.6f, 12, Map.of("minecraft:oak_log", 4), "minecraft:the_nether");
    }

    @Test
    void hasItemGuardParsesDescribesAndHolds() {
        Guard guard = Guard.parse(json("{\"type\":\"has_item\",\"item\":\"minecraft:iron_ingot\",\"count\":3}"))
                .orElseThrow();
        assertEquals("3 minecraft:iron_ingot", guard.describe());
        assertTrue(guard.holds(facts(Map.of("minecraft:iron_ingot", 3))));
        assertFalse(guard.holds(facts(Map.of("minecraft:iron_ingot", 2))));
    }

    @Test
    void everyGuardChecksItsFact() {
        assertTrue(Guard.parse(json("{\"type\":\"inventory_space\",\"slots\":2}")).orElseThrow().holds(facts(Map.of())));
        assertTrue(Guard.parse(json("{\"type\":\"health_at_least\",\"percent\":50}")).orElseThrow().holds(facts(Map.of())));
        assertTrue(Guard.parse(json("{\"type\":\"owner_within\",\"blocks\":16}")).orElseThrow().holds(facts(Map.of())));
        assertTrue(Guard.parse(json("{\"type\":\"block_within\",\"block\":\"minecraft:oak_log\",\"radius\":8,\"count\":4}")).orElseThrow().holds(facts(Map.of())));
        assertTrue(Guard.parse(json("{\"type\":\"dimension\",\"dimension\":\"minecraft:the_nether\"}")).orElseThrow().holds(facts(Map.of())));
    }

    @Test
    void rejectsUnknownTypeAndBadNumbers() {
        assertTrue(Guard.parse(json("{\"type\":\"nope\"}")).isEmpty());
        assertTrue(Guard.parse(json("{\"type\":\"health_at_least\",\"percent\":0}")).isEmpty());
        assertTrue(Guard.parse(json("{\"type\":\"has_item\",\"item\":\"\"}")).isEmpty());
        assertTrue(Guard.parse(json("{\"type\":\"block_within\",\"block\":\"minecraft:stone\",\"radius\":33}")).isEmpty());
    }

    @Test
    void blockWithinUsesTheEarlyExitFact() {
        Guard.BlockWithin guard = new Guard.BlockWithin("minecraft:stone", 8, 4);
        assertTrue(guard.holds(new FakeGuardFacts(Map.of(), 0, 1, 0, Map.of("minecraft:stone", 4), "minecraft:overworld")));
        assertFalse(guard.holds(new FakeGuardFacts(Map.of(), 0, 1, 0, Map.of("minecraft:stone", 3), "minecraft:overworld")));
    }

    @Test
    void toJsonRoundTrips() {
        for (String text : List.of(
                "{\"type\":\"has_item\",\"item\":\"minecraft:iron_ingot\",\"count\":3}",
                "{\"type\":\"inventory_space\",\"slots\":2}",
                "{\"type\":\"health_at_least\",\"percent\":50}",
                "{\"type\":\"owner_within\",\"blocks\":16}",
                "{\"type\":\"block_within\",\"block\":\"minecraft:oak_log\",\"radius\":8,\"count\":4}",
                "{\"type\":\"dimension\",\"dimension\":\"minecraft:the_nether\"}")) {
            Guard guard = Guard.parse(json(text)).orElseThrow();
            assertEquals(guard, Guard.parse(guard.toJson()).orElseThrow());
        }
    }

    @Test
    void queuedStepNormalizesNullsClampsAttemptsAndCopiesArgs() {
        JsonObject args = new JsonObject();
        args.addProperty("x", 1);
        QueuedStep step = new QueuedStep("mine_blocks", args, null, null, null, null, 99, null);
        args.addProperty("x", 2);
        assertEquals(1, step.args().get("x").getAsInt());
        assertEquals(List.of(), step.guards());
        assertEquals(GuardAction.WAIT, step.onUnmet());
        assertEquals(3, step.maxAttempts());
        assertEquals(1, new QueuedStep("go_to", null, null, null, null, null, 0, null).maxAttempts());
    }
}
