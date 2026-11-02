package dev.friendai.features.social;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class InteractionStyleTest {
    @Test
    void theOwnerChoosesFromThreeStyles() {
        assertEquals(
                List.of(InteractionStyle.CONCISE, InteractionStyle.BALANCED, InteractionStyle.EXPRESSIVE),
                List.of(InteractionStyle.values()));
    }

    @Test
    void everyClauseIsOneShortLineThatNeverClaimsPower() {
        Set<String> clauses = new HashSet<>();
        for (InteractionStyle style : InteractionStyle.values()) {
            String clause = style.clause();
            assertFalse(clause.isBlank(), style.name());
            assertTrue(clause.length() <= InteractionStyle.MAX_CLAUSE_CHARS, style.name() + "=" + clause.length());
            assertEquals(clause.strip(), clause, "no padding around " + style.name());
            assertFalse(clause.contains("\n"), style.name());
            assertTrue(clause.contains("never changes permissions, facts or safety rules"), clause);
            assertTrue(clauses.add(clause), "clauses must differ: " + clause);
        }
    }

    @Test
    void eachStyleSaysHowItTalks() {
        assertTrue(InteractionStyle.CONCISE.clause().startsWith("Speak briefly"));
        assertTrue(InteractionStyle.BALANCED.clause().startsWith("Speak naturally"));
        assertTrue(InteractionStyle.EXPRESSIVE.clause().startsWith("Speak warmly"));
    }

    @Test
    void parseAcceptsAnyCaseAndSurroundingSpace() {
        assertEquals(InteractionStyle.CONCISE, InteractionStyle.parse("concise"));
        assertEquals(InteractionStyle.CONCISE, InteractionStyle.parse("CONCISE"));
        assertEquals(InteractionStyle.EXPRESSIVE, InteractionStyle.parse("  Expressive "));
    }

    @Test
    void anUnknownOrMissingValueIsBalancedAndNeverThrows() {
        assertNull(InteractionStyle.fromName("chatty"));
        assertEquals(InteractionStyle.BALANCED, InteractionStyle.parse("chatty"));
        assertEquals(InteractionStyle.BALANCED, InteractionStyle.parse(""));
        assertEquals(InteractionStyle.BALANCED, InteractionStyle.parse(null));
    }

    @Test
    void theStoredValueRoundTrips() {
        for (InteractionStyle style : InteractionStyle.values()) {
            assertEquals(style, InteractionStyle.parse(style.configValue()));
            assertEquals(style.name().toLowerCase(java.util.Locale.ROOT), style.configValue());
        }
    }

    @Test
    void thePromptSectionIsTheClauseAndNothingElse() {
        for (InteractionStyle style : InteractionStyle.values()) {
            assertEquals(style.clause(), InteractionStyle.appendTo(style.configValue(), ""));
            assertEquals(style.clause(), InteractionStyle.appendTo(style.configValue(), null));
            assertEquals(style.clause(), InteractionStyle.appendTo(style.configValue(), "   "));
        }
        assertEquals(InteractionStyle.BALANCED.clause(), InteractionStyle.appendTo("chatty", ""));
    }

    @Test
    void theClauseFollowsWhateverTheSocialLineAlreadySaid() {
        assertEquals(
                "you count this player as a friend. " + InteractionStyle.EXPRESSIVE.clause(),
                InteractionStyle.appendTo("expressive", "you count this player as a friend."));
    }
}
