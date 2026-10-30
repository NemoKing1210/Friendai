package dev.friendai.protocol.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import dev.friendai.features.memory.Category;
import dev.friendai.features.memory.MemoryLimits;
import dev.friendai.features.memory.MemoryView;
import dev.friendai.features.memory.MenuStatus;
import dev.friendai.foundation.text.UserMessage;
import dev.friendai.protocol.DecodeResult;
import dev.friendai.protocol.ProtocolView;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class MemoryProtocolViewCodecTest {
    @Test
    void framesRoundTripWithExplicitRoleAndCategoryIds() {
        MemoryView view = new MemoryView(
                true,
                new MemoryLimits(160, 20, 30),
                List.of(new MemoryView.Group("", "", MemoryView.Role.TRUSTED, 1, true)),
                List.of(new MemoryView.FactRow(7, "world", Category.NOTE, "Привет 🌍", "")),
                Optional.of(new MenuStatus(true, UserMessage.of("friendai.memory.ok", "saved"))),
                new MemoryView.PersonaView("😀", true));

        byte[] encoded = MemoryProtocolViewCodec.encode(view, 19);
        assertEquals(3, Byte.toUnsignedInt(encoded[34]));
        assertEquals(5, Byte.toUnsignedInt(encoded[53]));
        DecodeResult.Valid<ProtocolView<MemoryView>> decoded =
                assertInstanceOf(DecodeResult.Valid.class, MemoryProtocolViewCodec.decode(encoded));
        assertEquals(19, decoded.value().requestId());
        assertEquals(view, decoded.value().value());
    }

    @Test
    void rejectsMalformedUtf8AndTrailingBodyBytes() {
        MemoryView view = new MemoryView(
                true,
                new MemoryLimits(160, 20, 30),
                List.of(),
                List.of(),
                Optional.empty(),
                new MemoryView.PersonaView("x", true));
        byte[] encoded = MemoryProtocolViewCodec.encode(view, 1);
        byte[] trailing = java.util.Arrays.copyOf(encoded, encoded.length + 1);
        assertInstanceOf(DecodeResult.Malformed.class, MemoryProtocolViewCodec.decode(trailing));

        byte[] badUtf8 = encoded.clone();
        badUtf8[badUtf8.length - 4] = (byte) 0xff;
        assertInstanceOf(DecodeResult.Malformed.class, MemoryProtocolViewCodec.decode(badUtf8));
    }

    @Test
    void rejectsUnknownCategoryIds() {
        MemoryView view = new MemoryView(
                true,
                new MemoryLimits(160, 20, 30),
                List.of(),
                List.of(new MemoryView.FactRow(1, "world", Category.NOTE, "note", "")),
                Optional.empty());
        byte[] encoded = MemoryProtocolViewCodec.encode(view, 1);
        encoded[53] = (byte) 255;
        assertInstanceOf(DecodeResult.Malformed.class, MemoryProtocolViewCodec.decode(encoded));
    }

    @Test
    void fitsTheOneMiBFamilyLimitByDroppingFactsFromTheEnd() {
        List<MemoryView.FactRow> facts = java.util.stream.IntStream.range(0, 2_000)
                .mapToObj(index -> new MemoryView.FactRow(index + 1, "world", Category.NOTE, "x".repeat(1_000), ""))
                .toList();
        MemoryView view = new MemoryView(true, new MemoryLimits(160, 20, 30), List.of(), facts, Optional.empty());

        byte[] encoded = MemoryProtocolViewCodec.encodeFitting(view, 22);
        DecodeResult.Valid<ProtocolView<MemoryView>> decoded =
                assertInstanceOf(DecodeResult.Valid.class, MemoryProtocolViewCodec.decode(encoded));
        assertEquals(22, decoded.value().requestId());
        assertEquals(1_000, decoded.value().value().facts().size());
    }

    @Test
    void transcriptRowsRoundTripAndAreBounded() {
        MemoryView.TranscriptRow row = new MemoryView.TranscriptRow(
                9_000_000_001L, "00000000-0000-0000-0000-0000000000a1", "Алекс", true, "Привет 🌍", 1_700_000_000_000L);
        MemoryView view = new MemoryView(
                true,
                new MemoryLimits(160, 20, 30),
                List.of(),
                List.of(),
                Optional.empty(),
                new MemoryView.PersonaView("p", false),
                List.of(row, new MemoryView.TranscriptRow(2, "k", "n", false, "hi", 5)));

        DecodeResult.Valid<ProtocolView<MemoryView>> decoded = assertInstanceOf(
                DecodeResult.Valid.class, MemoryProtocolViewCodec.decode(MemoryProtocolViewCodec.encode(view, 3)));
        assertEquals(view, decoded.value().value());

        List<MemoryView.TranscriptRow> many =
                java.util.Collections.nCopies(MemoryProtocolViewCodec.MAX_TRANSCRIPT_ROWS + 1, row);
        MemoryView tooMany = new MemoryView(
                true,
                new MemoryLimits(160, 20, 30),
                List.of(),
                List.of(),
                Optional.empty(),
                new MemoryView.PersonaView("p", false),
                many);
        org.junit.jupiter.api.Assertions.assertThrows(
                dev.friendai.protocol.ProtocolDataException.class, () -> MemoryProtocolViewCodec.encode(tooMany, 1));
    }
}
