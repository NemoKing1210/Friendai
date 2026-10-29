package dev.friendai.transport.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.friendai.execution.history.TaskRecord;
import dev.friendai.foundation.diagnostics.LogEntry;
import dev.friendai.foundation.diagnostics.LogKind;
import dev.friendai.presentation.companion.CompanionSnapshot;
import dev.friendai.presentation.companion.CompanionVitals;
import dev.friendai.presentation.companion.QueueRow;
import java.util.List;
import org.junit.jupiter.api.Test;

final class CompanionSnapshotProtocolAdapterTest {
    @Test
    void presentationSnapshotSurvivesProtocolBoundaryMapping() {
        CompanionSnapshot snapshot = new CompanionSnapshot(
                true,
                true,
                "Friend",
                "provider",
                "model",
                true,
                "working",
                List.of(new TaskRecord(1, "mine", "SUCCESS", "finished")),
                List.of(new LogEntry(1, 2, LogKind.ASSISTANT, "hello")),
                "perception",
                List.of("mine_blocks"),
                new CompanionVitals(
                        18, 20, 16, 3, 36, "guard", "owner", "steady", "follow", "nearby", "slow", 12, 0.5f),
                List.of(new QueueRow(4, 2, "goal", "running", "mine", "{}", "ready", "note", List.of(3L), 1, 3, 5)),
                true,
                true,
                false,
                false,
                true,
                "exploring",
                false,
                true,
                "next",
                "budget",
                "last shift",
                "planner");

        assertEquals(
                snapshot,
                CompanionSnapshotProtocolAdapter.fromProtocol(CompanionSnapshotProtocolAdapter.toProtocol(snapshot)));
    }
}
