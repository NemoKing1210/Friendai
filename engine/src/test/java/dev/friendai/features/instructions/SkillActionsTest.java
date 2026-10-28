package dev.friendai.features.instructions;

import static org.junit.jupiter.api.Assertions.*;

import dev.friendai.execution.tool.*;
import dev.friendai.infrastructure.persistence.instructions.SkillStore;
import java.nio.file.Path;
import java.time.Clock;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SkillActionsTest {
    @TempDir
    Path dir;

    final UUID owner = UUID.fromString("00000000-0000-0000-0000-000000000001");

    SkillActor actor(AccessLevel access) {
        return new SkillActor(owner, new Caller(owner, "Owner", access), false);
    }

    @Test
    void guestAndForgedOwnerCannotWrite() {
        try (var store = new SkillStore(dir, SkillLimits.defaults(), s -> s)) {
            var actions = new SkillActions(store, List.of(), Clock.systemUTC());
            assertFalse(actions.apply(
                            actor(AccessLevel.GUEST), SkillAction.create(SkillDraft.simple("One", "Inspect facts.")))
                    .status()
                    .ok());
            var forged = new SkillActor(owner, new Caller(UUID.randomUUID(), "Guest", AccessLevel.OWNER), false);
            assertFalse(actions.apply(forged, SkillAction.create(SkillDraft.simple("One", "Inspect facts.")))
                    .status()
                    .ok());
            assertTrue(store.book(owner).snapshot().isEmpty());
        }
    }

    @Test
    void importDropsAuthorityAndExportOmitsIdentity() {
        try (var store = new SkillStore(dir, SkillLimits.defaults(), s -> s)) {
            var actions = new SkillActions(store, List.of(), Clock.systemUTC());
            var parser = new SkillMenuActions(actions, actor(AccessLevel.OWNER));
            var result = parser.apply(
                    "import",
                    "{\"name\":\"One\",\"body\":\"Inspect facts.\",\"source\":\"builtin\",\"scope\":\"server\",\"author\":\"intruder\"}");
            assertTrue(result.status().ok());
            var skill = result.skill().orElseThrow();
            assertEquals(InstructionSkill.Source.OWNER, skill.source());
            assertEquals(InstructionSkill.Scope.COMPANION, skill.scope());
            var exported = actions.apply(
                            actor(AccessLevel.OWNER),
                            new SkillAction(SkillAction.Operation.EXPORT, skill.id(), 1, null, false, ""))
                    .text();
            assertFalse(exported.contains(owner.toString()));
            assertTrue(exported.contains("Inspect facts."));
        }
    }

    @Test
    void pendingNeedsOwnerApprovalAndStaleChangesAreRejected() {
        try (var store = new SkillStore(dir, SkillLimits.defaults(), s -> s)) {
            var skill = store.book(owner)
                    .create(
                            SkillDraft.simple("Lesson", "Inspect facts."),
                            InstructionSkill.Source.MODEL,
                            owner,
                            1,
                            true);
            var actions = new SkillActions(store, List.of(), Clock.systemUTC());
            assertTrue(actions.apply(
                            actor(AccessLevel.OWNER),
                            new SkillAction(SkillAction.Operation.APPROVE, skill.id(), 1, null, false, ""))
                    .status()
                    .ok());
            assertEquals(
                    InstructionSkill.Activation.ACTIVE,
                    store.book(owner).find(skill.id()).orElseThrow().activation());
            assertFalse(actions.apply(
                            actor(AccessLevel.OWNER),
                            new SkillAction(SkillAction.Operation.DELETE, skill.id(), 1, null, false, ""))
                    .status()
                    .ok());
        }
    }
}
