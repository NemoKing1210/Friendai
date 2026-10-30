package dev.friendai.agent.turn;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The golden failure is the only thing a contributor sees when a prompt change forgot its golden update, so it must
 * name the case, quote the first differing lines and repeat the command that regenerates them.
 */
class PromptGoldenDiffTest {

    @Test
    void quotesTheFirstDifferingLineFromBothSides() {
        List<String> golden = List.of("alpha", "beta", "gamma", "delta");
        List<String> prompt = List.of("alpha", "beta two", "gamma two", "delta");

        String message = PromptGoldenTest.differenceMessage("owner_player", golden, prompt);

        assertTrue(message.contains("owner_player"), message);
        assertTrue(message.contains("first difference at sorted line 2"), message);
        assertTrue(message.contains("golden:\n    beta\n    gamma\n    delta"), message);
        assertTrue(message.contains("prompt:\n    beta two\n    gamma two\n    delta"), message);
        assertTrue(message.contains("./gradlew updatePromptGolden"), message);
    }

    @Test
    void saysWhenTheLineCountDiffersAfterAnIdenticalPrefix() {
        List<String> golden = List.of("alpha", "beta");
        List<String> prompt = List.of("alpha", "beta", "gamma");

        String message = PromptGoldenTest.differenceMessage("guest_event", golden, prompt);

        assertTrue(message.contains("identical leading lines, then a different line count"), message);
        assertTrue(message.contains("golden: <no more lines>"), message);
        assertTrue(message.contains("prompt:\n    gamma"), message);
        assertTrue(message.contains("./gradlew updatePromptGolden"), message);
    }
}
