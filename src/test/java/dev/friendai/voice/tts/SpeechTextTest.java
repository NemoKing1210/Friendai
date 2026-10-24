package dev.friendai.voice.tts;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class SpeechTextTest {
    @Test
    void stripsMarkdownEmphasisAndCode() {
        assertEquals(List.of("Hello world"), SpeechText.cleanAndSplit("**Hello** `world`", 100));
        assertEquals(List.of("Hello"), SpeechText.cleanAndSplit("```java\nint x = 1;\n```\nHello", 100));
    }

    @Test
    void keepsLinkLabelsAndDropsUrls() {
        assertEquals(List.of("Read this now."), SpeechText.cleanAndSplit("Read [this](https://example.com) now.", 100));
        assertEquals(List.of("Go to now."), SpeechText.cleanAndSplit("Go to https://example.com/x now.", 100));
    }

    @Test
    void splitsAtSentencesAndSpaces() {
        assertEquals(List.of("First sentence.", "Second sentence!"),
                SpeechText.cleanAndSplit("First sentence. Second sentence!", 20));
        assertEquals(List.of("aaaa bbbb", "cccc dddd"), SpeechText.cleanAndSplit("aaaa bbbb cccc dddd", 9));
    }

    @Test
    void blankInputProducesNothing() {
        assertEquals(List.of(), SpeechText.cleanAndSplit("   ", 100));
        assertEquals(List.of(), SpeechText.cleanAndSplit(null, 100));
    }
}
