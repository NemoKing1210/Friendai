package dev.friendai.brain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.junit.jupiter.api.Test;

class ReplyGuardTest {
    private static final Set<String> TOOLS = Set.of("run_plan", "mine_block_at", "stay");

    private static String filter(String reply, boolean coordinatesAllowed, String lastSpoken) {
        return ReplyGuard.filter(reply, coordinatesAllowed, lastSpoken, TOOLS).orElse(null);
    }

    @Test
    void sentinelMeansSilence() {
        assertNull(filter("[silent]", false, null));
        assertNull(filter("  [SILENT]  ", false, null));
        assertNull(filter("`[silent]`", false, null));
    }

    @Test
    void sentinelInsideTextIsRemoved() {
        assertEquals("Done.", filter("Done. [silent]", false, null));
    }

    @Test
    void blankReplyIsSilence() {
        assertNull(filter("", false, null));
        assertNull(filter("   ", false, null));
    }

    @Test
    void parenthesisedCoordinatesAreStrippedWhenNotAsked() {
        assertEquals("The hive is nearby.", filter("The hive is nearby (1279 68 358).", false, null));
    }

    @Test
    void contextualCoordinatesAreStrippedWhenNotAsked() {
        assertEquals("The hive is nearby.", filter("The hive is nearby at 1279 68 358.", false, null));
        assertEquals("Улей рядом.", filter("Улей рядом на 1279 68 358.", false, null));
    }

    @Test
    void xyzCoordinatesAreStrippedWhenNotAsked() {
        assertEquals("The hive is nearby.", filter("The hive is nearby x=1279 y=68 z=358.", false, null));
    }

    @Test
    void coordinatesStayWhenThePlayerAsked() {
        assertEquals("The hive is at 1279 68 358.", filter("The hive is at 1279 68 358.", true, null));
        assertEquals("Coordinates: 1279 68 358.", filter("Coordinates: 1279 68 358.", true, null));
    }

    @Test
    void bareTripleIsNotTreatedAsCoordinates() {
        assertEquals("Wait 5 10 15 seconds.", filter("Wait 5 10 15 seconds.", false, null));
    }

    @Test
    void labelledCoordinatesAreStrippedWhenNotAsked() {
        assertEquals("Hive found.", filter("Hive found. Coordinates: 1279 68 358.", false, null));
        assertEquals("Hive found.", filter("Hive found. Location is 1279 68 358.", false, null));
        assertEquals("Found it.", filter("Found it. Your position is 1279 68 358.", false, null));
        assertEquals("Улей рядом.", filter("Улей рядом. Координаты: 1279 68 358.", false, null));
        assertNull(filter("Coordinates: 1279 68 358.", false, null));
    }

    @Test
    void coordinateQuestionsAreDetected() {
        assertTrue(ReplyGuard.playerAskedForCoordinates("where is the hive?"));
        assertTrue(ReplyGuard.playerAskedForCoordinates("где улей?"));
        assertTrue(ReplyGuard.playerAskedForCoordinates("give me the coordinates"));
        assertFalse(ReplyGuard.playerAskedForCoordinates("куда идём?"));
        assertFalse(ReplyGuard.playerAskedForCoordinates("привет"));
        assertFalse(ReplyGuard.playerAskedForCoordinates(null));
    }

    @Test
    void exactRepeatIsSilence() {
        assertNull(filter("Done.", false, "done"));
        assertEquals("Done again.", filter("Done again.", false, "Done."));
    }

    @Test
    void nullLastSpokenNeverSuppresses() {
        assertEquals("Done.", filter("Done.", false, null));
    }

    @Test
    void taskNumbersAndNamespacesAreRemoved() {
        assertEquals("mined oak_log.", filter("Task #47 mined minecraft:oak_log.", false, null));
    }

    @Test
    void underscoredToolNamesAreRemovedButOrdinaryWordsAreNot() {
        assertEquals("Calling now.", filter("Calling run_plan now.", false, null));
        assertEquals("I will stay here.", filter("I will stay here.", false, null));
    }

    @Test
    void cleanupLeavesNoDoubleSpacesOrDanglingPunctuation() {
        assertEquals("The hive is nearby.", filter("The hive is  nearby  (1279 68 358) .", false, null));
    }
}
