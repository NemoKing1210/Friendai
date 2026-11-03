package dev.friendai.features.knowledge.search;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SearchFailureTextTest {
    @Test
    void includesTheLegSummaryAndTheDebugReason() {
        String text = SearchFailureText.miningLeg(
                "minecraft:stone",
                "dug 3 blocks down to Y 62 without finding minecraft:stone",
                "why=no-route x3 cand=0 feet=solid head=air below=air");
        assertTrue(text.contains("minecraft:stone"), text);
        assertTrue(text.contains("dug 3 blocks down to Y 62"), text);
        assertTrue(text.contains("why=no-route x3"), text);
    }

    @Test
    void omitsBlankParts() {
        String text = SearchFailureText.miningLeg("minecraft:stone", " ", "");
        assertFalse(text.isBlank());
        assertFalse(text.contains("why="), text);
    }
}
