package dev.friendai.presentation.companion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

final class CompanionSnapshotTest {
    @Test
    void feelingsAreBounded() {
        CompanionSnapshot snapshot = snapshot(
                "x".repeat(40),
                IntStream.range(0, 20).mapToObj(i -> "r" + i + "y".repeat(300)).toList(),
                "g".repeat(400),
                IntStream.range(0, 20).mapToObj(i -> "c" + i).toList(),
                List.of("z".repeat(400)));

        assertEquals("x".repeat(CompanionSnapshot.MAX_BOND_TIER), snapshot.bondTier());
        assertEquals(CompanionSnapshot.MAX_LIST_ENTRIES, snapshot.bondReasons().size());
        assertTrue(
                snapshot.bondReasons().stream().allMatch(reason -> reason.length() == CompanionSnapshot.MAX_LIST_TEXT));
        assertEquals(CompanionSnapshot.MAX_LIST_TEXT, snapshot.goalText().length());
        assertEquals(CompanionSnapshot.MAX_LIST_ENTRIES, snapshot.goalCriteria().size());
        assertEquals(1, snapshot.goalUnmet().size());
        assertEquals(
                CompanionSnapshot.MAX_LIST_TEXT, snapshot.goalUnmet().getFirst().length());
    }

    @Test
    void nullFeelingsBecomeEmpty() {
        CompanionSnapshot snapshot = snapshot(null, null, null, null, null);

        assertEquals("", snapshot.bondTier());
        assertEquals(List.of(), snapshot.bondReasons());
        assertEquals("", snapshot.goalText());
        assertEquals(List.of(), snapshot.goalCriteria());
        assertEquals(List.of(), snapshot.goalUnmet());
    }

    @Test
    void theShortConstructorLeavesTheFeelingsEmpty() {
        CompanionSnapshot snapshot = new CompanionSnapshot(
                false,
                false,
                "",
                "",
                "",
                false,
                "",
                List.of(),
                List.of(),
                "",
                List.of(),
                CompanionVitals.NONE,
                List.of(),
                false,
                false,
                false,
                false,
                false,
                "",
                false,
                true,
                "",
                "",
                "",
                "");

        assertEquals("", snapshot.bondTier());
        assertTrue(snapshot.bondReasons().isEmpty());
    }

    private static CompanionSnapshot snapshot(
            String bondTier,
            List<String> bondReasons,
            String goalText,
            List<String> goalCriteria,
            List<String> goalUnmet) {
        return new CompanionSnapshot(
                true,
                false,
                "Friend",
                "provider",
                "model",
                false,
                "",
                List.of(),
                List.of(),
                "",
                List.of(),
                CompanionVitals.NONE,
                List.of(),
                false,
                false,
                false,
                false,
                false,
                "",
                false,
                true,
                "",
                "",
                "",
                "",
                bondTier,
                bondReasons,
                goalText,
                goalCriteria,
                goalUnmet);
    }
}
