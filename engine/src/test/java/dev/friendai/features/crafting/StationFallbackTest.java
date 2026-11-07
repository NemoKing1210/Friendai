package dev.friendai.features.crafting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class StationFallbackTest {
    @Test
    void aRawItemComesBeforeAStationNeed() {
        FakeRecipeBook book = new FakeRecipeBook()
                .add(new Recipe("test:cut", "test:cut", 1, List.of(Ingredient.of("test:raw")), Station.STONECUTTER));
        List<CraftPlanner.Target> targets = List.of(new CraftPlanner.Target("test:cut", 1));
        List<CraftPlanner.RawNeed> demand = CraftPlanner.materialsFor(targets, Map.of(), book, Set.of());
        AtomicInteger asked = new AtomicInteger();

        StationFallback.Decision decision = StationFallback.decide(targets, demand, Map.of(), book, Set.of(), need -> {
            asked.incrementAndGet();
            return "minecraft:stonecutter";
        });

        StationFallback.Next next = assertInstanceOf(StationFallback.Next.class, decision);
        assertEquals("test:raw", next.need().label());
        assertFalse(next.need().stationNeed());
        assertNull(next.candidate());
        // Gathering raw material is progress; the station is only proven once nothing raw is left.
        assertEquals(0, asked.get());
    }

    @Test
    void anObtainableStationIsHandedBackWithItsCandidate() {
        FakeRecipeBook book = new FakeRecipeBook()
                .add(new Recipe("test:cut", "test:cut", 1, List.of(Ingredient.of("test:raw")), Station.STONECUTTER));
        List<CraftPlanner.Target> targets = List.of(new CraftPlanner.Target("test:cut", 1));
        Map<String, Integer> inventory = Map.of("test:raw", 1);
        List<CraftPlanner.RawNeed> demand = CraftPlanner.materialsFor(targets, inventory, book, Set.of());
        assertEquals(
                List.of(new CraftPlanner.RawNeed(
                        "minecraft:stonecutter", Set.of("minecraft:stonecutter"), 1, Station.STONECUTTER)),
                demand);
        AtomicInteger asked = new AtomicInteger();

        StationFallback.Decision decision = StationFallback.decide(targets, demand, inventory, book, Set.of(), need -> {
            asked.incrementAndGet();
            return "minecraft:stonecutter";
        });

        StationFallback.Next next = assertInstanceOf(StationFallback.Next.class, decision);
        assertEquals(Station.STONECUTTER, next.need().station());
        assertEquals("minecraft:stonecutter", next.candidate());
        assertEquals(1, asked.get());
    }

    @Test
    void aStationThatCannotBeMadeHereFailsWithItsIdAndIsNeverAskedAgain() {
        FakeRecipeBook book =
                new FakeRecipeBook().add(new Recipe("test:cut", "test:cut", 1, List.of(), Station.STONECUTTER));
        List<CraftPlanner.Target> targets = List.of(new CraftPlanner.Target("test:cut", 1));
        List<CraftPlanner.RawNeed> demand = CraftPlanner.materialsFor(targets, Map.of(), book, Set.of());
        AtomicInteger asked = new AtomicInteger();

        StationFallback.Decision decision = StationFallback.decide(targets, demand, Map.of(), book, Set.of(), need -> {
            asked.incrementAndGet();
            return null;
        });

        StationFallback.Blocked blocked = assertInstanceOf(StationFallback.Blocked.class, decision);
        assertEquals("minecraft:stonecutter", blocked.stationId());
        // The blocked station is dropped from the re-planned demand, so the loop terminates and asks once.
        assertEquals(1, asked.get());
    }

    @Test
    void aBlockedStationLetsTheNextRecipeRouteWin() {
        FakeRecipeBook book = new FakeRecipeBook()
                .add(new Recipe("test:first", "test:out", 1, List.of(), Station.STONECUTTER))
                .add(new Recipe("test:second", "test:out", 1, List.of(), Station.SMITHING_TABLE));
        List<CraftPlanner.Target> targets = List.of(new CraftPlanner.Target("test:out", 1));
        List<CraftPlanner.RawNeed> demand = CraftPlanner.materialsFor(targets, Map.of(), book, Set.of());
        List<Station> asked = new ArrayList<>();

        StationFallback.Decision decision = StationFallback.decide(targets, demand, Map.of(), book, Set.of(), need -> {
            asked.add(need.station());
            return need.station() == Station.SMITHING_TABLE ? "minecraft:smithing_table" : null;
        });

        StationFallback.Next next = assertInstanceOf(StationFallback.Next.class, decision);
        assertEquals(Station.SMITHING_TABLE, next.need().station());
        assertEquals("minecraft:smithing_table", next.candidate());
        // The first route's station was rejected, the demand re-planned without it, and the second route chosen.
        assertEquals(List.of(Station.STONECUTTER, Station.SMITHING_TABLE), asked);
    }

    @Test
    void nothingMissingMeansTheTargetsCanBeCrafted() {
        List<CraftPlanner.Target> targets = List.of(new CraftPlanner.Target("minecraft:stick", 4));

        StationFallback.Decision decision = StationFallback.decide(
                targets, List.of(), Map.of("minecraft:oak_planks", 2), FakeRecipeBook.vanillaLike(), Set.of(), need -> {
                    throw new AssertionError("no station is needed");
                });

        assertInstanceOf(StationFallback.Done.class, decision);
    }
}
