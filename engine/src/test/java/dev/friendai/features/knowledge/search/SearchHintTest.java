package dev.friendai.features.knowledge.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.features.navigation.pathing.Pos;
import org.junit.jupiter.api.Test;

class SearchHintTest {
    private static final Pos ORIGIN = new Pos(0, 64, 0);

    private static SearchPlanner.Frontier frontier(int x, SearchPlanner.Terrain terrain) {
        return new SearchPlanner.Frontier(
                new Pos(x, 64, 0), new Pos(x, 64, 0), ResourceSearchProfile.Strategy.SURFACE, 1, 32, terrain);
    }

    @Test
    void directionAddsBonusOnlyToThatSide() {
        SearchHint hint = SearchHint.create(SearchPlanner.Heading.EAST, null, null, null, 0, "forest east", 100);
        assertEquals(SearchHint.BONUS, hint.bonusFor(frontier(16, SearchPlanner.Terrain.NEUTRAL), ORIGIN, 100));
        assertEquals(0, hint.bonusFor(frontier(-16, SearchPlanner.Terrain.NEUTRAL), ORIGIN, 100));
    }

    @Test
    void biomeAndAreaAlsoMatch() {
        SearchHint biome = SearchHint.create(null, null, "minecraft:forest", null, 0, "", 100);
        assertEquals(
                SearchHint.BONUS,
                biome.bonusFor(
                        frontier(16, new SearchPlanner.Terrain(false, 0, 0, false, "minecraft:forest")), ORIGIN, 100));
        assertEquals(
                0,
                biome.bonusFor(
                        frontier(16, new SearchPlanner.Terrain(false, 0, 0, false, "minecraft:ocean")), ORIGIN, 100));

        SearchHint area = SearchHint.create(null, "village", null, new Pos(100, 64, 100), 16, "", 100);
        var near = new SearchPlanner.Frontier(
                new Pos(110, 64, 100), new Pos(110, 64, 100), ResourceSearchProfile.Strategy.SURFACE, 1, 32);
        assertEquals(SearchHint.BONUS, area.bonusFor(near, ORIGIN, 100));
    }

    @Test
    void expiredHintHasNoBonusAndFailsValidation() {
        SearchHint hint = SearchHint.create(SearchPlanner.Heading.EAST, null, null, null, 0, "", 100);
        assertEquals(0, hint.bonusFor(frontier(16, SearchPlanner.Terrain.NEUTRAL), ORIGIN, hint.expiresTick()));
        var rejection = hint.validate(new SearchHint.ValidationFacts(true, true, true, true), hint.expiresTick());
        assertFalse(rejection.accepted());
        assertTrue(rejection.reason().contains("expired"));
    }

    @Test
    void validationNamesTheWorldReason() {
        SearchHint hint = SearchHint.create(SearchPlanner.Heading.NORTH, null, null, null, 0, "", 100);
        assertTrue(hint.validate(new SearchHint.ValidationFacts(true, true, true, true), 100)
                .accepted());
        assertTrue(hint.validate(new SearchHint.ValidationFacts(false, true, true, true), 100)
                .reason()
                .contains("not loaded"));
        assertTrue(hint.validate(new SearchHint.ValidationFacts(true, false, true, true), 100)
                .reason()
                .contains("water or lava"));
        assertTrue(hint.validate(new SearchHint.ValidationFacts(true, true, false, true), 100)
                .reason()
                .contains("protected"));
        assertTrue(hint.validate(new SearchHint.ValidationFacts(true, true, true, false), 100)
                .reason()
                .contains("world border"));
    }

    @Test
    void hintWithoutAnySteeringIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> SearchHint.create(null, null, null, null, 0, "", 0));
    }
}
