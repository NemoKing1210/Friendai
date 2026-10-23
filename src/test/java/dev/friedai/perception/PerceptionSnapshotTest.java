package dev.friedai.perception;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PerceptionSnapshotTest {
    @Test
    void formatsAllSections() {
        Map<String, Integer> blocks = new LinkedHashMap<>();
        blocks.put("oak_log", 12);
        blocks.put("poppy", 3);
        PerceptionSnapshot snapshot = new PerceptionSnapshot(
                "10 64 -5", "overworld", 20f, 20f, "day", "plains", "clear", 3.25,
                blocks, Map.of("zombie", 2), Map.of(), "Wearing: nothing; holding: nothing; combat mode: defensive", "follow");
        String text = snapshot.toPromptText();
        assertTrue(text.contains("Position: 10 64 -5"), text);
        assertTrue(text.contains("Health: 20/20"), text);
        assertTrue(text.contains("Nearby blocks: oak_log×12, poppy×3"), text);
        assertTrue(text.contains("Nearby entities: zombie×2"), text);
        assertTrue(text.contains("Inventory: empty"), text);
        assertTrue(text.contains("combat mode: defensive"), text);
        assertTrue(text.contains("Current task: follow"), text);
        assertTrue(text.contains("3.3 blocks away") || text.contains("3.2 blocks away"), text);
    }

    @Test
    void missingOwnerAndEmptySurroundings() {
        PerceptionSnapshot snapshot = new PerceptionSnapshot(
                "0 0 0", "the_nether", 7.5f, 20f, "night", "nether_wastes", "clear", null,
                Map.of(), Map.of(), Map.of(), "Wearing: nothing; holding: nothing; combat mode: defensive", "none");
        String text = snapshot.toPromptText();
        assertTrue(text.contains("Owner: not nearby"), text);
        assertTrue(text.contains("Health: 7.5/20"), text);
        assertTrue(text.contains("Nearby blocks: none"), text);
    }
}
