package dev.friendai.features.social;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class TopicCandidatesTest {
    private static final String EN = "en";

    private static TopicCandidate candidate(String id, String text) {
        return new TopicCandidate(id, text);
    }

    @Test
    void keepsTheOrderAndSkipsAnIdentityAlreadyRaised() {
        TopicRing ring = new TopicRing();
        ring.remember("fact:1");
        List<TopicCandidate> candidates = List.of(
                candidate("fact:1", "You told me earlier: I like oak."),
                candidate("fact:2", "You told me earlier: I like stone."),
                candidate("fact:3", "You told me earlier: I like glass."));

        assertEquals(
                "fact:2",
                TopicCandidates.select(candidates, ring, EN, true).orElseThrow().id());
    }

    @Test
    void skipsTheBannedFilterAndAnOverlongRendering() {
        TopicRing ring = new TopicRing();
        List<TopicCandidate> candidates = List.of(
                candidate("fact:1", "You told me earlier: the death of my horse."),
                candidate("fact:2", "You told me earlier: " + "x".repeat(TopicBank.MAX_LENGTH)),
                candidate("fact:3", "You told me earlier: I like oak."));

        assertEquals(
                "fact:3",
                TopicCandidates.select(candidates, ring, EN, true).orElseThrow().id());
    }

    @Test
    void aQuestionIsOnlyAllowedWhenTheCadencePermitsOne() {
        TopicRing ring = new TopicRing();
        List<TopicCandidate> candidates = List.of(
                candidate("bank:1", "What would you like to work on next?"),
                candidate("bank:2", "Is there anything you would like help with?"));

        assertTrue(TopicCandidates.select(candidates, ring, EN, false).isEmpty());
        assertEquals(
                "bank:1",
                TopicCandidates.select(candidates, ring, EN, true).orElseThrow().id());
    }

    @Test
    void nothingToSayIsSilenceNotAReplacement() {
        assertTrue(TopicCandidates.select(List.of(), new TopicRing(), EN, true).isEmpty());
        assertTrue(TopicCandidates.select(null, new TopicRing(), EN, true).isEmpty());
        assertTrue(TopicCandidates.select(List.of(candidate("fact:1", "  ")), new TopicRing(), EN, true)
                .isEmpty());
    }

    @Test
    void theIdentitySurvivesAnEditedText() {
        TopicRing ring = new TopicRing();
        ring.remember("fact:7");
        TopicCandidate edited = candidate("fact:7", "You told me earlier: I now like stone.");

        assertFalse(TopicCandidates.select(List.of(edited), ring, EN, true).isPresent());
    }
}
