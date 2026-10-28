package dev.friendai.features.knowledge.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SearchStatsTest {
    @Test
    void countersAggregateFindsMissesAndWork() {
        SearchStats stats = new SearchStats();
        stats.record("minecraft:iron_ore", true, 10, 100, 4);
        stats.record("minecraft:iron_ore", false, 6, 50, 2);
        SearchStats.Counters counters = stats.counters("minecraft:iron_ore");
        assertEquals(1, counters.finds());
        assertEquals(1, counters.misses());
        assertEquals(16, counters.travelBlocks());
        assertEquals(150, counters.ticks());
        assertEquals(6, counters.blocksPerFind());
    }

    @Test
    void jsonRoundTripKeepsEveryKind() {
        SearchStats stats = new SearchStats();
        stats.record("structure:minecraft:village", true, 20, 200, 0);
        SearchStats restored = SearchStats.fromJson(stats.toJson());
        assertEquals(
                stats.counters("structure:minecraft:village").finds(),
                restored.counters("structure:minecraft:village").finds());
        assertTrue(restored.summary().contains("finds"));
    }

    @Test
    void brokenRowDoesNotInvalidateTheRest() {
        var json = new com.google.gson.JsonObject();
        var broken = new com.google.gson.JsonObject();
        broken.addProperty("finds", -1);
        json.add("broken", broken);
        var good = new com.google.gson.JsonObject();
        good.addProperty("finds", 2);
        json.add("good", good);
        SearchStats restored = SearchStats.fromJson(json);
        assertEquals(2, restored.counters("good").finds());
        assertTrue(restored.kinds().stream().noneMatch(kind -> kind.equals("broken")));
    }
}
