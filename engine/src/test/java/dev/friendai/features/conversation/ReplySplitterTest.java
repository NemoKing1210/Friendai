package dev.friendai.features.conversation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class ReplySplitterTest {
    @Test
    void packsSentencesUpToMax() {
        assertEquals(List.of("One. Two.", "Three."), ReplySplitter.split("One. Two. Three.", 9));
    }

    @Test
    void longWordCutExactlyAtMax() {
        assertEquals(List.of("abcd", "efgh", "ij"), ReplySplitter.split("abcdefghij", 4));
    }

    @Test
    void longSentenceCutAtLastSpace() {
        List<String> parts = ReplySplitter.split("aaa bbb ccc ddd", 8);
        assertEquals(List.of("aaa bbb", "ccc ddd"), parts);
        assertTrue(parts.stream().allMatch(p -> p.length() <= 8));
    }

    @Test
    void markdownRemoved() {
        assertEquals(List.of("Done"), ReplySplitter.split("**Done**", 100));
        assertEquals(List.of("Title text"), ReplySplitter.split("# Title\ntext", 100));
        assertEquals(List.of("code here"), ReplySplitter.split("`code` here", 100));
    }

    @Test
    void cyrillicSplitsCorrectly() {
        assertEquals(List.of("Раз.", "Два."), ReplySplitter.split("Раз. Два.", 5));
    }

    @Test
    void emptyTextGivesNoLines() {
        assertTrue(ReplySplitter.split("  ", 10).isEmpty());
    }
}
