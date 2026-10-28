package dev.friendai.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.knowledge.search.ResourceSearchProfile;
import dev.friendai.knowledge.search.SearchCoverage;
import dev.friendai.knowledge.search.SearchLedger;
import dev.friendai.knowledge.search.SearchSession;
import dev.friendai.pathing.Pos;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SearchLedgerPersistenceTest {
    @Test
    void coverageAndActiveLedgerSurviveKnowledgeRoundTrip() {
        UUID owner = UUID.randomUUID();
        ResourceKnowledge original = new ResourceKnowledge(KnowledgeLimits.DEFAULTS);
        var session = original.searchLedger()
                .acquire(owner, "minecraft:overworld", "iron", 100, SearchLedger.LOCAL_DEFAULTS, new Pos(2, 64, 3));
        session.advance(new SearchSession.Input(
                110,
                "minecraft:overworld",
                new Pos(2, 64, 3),
                true,
                false,
                SearchSession.LegOutcome.NONE,
                null,
                0,
                0,
                0,
                0));
        original.recordCoverage(
                "minecraft:overworld",
                new Pos(5, 64, 6),
                "minecraft:iron_ore",
                ResourceSearchProfile.Strategy.SURFACE,
                SearchCoverage.Outcome.INSPECTED,
                110);
        original.trails()
                .recordSegment(
                        "minecraft:overworld", java.util.List.of(new Pos(0, 64, 0), new Pos(8, 64, 0)), 20, false);

        ResourceKnowledge restored = ResourceKnowledge.restore(original.snapshot(), KnowledgeLimits.DEFAULTS);

        assertEquals(1, restored.searchCoverage().snapshot().size());
        assertEquals(
                SearchSession.Phase.INTERRUPTED,
                restored.searchLedger().snapshot().get(0).phase());
        assertEquals(
                java.util.List.of(new Pos(0, 64, 0), new Pos(8, 64, 0)),
                restored.trails().route("minecraft:overworld", new Pos(0, 64, 0), new Pos(8, 64, 0)));
        var resumed = restored.searchLedger()
                .acquire(owner, "minecraft:overworld", "iron", 120, SearchLedger.LOCAL_DEFAULTS, new Pos(0, 0, 0));
        assertEquals(10, resumed.snapshot().spentTicks());
        assertEquals(SearchSession.Phase.CHOOSE_FRONTIER, resumed.snapshot().phase());
    }

    @Test
    void statsAndAskOnceSurviveKnowledgeRoundTrip() {
        UUID owner = UUID.randomUUID();
        ResourceKnowledge original = new ResourceKnowledge(KnowledgeLimits.DEFAULTS);
        original.searchLedger().stats().record("minecraft:iron_ore", true, 12, 240, 3);
        original.searchLedger().markAsked(owner, "minecraft:overworld", "iron ore");

        ResourceKnowledge restored = ResourceKnowledge.restore(original.snapshot(), KnowledgeLimits.DEFAULTS);

        assertEquals(
                1,
                restored.searchLedger().stats().counters("minecraft:iron_ore").finds());
        assertEquals(
                12,
                restored.searchLedger().stats().counters("minecraft:iron_ore").travelBlocks());
        org.junit.jupiter.api.Assertions.assertTrue(
                restored.searchLedger().asked(owner, "minecraft:overworld", " IRON  ORE "));
    }

    @Test
    void ledgerChangesMarkTheKnowledgeBookDirty() {
        ResourceKnowledge book = new ResourceKnowledge(KnowledgeLimits.DEFAULTS);
        long before = book.revision();
        book.searchLedger().stats().record("minecraft:iron_ore", true, 1, 2, 3);
        assertTrue(book.revision() > before, "stats must dirty the book");

        before = book.revision();
        UUID owner = UUID.randomUUID();
        book.searchLedger().markAsked(owner, "minecraft:overworld", "iron");
        assertTrue(book.revision() > before, "ask-once must dirty the book");

        before = book.revision();
        var session = book.searchLedger().acquire(owner, "minecraft:overworld", "iron", 1, SearchLedger.LOCAL_DEFAULTS);
        assertTrue(book.revision() > before, "a new session must dirty the book");

        before = book.revision();
        session.advance(new SearchSession.Input(
                2,
                "minecraft:overworld",
                new Pos(0, 64, 0),
                true,
                false,
                SearchSession.LegOutcome.NONE,
                null,
                0,
                0,
                0,
                0));
        assertTrue(book.revision() > before, "session work must dirty the book");
    }
}
