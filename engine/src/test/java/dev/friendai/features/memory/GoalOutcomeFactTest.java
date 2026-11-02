package dev.friendai.features.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class GoalOutcomeFactTest {
    @Test
    void verifiedGoalSaysWhatTheWorldChecked() {
        assertEquals(
                "goal \"Collect 32 oak logs\" \u2014 verified; +32 minecraft:oak_log",
                GoalOutcomeFact.text(
                        "Collect 32 oak logs", GoalOutcomeFact.End.VERIFIED, "+32 minecraft:oak_log", 160));
    }

    @Test
    void unverifiedGoalUsesADifferentWord() {
        assertEquals(
                "goal \"Make a friend\" \u2014 unverified",
                GoalOutcomeFact.text("Make a friend", GoalOutcomeFact.End.UNVERIFIED, null, 160));
    }

    @Test
    void checkedGoalWithoutEvidenceStillSaysVerified() {
        assertEquals(
                "goal \"Return the hoe\" \u2014 verified",
                GoalOutcomeFact.text("Return the hoe", GoalOutcomeFact.End.VERIFIED, " ", 160));
    }

    @Test
    void abandonedWithNothingVerifiedIsNotRemembered() {
        assertNull(GoalOutcomeFact.text("Build a tower", GoalOutcomeFact.End.ABANDONED, null, 160));
        assertNull(GoalOutcomeFact.text("Build a tower", GoalOutcomeFact.End.ABANDONED, "  ", 160));
        assertNull(GoalOutcomeFact.text("  ", GoalOutcomeFact.End.VERIFIED, "+1 dirt", 160));
    }

    @Test
    void abandonedAfterVerifiedWorkKeepsTheProvenance() {
        assertEquals(
                "goal \"Build a tower\" \u2014 abandoned, unverified; +12 minecraft:stone",
                GoalOutcomeFact.text("Build a tower", GoalOutcomeFact.End.ABANDONED, "+12 minecraft:stone", 160));
    }

    @Test
    void longWordingStaysUnderTheCapAndKeepsTheVerdict() {
        String longGoal = "Bring the owner every colour of wool ".repeat(6);
        String text = GoalOutcomeFact.text(longGoal, GoalOutcomeFact.End.VERIFIED, "+64 minecraft:white_wool", 160);
        assertTrue(text.length() <= 160, text);
        assertTrue(text.startsWith("goal \""), text);
        assertTrue(text.endsWith("\" \u2014 verified; +64 minecraft:white_wool"), text);
        assertTrue(text.contains("\u2026"), text);
    }

    @Test
    void aTinyLimitStillYieldsABoundedLine() {
        String text = GoalOutcomeFact.text("Collect oak", GoalOutcomeFact.End.UNVERIFIED, null, 24);
        assertTrue(text.length() <= 24, text);
        assertTrue(text.contains("unverified"), text);
    }

    @Test
    void quotesAndLineBreaksStayOnOneLine() {
        assertEquals(
                "goal \"Say 'hi' then wait\" \u2014 verified",
                GoalOutcomeFact.text(" Say \"hi\" then\nwait ", GoalOutcomeFact.End.VERIFIED, null, 160));
    }
}
