package dev.friendai.protocol.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.foundation.diagnostics.LogEntry;
import dev.friendai.foundation.diagnostics.LogKind;
import dev.friendai.protocol.DecodeResult;
import dev.friendai.protocol.ProtocolFamily;
import dev.friendai.protocol.ProtocolVersion;
import dev.friendai.protocol.ProtocolView;
import dev.friendai.protocol.ProtocolViewCodec;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

final class CompanionSnapshotProtocolCodecTest {
    @Test
    void ownerProjectionCannotLeakOperatorFieldsFromProducer() {
        CompanionSnapshotView producer = snapshot();

        byte[] encoded = CompanionSnapshotProtocolCodec.encodeForRecipient(producer, false, 14);
        String json = new String(
                encoded,
                ProtocolVersion.HEADER_BYTES,
                encoded.length - ProtocolVersion.HEADER_BYTES,
                StandardCharsets.UTF_8);
        assertFalse(json.contains("private perception"));
        assertFalse(json.contains("private tool"));
        assertFalse(json.contains("private log"));

        DecodeResult.Valid<ProtocolView<CompanionSnapshotView>> decoded =
                assertInstanceOf(DecodeResult.Valid.class, CompanionSnapshotProtocolCodec.decode(encoded));
        assertEquals(14, decoded.value().requestId());
        assertFalse(decoded.value().value().operator());
        assertTrue(decoded.value().value().newLog().isEmpty());
        assertEquals("", decoded.value().value().perception());
        assertTrue(decoded.value().value().toolNames().isEmpty());
    }

    @Test
    void operatorProjectionRetainsFieldsAndUsesExplicitLogKindId() {
        byte[] encoded = CompanionSnapshotProtocolCodec.encodeForRecipient(snapshot(), true, 3);
        String json = new String(
                encoded,
                ProtocolVersion.HEADER_BYTES,
                encoded.length - ProtocolVersion.HEADER_BYTES,
                StandardCharsets.UTF_8);
        assertTrue(json.contains("\"kind\":5"));
        DecodeResult.Valid<ProtocolView<CompanionSnapshotView>> decoded =
                assertInstanceOf(DecodeResult.Valid.class, CompanionSnapshotProtocolCodec.decode(encoded));
        assertEquals(LogKind.ERROR, decoded.value().value().newLog().getFirst().kind());
        assertEquals("private perception", decoded.value().value().perception());
    }

    @Test
    void decoderRejectsOperatorFieldsOnPublicSnapshots() {
        byte[] operatorEncoded = CompanionSnapshotProtocolCodec.encodeForRecipient(snapshot(), true, 3);
        String json = new String(
                operatorEncoded,
                ProtocolVersion.HEADER_BYTES,
                operatorEncoded.length - ProtocolVersion.HEADER_BYTES,
                StandardCharsets.UTF_8);
        byte[] forged = ProtocolViewCodec.encode(
                ProtocolFamily.CONTROL,
                128,
                3,
                json.replace("\"operator\":true", "\"operator\":false").getBytes(StandardCharsets.UTF_8));
        assertInstanceOf(DecodeResult.Malformed.class, CompanionSnapshotProtocolCodec.decode(forged));
    }

    private static CompanionSnapshotView snapshot() {
        return new CompanionSnapshotView(
                true,
                true,
                "Friend",
                "provider",
                "model",
                false,
                "idle",
                List.of(),
                List.of(new LogEntry(1, 2, LogKind.ERROR, "private log")),
                "private perception",
                List.of("private tool"),
                new CompanionSnapshotView.Vitals(0, 0, 0, 0, 0, "", "", "none", "none", "", 0, 0),
                List.of(),
                true,
                true,
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
    }
}
