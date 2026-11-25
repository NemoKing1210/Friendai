package dev.friendai.agent.turn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ToolResultTextTest {
    @Test
    void aShortResultIsKeptWhole() {
        assertEquals("found 3 logs", ToolResultText.of("found 3 logs"));
        assertEquals("", ToolResultText.of(null));
    }

    @Test
    void aResultAtTheBoundIsKeptWhole() {
        String text = "x".repeat(ToolResultText.MAX_CHARS);
        assertEquals(text, ToolResultText.of(text));
    }

    @Test
    void aLongResultKeepsItsBeginningAndItsEndingAndSaysWhatIsMissing() {
        String text = "HEAD " + "m".repeat(20_000) + " TAIL";
        String bounded = ToolResultText.of(text);
        assertTrue(bounded.startsWith("HEAD "), bounded.substring(0, 40));
        assertTrue(bounded.endsWith(" TAIL"), bounded.substring(bounded.length() - 40));
        assertTrue(bounded.contains("characters omitted"), bounded);
        assertTrue(bounded.length() < text.length() / 3, "the bound did not shorten the result: " + bounded.length());
    }

    @Test
    void everyTruncatedResultStaysNearTheBound() {
        for (int size : new int[] {ToolResultText.MAX_CHARS + 1, 9_000, 200_000}) {
            String bounded = ToolResultText.of("a".repeat(size));
            assertTrue(
                    bounded.length() < ToolResultText.MAX_CHARS + 120,
                    "a " + size + " character result produced " + bounded.length() + " characters");
        }
    }

    @Test
    void theBoundNeverSplitsASurrogatePair() {
        String text = "a".repeat(ToolResultText.MAX_CHARS / 2 - 1) + "😀".repeat(5_000);
        String bounded = ToolResultText.of(text);
        for (int i = 0; i < bounded.length(); i++) {
            if (Character.isLowSurrogate(bounded.charAt(i))) {
                assertTrue(i > 0 && Character.isHighSurrogate(bounded.charAt(i - 1)), "a pair was split at " + i);
            }
        }
    }
}
