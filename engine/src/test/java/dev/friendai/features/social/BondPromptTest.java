package dev.friendai.features.social;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class BondPromptTest {
    @Test
    void nothingIsSaidAtTheBaseTierWithACalmFace() {
        assertEquals("", BondPrompt.line(BondLedger.Tier.STRANGER, Mood.CALM, List.of(), true));
    }

    @Test
    void aGuestNeverSeesTheBond() {
        assertEquals("", BondPrompt.line(BondLedger.Tier.CLOSE, Mood.PLEASED, List.of("rescue"), false));
    }

    @Test
    void theLineNamesTheTierTheMoodAndTheNewestReasonAndCarriesNoNumber() {
        String line = BondPrompt.line(BondLedger.Tier.FRIEND, Mood.PLEASED, List.of("gift: oak_log", "rescue"), true);
        assertTrue(line.contains("friend"), line);
        assertTrue(line.contains("pleased"), line);
        assertTrue(line.contains("gift: oak_log"), line);
        assertTrue(line.chars().noneMatch(Character::isDigit), "no score may reach the prompt: " + line);
    }

    @Test
    void theLineStaysWithinItsBudget() {
        String line = BondPrompt.line(
                BondLedger.Tier.CLOSE,
                Mood.DISPLEASED,
                List.of("owner hit with a long explanation that goes on and on and on and on and on"),
                true);
        assertTrue(line.length() <= BondPrompt.MAX_CHARS, "line=" + line.length());
    }
}
