package dev.friendai.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CompanionTicketSetTest {
    private static CompanionTicketSet.Key key(String dimension, long chunk, int radius) {
        return new CompanionTicketSet.Key(dimension, chunk, radius);
    }

    @Test
    void sameKeyIsAddedOnlyOnce() {
        CompanionTicketSet set = new CompanionTicketSet();
        CompanionTicketSet.Key a = key("minecraft:overworld", 1L, 1);
        CompanionTicketSet.Change first = set.update(List.of(a));
        assertEquals(List.of(a), first.add());
        assertTrue(first.remove().isEmpty());
        CompanionTicketSet.Change second = set.update(List.of(a, a));
        assertTrue(second.add().isEmpty());
        assertTrue(second.remove().isEmpty());
        assertEquals(1, set.size());
    }

    @Test
    void sharedChunkStaysUntilTheLastUserLeaves() {
        CompanionTicketSet set = new CompanionTicketSet();
        CompanionTicketSet.Key shared = key("minecraft:overworld", 1L, 1);
        set.update(List.of(shared));
        CompanionTicketSet.Change narrowed = set.update(List.of(shared));
        assertTrue(narrowed.remove().isEmpty());
        CompanionTicketSet.Change last = set.update(List.of());
        assertEquals(List.of(shared), last.remove());
        assertEquals(0, set.size());
    }

    @Test
    void movingAddsTheNewKeyBeforeRemovingTheOldOne() {
        CompanionTicketSet set = new CompanionTicketSet();
        CompanionTicketSet.Key from = key("minecraft:overworld", 1L, 1);
        CompanionTicketSet.Key to = key("minecraft:overworld", 2L, 1);
        set.update(List.of(from));
        CompanionTicketSet.Change change = set.update(List.of(to));
        assertEquals(List.of(to), change.add());
        assertEquals(List.of(from), change.remove());
    }

    @Test
    void dimensionAndRadiusArePartOfTheKey() {
        CompanionTicketSet set = new CompanionTicketSet();
        CompanionTicketSet.Key overworld = key("minecraft:overworld", 1L, 1);
        CompanionTicketSet.Key nether = key("minecraft:the_nether", 1L, 1);
        CompanionTicketSet.Key wider = key("minecraft:overworld", 1L, 2);
        CompanionTicketSet.Change change = set.update(List.of(overworld, nether, wider));
        assertEquals(Set.of(overworld, nether, wider), Set.copyOf(change.add()));
        assertEquals(3, set.size());
    }

    @Test
    void emptyDesiredSetRemovesEverything() {
        CompanionTicketSet set = new CompanionTicketSet();
        CompanionTicketSet.Key a = key("minecraft:overworld", 1L, 1);
        CompanionTicketSet.Key b = key("minecraft:overworld", 2L, 2);
        set.update(List.of(a, b));
        CompanionTicketSet.Change change = set.update(List.of());
        assertEquals(Set.of(a, b), Set.copyOf(change.remove()));
        assertEquals(0, set.size());
    }
}
