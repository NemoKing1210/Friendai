package dev.friendai.skill;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SkillStoreTest {
    @TempDir Path dir;
    final UUID owner = UUID.fromString("00000000-0000-0000-0000-000000000001");
    @Test void timestampsArePortableAndMalformedMetadataCannotLeakThroughErrors() {
        var book=new SkillBook(SkillLimits.defaults(),s->s,InstructionSkill.Scope.COMPANION);
        book.create(SkillDraft.simple("Time","Inspect facts."),InstructionSkill.Source.OWNER,owner,10,false);
        var codec=new SkillFile(SkillLimits.defaults(),s->s,InstructionSkill.Scope.COMPANION);
        String saved=codec.save(book);
        var time=com.google.gson.JsonParser.parseString(saved).getAsJsonObject().getAsJsonArray("skills").get(0).getAsJsonObject().get("createdAt").getAsString();
        assertEquals("1970-01-01T00:00:00.010Z",time);
        assertEquals(10,codec.load(saved).snapshot().getFirst().createdAt());
        var error=assertThrows(IllegalArgumentException.class,()->codec.load("{\"version\":\"private-value\"}"));
        assertFalse(error.getMessage().contains("private-value"));
    }

    @Test void editsRoundTripWithoutReusingIds() {
        String id;
        try (var store = new SkillStore(dir, SkillLimits.defaults(), s -> s)) {
            var book = store.book(owner);
            var value = book.create(SkillDraft.simple("Stone", "Inspect materials first."), InstructionSkill.Source.OWNER, owner, 10, false);
            id = value.id();
            book.delete(id, value.version());
            var next = book.create(SkillDraft.simple("Roof", "Inspect support first."), InstructionSkill.Source.OWNER, owner, 20, false);
            assertNotEquals(id, next.id());
        }
        try (var store = new SkillStore(dir, SkillLimits.defaults(), s -> s)) {
            assertEquals(1, store.book(owner).snapshot().size());
            assertEquals("Roof", store.book(owner).snapshot().getFirst().name());
        }
    }

    @Test void futureFileIsNeverReplaced() throws Exception {
        String original = "{\"version\":99,\"valuable\":\"future data\"}";
        Files.writeString(dir.resolve(owner + ".json"), original);
        try (var store = new SkillStore(dir, SkillLimits.defaults(), s -> s)) {
            assertThrows(IllegalStateException.class, () -> store.book(owner));
            store.book(UUID.randomUUID()).create(SkillDraft.simple("Other", "Inspect facts."), InstructionSkill.Source.OWNER, owner, 10, false);
        }
        assertEquals(original, Files.readString(dir.resolve(owner + ".json")));
        assertFalse(Files.exists(dir.resolve(owner + ".json.bak")));
    }

    @Test void corruptDataBackedUpAndGlobalStoredSeparately() throws Exception {
        Files.writeString(dir.resolve(owner + ".json"), "{broken");
        try (var store = new SkillStore(dir, SkillLimits.defaults(), s -> s)) {
            assertTrue(store.book(owner).snapshot().isEmpty());
            store.global().create(SkillDraft.simple("Global", "Inspect facts."), InstructionSkill.Source.OWNER, owner, 1, false);
        }
        assertTrue(Files.exists(dir.resolve(owner + ".json.bak")));
        assertTrue(Files.exists(dir.resolve("server.json")));
    }

    @Test void staleEditsCannotOverwriteLatestVersion() {
        var book = new SkillBook(SkillLimits.defaults(), s -> s, InstructionSkill.Scope.COMPANION);
        var skill = book.create(SkillDraft.simple("One", "Inspect facts."), InstructionSkill.Source.OWNER, owner, 1, false);
        book.edit(skill.id(), 1, SkillDraft.simple("Two", "Check results."), 2);
        assertThrows(IllegalArgumentException.class, () -> book.edit(skill.id(), 1, SkillDraft.simple("Stale", "Check facts."), 3));
        assertEquals("Two", book.snapshot().getFirst().name());
    }
}
