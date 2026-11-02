package dev.friendai.agent.llm.stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class SpeechStreamBufferTest {
    private static SpeechStreamBuffer.Sentence sentence(long stream, long seq, String text, boolean last) {
        return new SpeechStreamBuffer.Sentence(stream, 7, seq, text, last);
    }

    @Test
    void sentencesAreDeliveredOnceInOrder() {
        SpeechStreamBuffer buffer = new SpeechStreamBuffer();
        buffer.beginSession(7);
        assertEquals(
                List.of("one"),
                buffer.offer(sentence(1, 0, "one", false)).ready().stream()
                        .map(SpeechStreamBuffer.Sentence::text)
                        .toList());
        var duplicate = buffer.offer(sentence(1, 0, "one", false));
        assertEquals(SpeechStreamBuffer.Status.DUPLICATE, duplicate.status());
        var last = buffer.offer(sentence(1, 1, "two", true));
        assertEquals(
                List.of("two"),
                last.ready().stream().map(SpeechStreamBuffer.Sentence::text).toList());
        assertFalse(buffer.incomplete(1));
    }

    @Test
    void outOfOrderSentencesWaitForTheGapToFill() {
        SpeechStreamBuffer buffer = new SpeechStreamBuffer();
        buffer.beginSession(7);
        assertEquals(
                SpeechStreamBuffer.Status.BUFFERED,
                buffer.offer(sentence(1, 2, "three", false)).status());
        assertEquals(
                SpeechStreamBuffer.Status.BUFFERED,
                buffer.offer(sentence(1, 1, "two", false)).status());
        var result = buffer.offer(sentence(1, 0, "one", false));
        assertEquals(
                List.of("one", "two", "three"),
                result.ready().stream().map(SpeechStreamBuffer.Sentence::text).toList());
    }

    @Test
    void aStaleSessionIsRejectedAndInvalidationDropsBufferedText() {
        SpeechStreamBuffer buffer = new SpeechStreamBuffer();
        buffer.beginSession(7);
        buffer.offer(sentence(1, 1, "two", false));
        assertTrue(buffer.incomplete(1));
        assertEquals(
                SpeechStreamBuffer.Status.STALE_SESSION,
                buffer.offer(new SpeechStreamBuffer.Sentence(1, 8, 0, "x", false))
                        .status());
        buffer.invalidate();
        assertEquals(-1, buffer.sessionId());
        assertFalse(buffer.known(1));
        assertFalse(buffer.incomplete(1));
        assertEquals(
                SpeechStreamBuffer.Status.STALE_SESSION,
                buffer.offer(sentence(1, 0, "late", false)).status());
    }

    @Test
    void aGapBeyondTheBoundAndTooManyStreamsAreRefused() {
        SpeechStreamBuffer buffer = new SpeechStreamBuffer();
        buffer.beginSession(7);
        assertEquals(
                SpeechStreamBuffer.Status.UNKNOWN_STREAM,
                buffer.offer(sentence(1, SpeechStreamBuffer.MAX_PENDING + 2, "far", false))
                        .status());

        for (long stream = 1; stream <= SpeechStreamBuffer.MAX_STREAMS; stream++) {
            buffer.beginSession(7);
            buffer.offer(sentence(stream, 0, "x", false));
        }
        buffer.beginSession(7);
        for (long stream = 1; stream <= SpeechStreamBuffer.MAX_STREAMS; stream++) {
            buffer.offer(sentence(stream, 0, "x", false));
        }
        assertEquals(
                SpeechStreamBuffer.Status.UNKNOWN_STREAM,
                buffer.offer(sentence(SpeechStreamBuffer.MAX_STREAMS + 1, 0, "new", false))
                        .status());
    }

    @Test
    void aLastSentenceBeforeAnEarlierOneMarksTheStreamOnlyAfterTheGapFills() {
        SpeechStreamBuffer buffer = new SpeechStreamBuffer();
        buffer.beginSession(7);
        buffer.offer(sentence(1, 1, "end", true));
        assertTrue(buffer.incomplete(1));
        var result = buffer.offer(sentence(1, 0, "start", false));
        assertEquals(2, result.ready().size());
        assertFalse(buffer.incomplete(1));
    }

    @Test
    void tooLongTextIsRejectedBeforeItEntersTheBuffer() {
        try {
            new SpeechStreamBuffer.Sentence(1, 7, 0, "x".repeat(SpeechStreamBuffer.MAX_TEXT + 1), false);
            throw new AssertionError("expected rejection");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }
}
