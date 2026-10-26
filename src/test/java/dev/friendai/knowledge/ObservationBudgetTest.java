package dev.friendai.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ObservationBudgetTest {
    @Test
    void fourCompanionsShareBudgetFairly() {
        List<UUID> companions = List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        int[] progress = new int[companions.size()];
        int totalRays = 0;
        int totalCells = 0;

        for (long tick = 0; tick < 4; tick++) {
            KnowledgeLimits.ObservationBudget budget = KnowledgeLimits.observationBudget(1_000_000, () -> 0L);
            int start = budget.roundRobinStart(companions.size(), tick);
            for (int offset = 0; offset < companions.size(); offset++) {
                int index = (start + offset) % companions.size();
                UUID owner = companions.get(index);
                for (int ray = 0; ray < 32 && budget.tryRay(owner); ray++) {
                    assertTrue(budget.tryCell());
                    progress[index]++;
                    totalRays++;
                    totalCells++;
                }
            }
            assertTrue(totalRays <= 4 * 256);
            assertTrue(totalCells <= 4 * 128);
        }

        for (int companionProgress : progress) assertTrue(companionProgress > 0);
    }

    @Test
    void oneFourAndSixteenCompanionsStayWithinEveryTickCapAndAllProgress() {
        for (int count : List.of(1, 4, 16)) {
            List<UUID> companions = new ArrayList<>();
            for (int i = 0; i < count; i++) companions.add(UUID.randomUUID());
            int[] progress = new int[count];
            for (long tick = 0; tick < 1_200; tick++) {
                KnowledgeLimits.ObservationBudget budget = KnowledgeLimits.observationBudget(1_000_000, () -> 0L);
                int start = budget.roundRobinStart(count, tick);
                int[] tickRays = new int[count];
                for (int offset = 0; offset < count; offset++) {
                    int index = (start + offset) % count;
                    UUID companion = companions.get(index);
                    while (budget.availableCells() > 0 && budget.availableRays(companion) > 0
                            && budget.tryRay(companion)) {
                        assertTrue(budget.tryCell());
                        progress[index]++;
                        tickRays[index]++;
                    }
                }
                assertTrue(budget.raysUsed() <= 256, "global ray cap exceeded with " + count + " companions");
                assertTrue(budget.cellsUsed() <= 128, "global cell cap exceeded with " + count + " companions");
                for (int rays : tickRays) assertTrue(rays <= 32,
                        "per-companion ray cap exceeded with " + count + " companions");
            }
            for (int companionProgress : progress) assertTrue(companionProgress > 0,
                    "a continuously active companion received no work with " + count + " companions");
        }
    }

    @Test
    void focusAndPassiveWorkUseTheSameCompanionLimit() {
        UUID owner = UUID.randomUUID();
        KnowledgeLimits.ObservationBudget budget = KnowledgeLimits.observationBudget(1_000_000, () -> 0L);
        int passive = 0;
        int focused = 0;
        while (passive < 20 && budget.tryRay(owner)) passive++;
        while (focused < 20 && budget.tryRay(owner)) focused++;
        assertEquals(20, passive);
        assertEquals(12, focused);
        assertFalse(budget.tryRay(owner));
    }

    @Test
    void deadlineStopsFurtherWork() {
        long[] now = {0};
        KnowledgeLimits.ObservationBudget budget = KnowledgeLimits.observationBudget(10, () -> now[0]);
        UUID owner = UUID.randomUUID();
        assertTrue(budget.tryRay(owner));
        now[0] = 10;
        assertFalse(budget.tryRay(owner));
        assertEquals(0, budget.availableCells());
    }
}
