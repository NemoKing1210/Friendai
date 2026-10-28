package dev.friendai.skill;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import dev.friendai.config.ServerConfig;
import dev.friendai.core.SkillService;
import dev.friendai.execution.tool.*;
import dev.friendai.foundation.teaching.TeachingContext;
import dev.friendai.memory.MemoryStore;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SkillTeachingActionsTest {
    @TempDir
    Path dir;

    @Test
    void ownerProfileLanguageStillAppliesWhenInstructionSkillsAreDisabled() {
        UUID owner = UUID.randomUUID();
        var config = ServerConfig.defaults();
        config.skills.enabled = false;
        var memory = new MemoryStore(dir.resolve("memory"), () -> 0L);
        memory.book(owner).setOwnerProfile(new dev.friendai.memory.OwnerProfile("ru", "Alex"));
        try (var service = new SkillService(dir.resolve("skills"), () -> config, memory)) {
            var ownerTurn = service.begin(
                    owner,
                    new Caller(owner, "Owner", AccessLevel.OWNER),
                    "hello",
                    dev.friendai.agent.turn.TurnKind.PLAYER,
                    "",
                    false,
                    false);
            assertEquals(
                    "ru",
                    ownerTurn
                            .persona(new dev.friendai.agent.turn.Persona("F", "Guide", "AUTO"))
                            .language());
            assertTrue(ownerTurn.sections().isEmpty());
            var guestTurn = service.begin(
                    owner,
                    new Caller(UUID.randomUUID(), "Guest", AccessLevel.GUEST),
                    "hello",
                    dev.friendai.agent.turn.TurnKind.PLAYER,
                    "",
                    false,
                    false);
            assertEquals(
                    "auto",
                    guestTurn
                            .persona(new dev.friendai.agent.turn.Persona("F", "Guide", "auto"))
                            .language());
        }
    }

    @Test
    void currentSecretsCannotBeExportedAfterConfigChangesAndLiveCapsApply() {
        UUID owner = UUID.randomUUID();
        var config = ServerConfig.defaults();
        config.skills.ownerCount = 1;
        try (var service = new SkillService(
                dir.resolve("skills"), () -> config, new MemoryStore(dir.resolve("memory"), () -> 0L))) {
            var actor = new SkillActor(owner, new Caller(owner, "Owner", AccessLevel.OWNER), false);
            var created = service.actions()
                    .apply(actor, SkillAction.create(SkillDraft.simple("One", "future-private-value")));
            assertTrue(created.status().ok());
            assertFalse(service.actions()
                    .apply(actor, SkillAction.create(SkillDraft.simple("Two", "Inspect facts.")))
                    .status()
                    .ok());
            var skill = created.skill().orElseThrow();
            config.profiles.get("main").apiKey = "future-private-value";
            var export = service.actions()
                    .apply(actor, new SkillAction(SkillAction.Operation.EXPORT, skill.id(), 1, null, false, ""));
            assertFalse(export.status().ok());
            assertFalse(export.text().contains("future-private-value"));
            assertTrue(service.listed(owner).stream().noneMatch(s -> s.id().equals(skill.id())));
        }
    }

    @Test
    void teachingRequiresAnOutcomeAndCreatesPendingModelData() {
        UUID owner = UUID.randomUUID();
        var caller = new Caller(owner, "Owner", AccessLevel.OWNER);
        var config = ServerConfig.defaults();
        try (var service = new SkillService(
                dir.resolve("skills"), () -> config, new MemoryStore(dir.resolve("memory"), () -> 0L))) {
            var args = JsonParser.parseString(
                            "{\"action\":\"create\",\"name\":\"Material check\",\"body\":\"Inspect materials before planning.\"}")
                    .getAsJsonObject();
            assertTrue(service.teach(owner, caller, args).error());
            service.setTeachingContextSource(
                    id -> new TeachingContext(owner, "goal:1", 1, TeachingContext.OutcomeKind.GOAL_SUCCESS));
            assertFalse(service.teach(owner, caller, args).error());
            var skill = service.store().book(owner).snapshot().getFirst();
            assertEquals(InstructionSkill.Source.MODEL, skill.source());
            assertEquals(InstructionSkill.Activation.PENDING, skill.activation());
            args.addProperty("action", "update");
            args.addProperty("id", skill.id());
            args.addProperty("expectedVersion", 1);
            assertFalse(service.teach(owner, caller, args).error());
            assertEquals(
                    InstructionSkill.Activation.PENDING,
                    service.store().book(owner).find(skill.id()).orElseThrow().activation());
            assertTrue(
                    service.teach(owner, caller, args).error(), "two successful writes consume the outcome allowance");
            service.setTeachingContextSource(id -> null);
            assertTrue(service.teach(owner, caller, args).error());
        }
    }
}
