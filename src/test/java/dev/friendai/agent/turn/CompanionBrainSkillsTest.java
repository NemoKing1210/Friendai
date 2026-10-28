package dev.friendai.agent.turn;

import static org.junit.jupiter.api.Assertions.*;

import dev.friendai.execution.tool.*;
import dev.friendai.foundation.teaching.TeachingContext;
import dev.friendai.memory.*;
import dev.friendai.skill.*;
import dev.friendai.testutil.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class CompanionBrainSkillsTest {
    @Test
    void aDirectorChoiceCannotBorrowTeachingAuthorityFromABatchedOutcome() {
        UUID owner = UUID.randomUUID();
        var gateway = new FakeGateway();
        var first = gateway.enqueuePending();
        gateway.enqueuePending();
        var brain = new CompanionBrain(
                () -> new Persona("Friendai", "Guide", "auto"),
                () -> new BrainSettings(8, 30, 0, 0),
                () -> gateway,
                new FakeToolExecutor(),
                () -> "state",
                new RecordingSpeaker(),
                new ManualScheduler(),
                AccessResolver.of(owner, id -> false));
        brain.post(new BrainEvent.PlayerSaid(owner, "Owner", "hello", BrainEvent.Source.CHAT));
        brain.post(new BrainEvent.SkillOutcome("shift:1", TeachingContext.OutcomeKind.SHIFT_FAILURE, "No materials"));
        brain.post(new BrainEvent.FreeTimeShift(List.of(), "", List.of(), List.of(), ""));
        first.complete(FakeGateway.text("[silent]"));
        assertEquals(2, gateway.requests.size());
        assertNull(brain.teachingContext());
    }

    @Test
    void outcomeContextExpiresOnCancelAndOrdinaryChatHasNone() {
        UUID owner = UUID.randomUUID();
        var gateway = new FakeGateway();
        gateway.enqueuePending();
        var brain = new CompanionBrain(
                () -> new Persona("Friendai", "Guide", "auto"),
                () -> new BrainSettings(8, 30, 0, 0),
                () -> gateway,
                new FakeToolExecutor(),
                () -> "state",
                new RecordingSpeaker(),
                new ManualScheduler(),
                AccessResolver.of(owner, id -> false));
        brain.post(new BrainEvent.SkillOutcome("goal:1", TeachingContext.OutcomeKind.GOAL_SUCCESS, "Stone tools made"));
        assertNotNull(brain.teachingContext());
        brain.cancel();
        assertNull(brain.teachingContext());
        gateway.enqueuePending();
        brain.post(new BrainEvent.PlayerSaid(owner, "Owner", "hello", BrainEvent.Source.CHAT));
        assertNull(brain.teachingContext());
    }

    @Test
    void gatewayReceivesCurrentTurnSnapshotAndOwnerLanguageOnlyForOwner() {
        UUID owner = UUID.randomUUID(), guest = UUID.randomUUID();
        var book = new SkillBook(SkillLimits.defaults(), s -> s, InstructionSkill.Scope.COMPANION);
        var first = book.create(
                SkillDraft.simple("Lesson", "UNIQUE_SKILL_SENTINEL"), InstructionSkill.Source.OWNER, owner, 1, false);
        var gateway = new FakeGateway();
        var scheduler = new ManualScheduler();
        gateway.enqueue(FakeGateway.text("Hello"));
        gateway.enqueue(FakeGateway.text("Again"));
        gateway.enqueue(FakeGateway.text("Welcome"));
        var brain = new CompanionBrain(
                () -> new Persona("Friendai", "Guide", "auto"),
                () -> new BrainSettings(8, 30, 0, 0),
                () -> gateway,
                new FakeToolExecutor(),
                () -> "state",
                new RecordingSpeaker(),
                scheduler,
                AccessResolver.of(owner, id -> false));
        brain.setSkillSource((caller, message, turn) -> new SkillPromptSession(
                book.snapshot(),
                new SkillContext(
                        caller,
                        message,
                        "",
                        Set.of(InstructionSkill.Kind.CHAT),
                        false,
                        CompanionDescription.EMPTY,
                        new OwnerProfile("ru", "Alex")),
                24000,
                2400));
        brain.post(new BrainEvent.PlayerSaid(owner, "Owner", "hello", BrainEvent.Source.CHAT));
        scheduler.advance(0);
        assertTrue(gateway.requests.getFirst().system().contains("UNIQUE_SKILL_SENTINEL"));
        assertTrue(gateway.requests.getFirst().system().contains("Always reply in ru."));
        book.edit(
                first.id(),
                1,
                new SkillDraft(
                        first.name(), "", first.body(), false, false, first.turnKinds(), List.of(), List.of(), false),
                2);
        brain.post(new BrainEvent.PlayerSaid(owner, "Owner", "again", BrainEvent.Source.CHAT));
        scheduler.advance(0);
        assertFalse(gateway.requests.get(1).system().contains("UNIQUE_SKILL_SENTINEL"));
        brain.post(new BrainEvent.PlayerSaid(guest, "Guest", "hello", BrainEvent.Source.CHAT));
        scheduler.advance(0);
        assertFalse(gateway.requests.get(2).system().contains("Alex"));
    }
}
