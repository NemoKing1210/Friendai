package dev.friendai.features.speech;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class TranscriptTextTest {
    @Test
    void shortTextIsOnlyStripped() {
        assertEquals("hello", TranscriptText.cap("  hello ", 10));
        assertEquals("", TranscriptText.cap(null, 10));
        assertEquals("", TranscriptText.cap("   ", 10));
    }

    @Test
    void longTextIsCappedByCodePointsAndMarked() {
        assertEquals("abc\u2026", TranscriptText.cap("abcdef", 3));
        assertEquals(4, TranscriptText.cap("abcdef", 3).length(), "three code points plus the ellipsis");
    }

    @Test
    void surrogatePairsAreNeverSplit() {
        String emoji = "\uD83D\uDE00\uD83D\uDE01\uD83D\uDE02\uD83D\uDE03";
        String capped = TranscriptText.cap(emoji, 2);
        assertEquals("\uD83D\uDE00\uD83D\uDE01\u2026", capped);
        assertEquals(5, capped.length(), "two pairs plus the single-character ellipsis");
    }

    @Test
    void aNonPositiveCapKeepsOnlyTheMarker() {
        assertEquals("\u2026", TranscriptText.cap("abc", 0));
        assertEquals("", TranscriptText.cap("", 0));
    }
}
