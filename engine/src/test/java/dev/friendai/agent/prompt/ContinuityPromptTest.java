package dev.friendai.agent.prompt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.features.memory.Category;
import dev.friendai.features.memory.Fact;
import dev.friendai.features.memory.Subject;
import dev.friendai.features.places.Place;
import dev.friendai.features.places.PlaceKind;
import dev.friendai.features.places.PlaceSource;
import dev.friendai.features.social.Happening;
import dev.friendai.features.social.HappeningJournal;
import dev.friendai.foundation.spatial.Pos;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ContinuityPromptTest {
    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private static HappeningJournal journal(Happening... happenings) {
        HappeningJournal journal = new HappeningJournal();
        for (Happening happening : happenings) {
            journal.add(happening);
        }
        return journal;
    }

    private static Happening advancement(String text) {
        return new Happening(Happening.Kind.ADVANCEMENT, Happening.Importance.HIGH, 10, "adv:" + text, text);
    }

    private static Happening death() {
        return new Happening(Happening.Kind.DEATH, Happening.Importance.HIGH, 20, "death:1", "you died to a zombie");
    }

    private static Place place(long id, String name, long lastVisit, long updated) {
        return new Place(
                id,
                name,
                PlaceKind.FARM,
                "",
                "minecraft:overworld",
                new Pos(0, 64, 0),
                8,
                Set.of(),
                PlaceSource.MANUAL,
                OWNER,
                0,
                updated,
                lastVisit,
                0,
                "");
    }

    private static Fact fact(int id, Category category, String text) {
        return new Fact(id, Subject.player(OWNER), category, text, OWNER, "Steve", id);
    }

    @Test
    void nothingToSayRendersNothing() {
        assertEquals("", ContinuityPrompt.render(null, null, null, null, null));
        assertEquals("", ContinuityPrompt.render(new HappeningJournal(), "  ", List.of(), List.of(), List.of()));
    }

    @Test
    void theNewestAchievementIsTheMilestone() {
        String block =
                ContinuityPrompt.render(journal(death(), advancement("you earned Stone Age")), "", null, null, null);
        assertEquals(ContinuityPrompt.HEADER + "\n- you earned Stone Age", block);
    }

    @Test
    void deathsAndRespawnsAreNotMilestones() {
        Happening respawn = new Happening(
                Happening.Kind.RESPAWN, Happening.Importance.HIGH, 30, "respawn:1", "you respawned at 1 2 3");
        assertEquals("", ContinuityPrompt.render(journal(death(), respawn), "", null, null, null));
    }

    @Test
    void anOlderAchievementSurvivesNewerWorldEvents() {
        String block =
                ContinuityPrompt.render(journal(advancement("you earned Stone Age"), death()), "", null, null, null);
        assertTrue(block.contains("you earned Stone Age"), block);
        assertFalse(block.contains("you died"), block);
    }

    @Test
    void theActiveProjectIsThePrimaryGoalText() {
        String block = ContinuityPrompt.render(null, "build an iron farm", null, null, null);
        assertEquals(ContinuityPrompt.HEADER + "\n- the active project: build an iron farm", block);
    }

    @Test
    void theKnownPlaceIsTheMostRecentlyVisited() {
        String block = ContinuityPrompt.render(
                null, "", List.of(place(1, "home", 5, 9), place(2, "the wheat field", 9, 9)), null, null);
        assertTrue(block.contains("a place you know: the wheat field"), block);
        assertFalse(block.contains("home"), block);
    }

    @Test
    void theLastVerifiedOutcomeIsCarriedVerbatim() {
        String block = ContinuityPrompt.render(null, "", null, List.of("", "+3 minecraft:oak_log"), null);
        assertEquals(ContinuityPrompt.HEADER + "\n- last verified: +3 minecraft:oak_log", block);
    }

    @Test
    void onlyPreferencesAreRememberedHere() {
        String block = ContinuityPrompt.render(
                null,
                "",
                null,
                null,
                List.of(fact(1, Category.PROFILE, "plays since 2019"), fact(2, Category.PREFERENCE, "prefers oak")));
        assertEquals(ContinuityPrompt.HEADER + "\n- you remember: prefers oak", block);
        assertEquals(
                "",
                ContinuityPrompt.render(null, "", null, null, List.of(fact(1, Category.PROFILE, "plays since 2019"))));
    }

    @Test
    void blankFactsContributeNothing() {
        assertEquals(
                "", ContinuityPrompt.render(null, "", null, List.of("  "), List.of(fact(1, Category.PREFERENCE, " "))));
    }

    @Test
    void atMostThreeItemsAndFourLinesSurvive() {
        String block = ContinuityPrompt.render(
                journal(advancement("you earned Stone Age")),
                "build an iron farm",
                List.of(place(1, "home", 1, 1)),
                List.of("+3 minecraft:oak_log"),
                List.of(fact(1, Category.PREFERENCE, "prefers oak")));
        List<String> lines = block.lines().toList();
        assertEquals(1 + ContinuityPrompt.MAX_ITEMS, lines.size(), block);
        assertTrue(block.contains("you earned Stone Age"), block);
        assertTrue(block.contains("the active project: build an iron farm"), block);
        assertTrue(block.contains("a place you know: home"), block);
        assertFalse(block.contains("last verified"), block);
        assertFalse(block.contains("you remember"), block);
    }

    @Test
    void anItemNeverSpansLinesAndObeysTheHardCaps() {
        String block = ContinuityPrompt.render(null, "build\nan iron farm " + "x".repeat(400), null, null, null);
        assertEquals(2, block.lines().count(), block);
        assertTrue(block.length() <= ContinuityPrompt.MAX_CHARS, block);
        String item = block.lines().skip(1).findFirst().orElseThrow();
        assertTrue(item.startsWith("- the active project: build an iron farm"), item);
        assertTrue(item.length() <= 2 + ContinuityPrompt.MAX_ITEM_CHARS, item);
    }

    @Test
    void theHeaderSaysBackgroundNotScriptAndOnlyWhenItFits() {
        String block = ContinuityPrompt.render(null, "build an iron farm", null, null, null);
        assertTrue(block.startsWith(ContinuityPrompt.HEADER), block);
        assertTrue(ContinuityPrompt.HEADER.contains("not a script"), ContinuityPrompt.HEADER);
        assertTrue(ContinuityPrompt.HEADER.contains("only when it fits"), ContinuityPrompt.HEADER);
        assertTrue(ContinuityPrompt.HEADER.length() < ContinuityPrompt.MAX_CHARS, ContinuityPrompt.HEADER);
    }

    @Test
    void moreThanThreeCandidatesNeverGrowTheBlock() {
        String block = ContinuityPrompt.render(
                journal(advancement("you earned Stone Age")),
                "build an iron farm",
                List.of(place(1, "home", 1, 1)),
                List.of("+3 minecraft:oak_log", "+1 minecraft:stone"),
                List.of(fact(1, Category.PREFERENCE, "prefers oak"), fact(2, Category.PREFERENCE, "likes rain")));
        assertEquals(ContinuityPrompt.MAX_ITEMS, block.lines().count() - 1, block);
        assertTrue(block.length() <= ContinuityPrompt.MAX_CHARS, block);
    }
}
