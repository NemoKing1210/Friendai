package dev.friendai.debug;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class SnapshotCodecTest {
    private static CompanionSnapshot full() {
        return new CompanionSnapshot(
                true, true, "Friendai", "anthropic", "claude-x", true, "follow",
                List.of(new TaskRecord(1, "stay", "SUCCESS", "ok"), new TaskRecord(2, "go_to", "FAILED", "no path")),
                List.of(new LogEntry(7, 123456789L, LogKind.TOOL_CALL, "stay {}")),
                "perception text\nline 2", List.of("stay", "go_to"),
                new CompanionVitals(17.5f, 20f, 14, 5, 27, "defensive", "bodyguard", "balanced", "attack",
                        "defend_self", "auto", 12, 0.5f),
                List.of(new QueueRow(3, 1, "build a hut", "active", "mine_blocks", "{\"block\":\"oak_log\"}", "running", "mining",
                        List.of(2L), 1, 2, 5)));
    }

    @Test
    void roundTripsFullSnapshot() {
        CompanionSnapshot s = full();
        assertEquals(s, SnapshotCodec.decode(SnapshotCodec.encode(s)));
    }

    @Test
    void combatSettingsArePresentInSnapshotRoundTrip() {
        CompanionVitals decoded = SnapshotCodec.decode(SnapshotCodec.encode(full())).vitals();
        assertEquals("bodyguard", decoded.protection());
        assertEquals("balanced", decoded.style());
        assertEquals("attack", decoded.action());
        assertEquals("defend_self", decoded.reason());
    }

    @Test
    void carriesExperienceLevelAndProgress() {
        CompanionVitals decoded = SnapshotCodec.decode(SnapshotCodec.encode(full())).vitals();
        assertEquals(12, decoded.xpLevel());
        assertEquals(0.5f, decoded.xpProgress());
    }

    @Test
    void freeTimeFieldsRoundTrip() {
        CompanionSnapshot base = full();
        CompanionSnapshot s = new CompanionSnapshot(base.present(), base.operator(), base.companionName(),
                base.provider(), base.model(), base.thinking(), base.currentTask(), base.history(), base.newLog(),
                base.perception(), base.toolNames(), base.vitals(), base.queue(), base.autoRespawn(),
                base.autoRespawnAvailable(), base.creativeMode(), base.creativeModeAvailable(), true, "mine iron for a pickaxe");
        assertEquals(s, SnapshotCodec.decode(SnapshotCodec.encode(s)));
    }

    @Test
    void longActivityIsTruncated() {
        CompanionSnapshot base = full();
        CompanionSnapshot s = new CompanionSnapshot(base.present(), base.operator(), base.companionName(),
                base.provider(), base.model(), base.thinking(), base.currentTask(), base.history(), base.newLog(),
                base.perception(), base.toolNames(), base.vitals(), base.queue(), base.autoRespawn(),
                base.autoRespawnAvailable(), base.creativeMode(), base.creativeModeAvailable(), true, "x".repeat(500));
        assertEquals(CompanionSnapshot.MAX_ACTIVITY, s.freeTimeActivity().length());
    }

    @Test
    void roundTripsEmptyLists() {
        CompanionSnapshot s = new CompanionSnapshot(false, false, "", "", "", false, "", List.of(), List.of(), "", List.of(), CompanionVitals.NONE, List.of());
        assertEquals(s, SnapshotCodec.decode(SnapshotCodec.encode(s)));
    }

    @Test
    void rejectsGarbage() {
        byte[] garbage = new byte[200];
        new Random(42).nextBytes(garbage);
        assertThrows(IllegalArgumentException.class, () -> SnapshotCodec.decode(garbage));
        assertThrows(IllegalArgumentException.class, () -> SnapshotCodec.decode(new byte[0]));
    }

    @Test
    void handlesLargeText() {
        List<LogEntry> entries = new ArrayList<>();
        for (int i = 1; i <= 50; i++) {
            entries.add(new LogEntry(i, i, LogKind.TOOL_RESULT, "é".repeat(2000)));
        }
        CompanionSnapshot s = new CompanionSnapshot(
                true, true, "n", "p", "m", false, "t", List.of(), entries, "", List.of(), CompanionVitals.NONE, List.of());
        assertEquals(s, SnapshotCodec.decode(SnapshotCodec.encode(s)));
    }
}
