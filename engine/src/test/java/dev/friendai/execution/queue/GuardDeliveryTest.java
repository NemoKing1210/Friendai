package dev.friendai.execution.queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GuardDeliveryTest {
    private static JsonObject json(String text) {
        return JsonParser.parseString(text).getAsJsonObject();
    }

    /** An owner holding a fixed count of oak logs and one flag for a block at any position. */
    private record OwnerWorld(int ownerCount, boolean blockPresent, int[] lastPosition) implements GuardFacts {
        @Override
        public int countItems(String idOrTag) {
            return 0;
        }

        @Override
        public int emptySlots() {
            return 0;
        }

        @Override
        public float healthFraction() {
            return 0;
        }

        @Override
        public int ownerDistance() {
            return -1;
        }

        @Override
        public int countBlocks(String idOrTag, int radius) {
            return 0;
        }

        @Override
        public String dimension() {
            return "minecraft:overworld";
        }

        @Override
        public int ownerCountItems(String idOrTag) {
            return idOrTag.equals("minecraft:oak_log") ? ownerCount : 0;
        }

        @Override
        public boolean blockAt(String idOrTag, int x, int y, int z) {
            lastPosition[0] = x;
            lastPosition[1] = y;
            lastPosition[2] = z;
            return blockPresent && idOrTag.equals("minecraft:oak_planks");
        }
    }

    @Test
    void deliveredHoldsWhenTheOwnerHoldsEnough() {
        Guard guard = Guard.parse(json("{\"type\":\"delivered\",\"item\":\"minecraft:oak_log\",\"count\":12}"))
                .orElseThrow();
        assertEquals("deliver 12 minecraft:oak_log to the owner", guard.describe());
        assertTrue(guard.holds(new OwnerWorld(12, false, new int[3])));
        assertFalse(guard.holds(new OwnerWorld(11, false, new int[3])));
        assertEquals(guard, Guard.parse(guard.toJson()).orElseThrow());
    }

    @Test
    void blockAtHoldsOnlyAtTheNamedPosition() {
        Guard guard = Guard.parse(
                        json("{\"type\":\"block_at\",\"block\":\"minecraft:oak_planks\",\"x\":10,\"y\":64,\"z\":-3}"))
                .orElseThrow();
        assertEquals("minecraft:oak_planks at 10 64 -3", guard.describe());
        int[] position = new int[3];
        assertTrue(guard.holds(new OwnerWorld(0, true, position)));
        assertEquals(10, position[0]);
        assertEquals(64, position[1]);
        assertEquals(-3, position[2]);
        assertFalse(guard.holds(new OwnerWorld(0, false, position)));
        assertEquals(guard, Guard.parse(guard.toJson()).orElseThrow());
    }

    @Test
    void malformedPositionsAndCountsAreRejectedWithoutThrowing() {
        for (String invalid : List.of(
                "{\"type\":\"delivered\",\"item\":\"minecraft:oak_log\"}",
                "{\"type\":\"delivered\",\"item\":\"\",\"count\":2}",
                "{\"type\":\"delivered\",\"item\":\"minecraft:oak_log\",\"count\":0}",
                "{\"type\":\"block_at\",\"block\":\"minecraft:oak_planks\",\"x\":1,\"y\":2}",
                "{\"type\":\"block_at\",\"block\":\"\",\"x\":1,\"y\":2,\"z\":3}",
                "{\"type\":\"block_at\",\"block\":\"minecraft:oak_planks\",\"x\":40000000,\"y\":2,\"z\":3}",
                "{\"type\":\"block_at\",\"block\":\"minecraft:oak_planks\",\"x\":1,\"y\":2,\"z\":3.5}")) {
            assertTrue(Guard.parse(json(invalid)).isEmpty(), invalid);
        }
    }

    @Test
    void unknownBlockIdsAndMissingOwnerDataJustDoNotHold() {
        GuardFacts empty = new FakeGuardFacts(Map.of(), 0, 0f, -1, Map.of(), "");
        assertEquals(0, empty.ownerCountItems("minecraft:oak_log"));
        assertFalse(empty.blockAt("minecraft:oak_planks", 0, 0, 0));
        assertFalse(new Guard.OwnerHasAtLeast("minecraft:oak_log", 1).holds(empty));
        assertFalse(new Guard.BlockAt("friendai:not_a_block", 0, 64, 0).holds(empty));
    }
}
