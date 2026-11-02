package dev.friendai.agent.llm.stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class IncrementalSseDecoderTest {
    private static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static List<StreamEvent> feedAll(IncrementalSseDecoder decoder, String text, int chunk) {
        List<StreamEvent> events = new ArrayList<>();
        byte[] bytes = utf8(text);
        for (int i = 0; i < bytes.length; i += chunk) {
            byte[] part = java.util.Arrays.copyOfRange(bytes, i, Math.min(bytes.length, i + chunk));
            events.addAll(decoder.feed(part));
        }
        return events;
    }

    @Test
    void oneEventIsDecodedAcrossArbitraryChunkBoundaries() {
        for (int chunk : List.of(1, 2, 3, 5, 7, 64)) {
            IncrementalSseDecoder decoder = new IncrementalSseDecoder();
            List<StreamEvent> events = feedAll(decoder, "event: delta\ndata: hello\n\n", chunk);
            assertEquals(1, events.size(), "chunk " + chunk);
            assertEquals("delta", events.getFirst().event());
            assertEquals("hello", events.getFirst().data());
        }
    }

    @Test
    void aUtf8CharacterSplitAcrossChunksStillDecodes() {
        String text = "data: привет\n\n";
        byte[] bytes = utf8(text);
        int split = 0;
        while ((bytes[split] & 0xC0) != 0x80) {
            split++;
        }
        // split is the first byte of a multibyte sequence; cut one byte into it.
        IncrementalSseDecoder decoder = new IncrementalSseDecoder();
        assertTrue(
                decoder.feed(java.util.Arrays.copyOfRange(bytes, 0, split + 1)).isEmpty());
        List<StreamEvent> events =
                new ArrayList<>(decoder.feed(java.util.Arrays.copyOfRange(bytes, split + 1, bytes.length)));
        assertEquals(1, events.size());
        assertEquals("привет", events.getFirst().data());
    }

    @Test
    void crlfAndLoneCarriageReturnsAreLineBreaks() {
        IncrementalSseDecoder decoder = new IncrementalSseDecoder();
        List<StreamEvent> events = new ArrayList<>();
        events.addAll(decoder.feed(utf8("data: a\r")));
        events.addAll(decoder.feed(utf8("\n\r\n")));
        assertEquals(1, events.size());
        assertEquals("a", events.getFirst().data());

        IncrementalSseDecoder lone = new IncrementalSseDecoder();
        List<StreamEvent> loneEvents = lone.feed(utf8("data: b\r\r"));
        assertEquals(1, loneEvents.size());
        assertEquals("b", loneEvents.getFirst().data());
    }

    @Test
    void multipleDataLinesJoinWithNewlinesAndCommentsAreIgnored() {
        IncrementalSseDecoder decoder = new IncrementalSseDecoder();
        List<StreamEvent> events =
                decoder.feed(utf8(": keep-alive\nid: 7\nretry: 100\nevent: message\ndata: first\ndata: second\n\n"));
        assertEquals(1, events.size());
        assertEquals("message", events.getFirst().event());
        assertEquals("first\nsecond", events.getFirst().data());
    }

    @Test
    void finishFlushesTheLastUnterminatedEvent() {
        IncrementalSseDecoder decoder = new IncrementalSseDecoder();
        assertTrue(decoder.feed(utf8("data: tail")).isEmpty());
        List<StreamEvent> events = decoder.finish();
        assertEquals(1, events.size());
        assertEquals("tail", events.getFirst().data());
        assertFalse(decoder.feed(utf8("data: later\n\n")).iterator().hasNext());
        assertTrue(decoder.finished());
    }

    @Test
    void closeDropsBufferedText() {
        IncrementalSseDecoder decoder = new IncrementalSseDecoder();
        decoder.feed(utf8("data: secret"));
        decoder.close();
        assertTrue(decoder.finish().isEmpty());
        assertTrue(decoder.feed(utf8("data: after\n\n")).isEmpty());
    }

    @Test
    void anOversizedLineFailsTheDecoderInsteadOfGrowing() {
        IncrementalSseDecoder decoder = new IncrementalSseDecoder();
        byte[] huge = new byte[IncrementalSseDecoder.MAX_LINE_BYTES + 1];
        java.util.Arrays.fill(huge, (byte) 'x');
        assertTrue(decoder.feed(huge).isEmpty());
        assertTrue(decoder.failed());
        assertTrue(decoder.feed(utf8("data: later\n\n")).isEmpty());
    }

    @Test
    void anOversizedEventFailsTheDecoder() {
        IncrementalSseDecoder decoder = new IncrementalSseDecoder();
        String payload = "x".repeat(1000);
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < 1100; i++) {
            builder.append("data: ").append(payload).append('\n');
        }
        decoder.feed(utf8(builder.toString()));
        assertTrue(decoder.failed());
    }

    @Test
    void openAiDoneIsPlainDataForTheProviderLayer() {
        IncrementalSseDecoder decoder = new IncrementalSseDecoder();
        List<StreamEvent> events = decoder.feed(utf8("data: [DONE]\n\n"));
        assertEquals(1, events.size());
        assertEquals("[DONE]", events.getFirst().data());
        assertEquals("", events.getFirst().event());
    }
}
