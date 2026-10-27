package dev.friendai.debug;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

class CreativeSnapshotCodecTest {
    @Test
    void roundTripsModeAndAvailability() {
        CompanionSnapshot snapshot = new CompanionSnapshot(false, false, "", "", "", false, "", List.of(),
                List.of(), "", List.of(), CompanionVitals.NONE, List.of(), true, false, true, false);

        assertEquals(snapshot, SnapshotCodec.decode(SnapshotCodec.encode(snapshot)));
    }

    @Test
    void rejectsPartialCreativeAvailabilityFields() {
        CompanionSnapshot snapshot = new CompanionSnapshot(false, false, "", "", "", false, "", List.of(),
                List.of(), "", List.of(), CompanionVitals.NONE, List.of(), true, false, true, false);
        byte[] encoded = SnapshotCodec.encode(snapshot);
        byte[] truncated = java.util.Arrays.copyOf(encoded, encoded.length - 1);

        assertThrows(IllegalArgumentException.class, () -> SnapshotCodec.decode(truncated));
    }

    @Test
    void oldSnapshotConstructorDefaultsCreativeOffAndUnavailable() {
        CompanionSnapshot snapshot = new CompanionSnapshot(false, false, "", "", "", false, "", List.of(),
                List.of(), "", List.of(), CompanionVitals.NONE, List.of());

        CompanionSnapshot decoded = SnapshotCodec.decode(SnapshotCodec.encode(snapshot));

        assertEquals(false, decoded.creativeMode());
        assertEquals(false, decoded.creativeModeAvailable());
    }

    @Test
    void oldWireSnapshotDefaultsCreativeOffAndUnavailable() {
        CompanionSnapshot snapshot = new CompanionSnapshot(true, false, "A", "", "", false, "", List.of(),
                List.of(), "", List.of(), CompanionVitals.NONE, List.of(), false, true, true, true);
        byte[] current = SnapshotCodec.encode(snapshot);
        byte[] oldWire = java.util.Arrays.copyOf(current, current.length - 2);

        CompanionSnapshot decoded = SnapshotCodec.decode(oldWire);

        assertEquals(false, decoded.creativeMode());
        assertEquals(false, decoded.creativeModeAvailable());
        assertEquals(false, decoded.autoRespawn());
        assertEquals(true, decoded.autoRespawnAvailable());
    }
}
