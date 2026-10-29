package dev.friendai.agent.perception;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.features.combat.Threat;
import dev.friendai.features.knowledge.Landmark;
import dev.friendai.features.mining.tree.TreeSummary;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PerceptionSnapshotTest {
    @Test
    void promptReportsCreativeCapabilitiesAndKeepsSurvivalAsTheLegacyDefault() {
        PerceptionSnapshot creative = new PerceptionSnapshot(
                "0 64 0",
                "overworld",
                20f,
                20f,
                20,
                "day",
                "plains",
                "clear",
                null,
                Map.of(),
                Map.of(),
                Map.of(),
                "Wearing: nothing",
                "none",
                null,
                null,
                null,
                null,
                List.of(),
                17,
                true);
        String creativeText = creative.toPromptText();
        assertTrue(creativeText.contains("Mode: creative"), creativeText);
        assertTrue(creativeText.contains("Ordinary damage and hunger are disabled"), creativeText);
        assertTrue(
                creativeText.contains(
                        "flight, free materials, item creation and command execution require the online owner's live permissions"),
                creativeText);

        PerceptionSnapshot survival = new PerceptionSnapshot(
                "0 64 0",
                "overworld",
                20f,
                20f,
                20,
                "day",
                "plains",
                "clear",
                null,
                Map.of(),
                Map.of(),
                Map.of(),
                "Wearing: nothing",
                "none",
                null,
                null,
                null,
                null,
                List.of());
        assertTrue(survival.toPromptText().contains("Mode: survival"), survival.toPromptText());
        assertTrue(
                survival.toPromptText().contains("Normal damage, hunger, material use and ground movement apply"),
                survival.toPromptText());
    }

    @Test
    void reportsExperienceLevel() {
        PerceptionSnapshot snapshot = new PerceptionSnapshot(
                "0 64 0",
                "overworld",
                20f,
                20f,
                20,
                "day",
                "plains",
                "clear",
                null,
                Map.of(),
                Map.of(),
                Map.of(),
                "Wearing: nothing",
                "follow",
                null,
                null,
                null,
                null,
                List.of(),
                17);
        String text = snapshot.toPromptText();
        assertTrue(text.contains("Hunger: 20/20\nExperience level: 17\n"), text);
    }

    @Test
    void formatsAllSections() {
        Map<String, Integer> blocks = new LinkedHashMap<>();
        blocks.put("oak_log", 12);
        blocks.put("poppy", 3);
        PerceptionSnapshot snapshot = new PerceptionSnapshot(
                "10 64 -5",
                "overworld",
                20f,
                20f,
                14,
                "day",
                "plains",
                "clear",
                3.25,
                blocks,
                Map.of("zombie", 2),
                Map.of(),
                "Wearing: nothing; holding: nothing; combat mode: defensive",
                "follow",
                null,
                null,
                null,
                null,
                List.of());
        String text = snapshot.toPromptText();
        assertTrue(text.contains("Position: 10 64 -5"), text);
        assertTrue(text.contains("Health: 20/20"), text);
        assertTrue(text.contains("Health: 20/20\nHunger: 14/20\n"), text);
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
                "0 0 0",
                "the_nether",
                7.5f,
                20f,
                20,
                "night",
                "nether_wastes",
                "clear",
                null,
                Map.of(),
                Map.of(),
                Map.of(),
                "Wearing: nothing; holding: nothing; combat mode: defensive",
                "none",
                null,
                null,
                null,
                null,
                List.of());
        String text = snapshot.toPromptText();
        assertTrue(text.contains("Owner: not nearby"), text);
        assertTrue(text.contains("Health: 7.5/20"), text);
        assertTrue(text.contains("Nearby blocks: none"), text);
    }

    @Test
    void ownerFocusReplacesTheDistanceLine() {
        OwnerFocus focus = OwnerFocus.visible(
                "Owner",
                6.2,
                "10 64 -4",
                "grass_block at 10 63 -4",
                "nothing within 64 blocks",
                "nothing",
                "behind-right",
                "looking away from you",
                20f,
                20f,
                20,
                0,
                "ok");
        PerceptionSnapshot snapshot = new PerceptionSnapshot(
                "0 0 0",
                "overworld",
                20f,
                20f,
                20,
                "day",
                "plains",
                "clear",
                6.2,
                Map.of(),
                Map.of(),
                Map.of(),
                "Wearing: nothing; holding: nothing; combat mode: defensive",
                "none",
                focus,
                null,
                null,
                null,
                List.of());
        String text = snapshot.toPromptText();
        assertTrue(text.contains(focus.toPromptText() + "\nNearby blocks: none"), text);
        assertEquals(1, text.split("blocks away", -1).length - 1, text);
        assertFalse(text.contains("Owner: 6.2 blocks away\n"), text);
    }

    @Test
    void withoutOwnerFocusTheOldLineIsUnchanged() {
        PerceptionSnapshot snapshot = new PerceptionSnapshot(
                "0 0 0",
                "overworld",
                20f,
                20f,
                20,
                "day",
                "plains",
                "clear",
                6.25,
                Map.of(),
                Map.of(),
                Map.of(),
                "Wearing: nothing; holding: nothing; combat mode: defensive",
                "none",
                null,
                null,
                null,
                null,
                List.of());
        assertTrue(
                snapshot.toPromptText().contains("\nOwner: 6.2 blocks away\nNearby blocks: none\n")
                        || snapshot.toPromptText().contains("\nOwner: 6.3 blocks away\nNearby blocks: none\n"),
                snapshot.toPromptText());
    }

    @Test
    void newSectionsAppearInOrderAndSayNoneWhenEmpty() {
        SelfStatus self = new SelfStatus(true, false, false, false, false, true, 15, "stone at 1 63 2", 3);
        OwnerFocus focus = OwnerFocus.visible(
                "Owner",
                6.2,
                "10 64 -4",
                "grass_block at 10 63 -4",
                "nothing within 64 blocks",
                "nothing",
                "behind-right",
                "looking away from you",
                14f,
                20f,
                17,
                7,
                "on fire");
        PerceptionSnapshot snapshot = new PerceptionSnapshot(
                "0 0 0",
                "overworld",
                20f,
                20f,
                20,
                "day",
                "plains",
                "clear",
                6.2,
                Map.of(),
                Map.of(),
                Map.of(),
                "Wearing: nothing; holding: nothing; combat mode: defensive",
                "none",
                focus,
                self,
                List.of(new Threat("zombie", 4.2, "behind-right", 18f, 20f, "me")),
                List.of(new Landmark("crafting_table", "crafting_table", "12 64 -3", 4.1, "south-east")),
                List.of());
        String text = snapshot.toPromptText();
        assertTrue(text.indexOf("Self: ") < text.indexOf("Owner: "), text);
        assertTrue(text.contains("Threats: zombie 4.2 blocks behind-right, 18/20 hp, coming for me"), text);
        assertTrue(text.contains("Landmarks: crafting_table at 12 64 -3, 4.1 blocks south-east"), text);

        PerceptionSnapshot empty = new PerceptionSnapshot(
                "0 0 0",
                "overworld",
                20f,
                20f,
                20,
                "day",
                "plains",
                "clear",
                null,
                Map.of(),
                Map.of(),
                Map.of(),
                "Wearing: nothing; holding: nothing; combat mode: defensive",
                "none",
                null,
                self,
                List.of(),
                List.of(),
                List.of());
        assertTrue(empty.toPromptText().contains("Threats: none"), empty.toPromptText());
        assertTrue(empty.toPromptText().contains("Landmarks: none nearby"), empty.toPromptText());
    }

    @Test
    void formatsTrees() {
        PerceptionSnapshot snapshot = new PerceptionSnapshot(
                "0 0 0",
                "overworld",
                20f,
                20f,
                20,
                "day",
                "plains",
                "clear",
                null,
                Map.of(),
                Map.of(),
                Map.of(),
                "Wearing: nothing; holding: nothing; combat mode: defensive",
                "none",
                null,
                null,
                null,
                null,
                List.of(new TreeSummary("oak_log", "standing", "12 64 -3", 14, 12, 6.0, "north-west")));
        assertTrue(
                snapshot.toPromptText().contains("Trees: oak_log 6 blocks north-west (14 logs, height 12, standing)"),
                snapshot.toPromptText());
    }

    @Test
    void omitsTheTreesLineWhenEmpty() {
        PerceptionSnapshot snapshot = new PerceptionSnapshot(
                "0 0 0",
                "overworld",
                20f,
                20f,
                20,
                "day",
                "plains",
                "clear",
                null,
                Map.of(),
                Map.of(),
                Map.of(),
                "Wearing: nothing; holding: nothing; combat mode: defensive",
                "none",
                null,
                null,
                null,
                null,
                List.of());
        assertFalse(snapshot.toPromptText().contains("Trees:"), snapshot.toPromptText());
    }

    @Test
    void runningTaskIsMarkedUnfinished() {
        assertEquals("mining ore (running; no result yet)", PerceptionSnapshot.runningTask("mining ore"));
        assertEquals("none", PerceptionSnapshot.runningTask(null));
        assertEquals("none", PerceptionSnapshot.runningTask(" "));
    }
}
