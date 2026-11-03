package dev.friendai.agent.autonomy;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.friendai.foundation.diagnostics.LogCategory;
import dev.friendai.foundation.diagnostics.LogEvents;
import dev.friendai.foundation.diagnostics.LogKind;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FreeTimeLogEventsTest {
    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID COMPANION = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @Test
    void aShiftEndCarriesTheDiagnosis() {
        var record = FreeTimeLogEvents.end(
                OWNER,
                COMPANION,
                "s1",
                7L,
                false,
                "DONE",
                "there is no clear side to climb …",
                "UNREACHABLE",
                "no way up the tree");

        assertEquals(LogEvents.SHIFT_END, record.event());
        assertEquals(LogCategory.TASK, record.category());
        assertEquals(LogKind.TASK, record.kind());
        assertEquals(7L, record.plan());
        assertEquals(
                Map.of(
                        "plan", "7",
                        "success", "false",
                        "stopWhen", "DONE",
                        "diagnosisKind", "UNREACHABLE",
                        "diagnosisDetail", "no way up the tree",
                        "summary", "there is no clear side to climb …"),
                record.data());
    }

    @Test
    void aShiftStartNamesTheNeedAndGoal() {
        var record = FreeTimeLogEvents.start(OWNER, COMPANION, "s1", 7L, "STOCK", "stock for stage WOOD: logs 0/16");

        assertEquals(LogEvents.SHIFT_START, record.event());
        assertEquals("STOCK", record.data().get("need"));
        assertEquals("stock for stage WOOD: logs 0/16", record.data().get("goal"));
    }

    @Test
    void aReadableLineIsNamed() {
        var record =
                FreeTimeLogEvents.report(OWNER, COMPANION, "s1", 7L, "friendai.freetime.shift_report.support_needed");

        assertEquals(LogEvents.SHIFT_REPORT, record.event());
        assertEquals(7L, record.plan());
        assertEquals(
                "friendai.freetime.shift_report.support_needed", record.data().get("key"));
    }
}
