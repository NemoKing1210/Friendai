package dev.friendai.skill;

import static org.junit.jupiter.api.Assertions.*;

import dev.friendai.tool.*;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TeachingPolicyTest {
    @TempDir
    Path dir;

    final UUID owner = UUID.randomUUID();
    final Caller caller = new Caller(owner, "Owner", AccessLevel.OWNER);

    TeachingContext context(String id) {
        return new TeachingContext(owner, id, 1, TeachingContext.OutcomeKind.GOAL_SUCCESS);
    }

    @Test
    void outcomeAndCallerGenerationAreRequired() {
        assertFalse(TeachingPolicy.authorize(null, caller, 1));
        assertFalse(TeachingPolicy.authorize(context("goal:1"), caller, 2));
        assertFalse(TeachingPolicy.authorize(
                context("goal:1"), new Caller(UUID.randomUUID(), "Other", AccessLevel.OWNER), 1));
        assertTrue(TeachingPolicy.authorize(context("goal:1"), caller, 1));
    }

    @Test
    void restartAndClockRollbackDoNotRefillWriteBudget() {
        try (var store = new SkillStore(dir, SkillLimits.defaults(), s -> s)) {
            var ledger = new TeachingLedger(store.book(owner));
            for (int i = 0; i < 6; i++) {
                assertTrue(ledger.available("goal:" + i, 10_000, 6, 2));
                ledger.recordSuccessfulWrite("goal:" + i, 10_000);
            }
            assertFalse(ledger.available("goal:new", 10_000, 6, 2));
        }
        try (var store = new SkillStore(dir, SkillLimits.defaults(), s -> s)) {
            var ledger = new TeachingLedger(store.book(owner));
            assertFalse(ledger.available("goal:new", 1, 6, 2));
            assertTrue(ledger.available("goal:new", 3_610_000, 6, 2));
        }
    }

    @Test
    void repeatedOutcomeCannotMintNewWrites() {
        var book = new SkillBook(SkillLimits.defaults(), s -> s, InstructionSkill.Scope.COMPANION);
        var ledger = new TeachingLedger(book);
        ledger.recordSuccessfulWrite("shift:1", 1);
        ledger.recordSuccessfulWrite("shift:1", 2);
        assertFalse(ledger.available("shift:1", 3_700_000, 6, 2));
    }
}
