package dev.friendai.features.farming;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FarmReportTest {
    @Test
    void shortfallEndsTheLine() {
        FarmReport report = new FarmReport(Map.of(), 0, 2, 0, 0, false, Map.of("minecraft:wheat_seeds", 3), 3);
        assertEquals("planted 2 new; need 3 minecraft:wheat_seeds", report.text(8));
    }

    @Test
    void shortfallFollowsExistingClauses() {
        FarmReport report = new FarmReport(Map.of(), 0, 0, 0, 2, true, Map.of("minecraft:wheat_seeds", 3), 2);
        assertEquals(
                "nothing to harvest or plant within 8 blocks; no seeds for 2 empty farmland; no hoe to till with"
                        + "; need 3 minecraft:wheat_seeds",
                report.text(8));
    }

    @Test
    void shortfallKeepsInsertionOrder() {
        Map<String, Integer> shortfall = new LinkedHashMap<>();
        shortfall.put("minecraft:carrot", 1);
        shortfall.put("minecraft:wheat_seeds", 2);
        FarmReport report = new FarmReport(Map.of(), 0, 0, 0, 0, false, shortfall, 3);
        assertEquals(
                "nothing to harvest or plant within 4 blocks; need 1 minecraft:carrot; need 2 minecraft:wheat_seeds",
                report.text(4));
    }

    @Test
    void sixArgumentConstructorKeepsTheOldText() {
        assertEquals(
                "harvested 3 minecraft:wheat", new FarmReport(Map.of("minecraft:wheat", 3), 0, 0, 0, 0, false).text(8));
        assertEquals("replanted 1; planted 2 new; tilled 3", new FarmReport(Map.of(), 1, 2, 3, 0, false).text(8));
        assertEquals(
                "nothing to harvest or plant within 5 blocks", new FarmReport(Map.of(), 0, 0, 0, 0, false).text(5));
    }
}
