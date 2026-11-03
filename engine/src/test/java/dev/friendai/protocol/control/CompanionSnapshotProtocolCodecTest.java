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
import java.util.ArrayList;
import java.util.Arrays;
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

    @Test
    void payloadWithoutTheFeelingsBlockDecodesAsEmpty() {
        String json = "{\"present\":true,\"operator\":false,\"companionName\":\"Friend\",\"provider\":\"p\","
                + "\"model\":\"m\",\"thinking\":false,\"currentTask\":\"\",\"history\":[],\"newLog\":[],"
                + "\"perception\":\"\",\"toolNames\":[],\"vitals\":{\"health\":0,\"maxHealth\":0,\"food\":0,"
                + "\"inventoryUsed\":0,\"inventorySlots\":0,\"protection\":\"\",\"style\":\"\",\"action\":\"\","
                + "\"reason\":\"\",\"paceMode\":\"\",\"xpLevel\":0,\"xpProgress\":0},\"queue\":[],"
                + "\"autoRespawn\":false,\"autoRespawnAvailable\":false,\"creativeMode\":false,"
                + "\"creativeModeAvailable\":false,\"freeTimeOn\":false,\"freeTimeActivity\":\"\","
                + "\"freeTimeWaiting\":false,\"followMode\":false,\"freeTimeNext\":\"\","
                + "\"freeTimeBudget\":\"\",\"freeTimeLast\":\"\",\"freeTimePlannerModel\":\"\"}";
        byte[] encoded =
                ProtocolViewCodec.encode(ProtocolFamily.CONTROL, 128, 9, json.getBytes(StandardCharsets.UTF_8));

        DecodeResult.Valid<ProtocolView<CompanionSnapshotView>> decoded =
                assertInstanceOf(DecodeResult.Valid.class, CompanionSnapshotProtocolCodec.decode(encoded));
        CompanionSnapshotView view = decoded.value().value();
        assertEquals("", view.bondTier());
        assertEquals(List.of(), view.bondReasons());
        assertEquals("", view.goalText());
        assertEquals(List.of(), view.goalCriteria());
        assertEquals(List.of(), view.goalUnmet());
        assertEquals("", view.routine(), "an old payload has no routine line");
    }

    @Test
    void ownerReceivesTheFeelingsBlockAndOperatorFieldsStayOut() {
        CompanionSnapshotView producer = new CompanionSnapshotView(
                true,
                true,
                "Friend",
                "provider",
                "model",
                false,
                "idle",
                List.of(),
                List.of(),
                "",
                List.of(),
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
                "",
                "friend",
                List.of("shared work"),
                "build a shelter",
                List.of("gather wood", "put the roof on"),
                List.of("put the roof on"));

        byte[] encoded = CompanionSnapshotProtocolCodec.encodeForRecipient(producer, false, 4);
        DecodeResult.Valid<ProtocolView<CompanionSnapshotView>> decoded =
                assertInstanceOf(DecodeResult.Valid.class, CompanionSnapshotProtocolCodec.decode(encoded));
        CompanionSnapshotView view = decoded.value().value();
        assertFalse(view.operator());
        assertEquals("friend", view.bondTier());
        assertEquals(List.of("shared work"), view.bondReasons());
        assertEquals("build a shelter", view.goalText());
        assertEquals(List.of("gather wood", "put the roof on"), view.goalCriteria());
        assertEquals(List.of("put the roof on"), view.goalUnmet());
    }

    @Test
    void everyLogKindSurvivesTheSnapshotRoundTrip() {
        List<LogEntry> entries = new ArrayList<>();
        long id = 1;
        for (LogKind kind : LogKind.values()) {
            entries.add(new LogEntry(id++, 2, kind, "entry " + kind));
        }

        byte[] encoded = CompanionSnapshotProtocolCodec.encodeForRecipient(snapshotWithLog(entries), true, 5);
        DecodeResult.Valid<ProtocolView<CompanionSnapshotView>> decoded =
                assertInstanceOf(DecodeResult.Valid.class, CompanionSnapshotProtocolCodec.decode(encoded));
        assertEquals(
                Arrays.asList(LogKind.values()),
                decoded.value().value().newLog().stream().map(LogEntry::kind).toList());

        String json = new String(
                encoded,
                ProtocolVersion.HEADER_BYTES,
                encoded.length - ProtocolVersion.HEADER_BYTES,
                StandardCharsets.UTF_8);
        assertTrue(json.contains("\"kind\":9"));
        assertTrue(json.contains("\"kind\":10"));
    }

    @Test
    void aRoutineLineSurvivesTheSnapshotRoundTrip() {
        CompanionSnapshotView producer = new CompanionSnapshotView(
                true,
                false,
                "Friend",
                "provider",
                "model",
                false,
                "idle",
                List.of(),
                List.of(),
                "",
                List.of(),
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
                "",
                "",
                List.of(),
                "",
                List.of(),
                List.of(),
                false,
                0,
                "",
                "morning · next in 1200 ticks · last: succeeded");

        byte[] encoded = CompanionSnapshotProtocolCodec.encodeForRecipient(producer, false, 6);
        DecodeResult.Valid<ProtocolView<CompanionSnapshotView>> decoded =
                assertInstanceOf(DecodeResult.Valid.class, CompanionSnapshotProtocolCodec.decode(encoded));

        assertEquals(
                "morning · next in 1200 ticks · last: succeeded",
                decoded.value().value().routine());
    }

    @Test
    void anOldSnapshotWithoutARoutineDecodesEmpty() {
        DecodeResult.Valid<ProtocolView<CompanionSnapshotView>> decoded = assertInstanceOf(
                DecodeResult.Valid.class,
                CompanionSnapshotProtocolCodec.decode(
                        CompanionSnapshotProtocolCodec.encodeForRecipient(snapshot(), false, 7)));
        assertEquals("", decoded.value().value().routine());
    }

    private static CompanionSnapshotView snapshot() {
        return snapshotWithLog(List.of(new LogEntry(1, 2, LogKind.ERROR, "private log")));
    }

    private static CompanionSnapshotView snapshotWithLog(List<LogEntry> log) {
        return new CompanionSnapshotView(
                true,
                true,
                "Friend",
                "provider",
                "model",
                false,
                "idle",
                List.of(),
                log,
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
                "",
                "",
                List.of(),
                "",
                List.of(),
                List.of());
    }
}
