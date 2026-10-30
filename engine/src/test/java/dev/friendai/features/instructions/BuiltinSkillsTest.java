package dev.friendai.features.instructions;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;

class BuiltinSkillsTest {
    @Test
    void loadsExactlyThePackagedIndexWithBoundedBodies() throws IOException {
        var loader = getClass().getClassLoader();
        var all = BuiltinSkills.load(loader);
        var index = readIndex(loader);
        assertEquals(index.size(), all.size());
        assertEquals(indexIds(index), all.stream().map(InstructionSkill::id).toList());
        assertTrue(
                all.stream()
                        .allMatch(s -> s.source() == InstructionSkill.Source.BUILTIN
                                && s.enabled()
                                && s.activation() == InstructionSkill.Activation.ACTIVE
                                && s.version() == 1
                                && !s.name().isBlank()
                                && !s.description().isBlank()
                                && !s.body().isBlank()
                                && s.body().length() <= SkillLimits.BODY),
                "every packaged member is an active, bounded builtin");
    }

    @Test
    void titlesFollowOneDerivedConvention() {
        for (var skill : BuiltinSkills.load(getClass().getClassLoader())) {
            String slug = skill.id().substring("builtin:".length()).replace('-', '_');
            assertEquals(
                    "friendai.skill." + slug, BuiltinSkills.titleKey(skill.id()).orElseThrow());
        }
        assertTrue(BuiltinSkills.titleKey("owner:mine").isEmpty());
        assertTrue(BuiltinSkills.titleKey("builtin:Bad Id").isEmpty());
        assertTrue(BuiltinSkills.titleKey(null).isEmpty());
    }

    @Test
    void refusesAnOversizedCatalogue() {
        var resources = catalogue(ids(BuiltinSkills.MAX_BUILTINS + 1));
        assertThrows(IllegalStateException.class, () -> BuiltinSkills.load(loader(resources)));
    }

    @Test
    void refusesDuplicateIds() {
        var resources = catalogue(List.of("one", "one"));
        assertThrows(IllegalStateException.class, () -> BuiltinSkills.load(loader(resources)));
    }

    @Test
    void refusesAMemberWithoutABodyOrMetadata() {
        var resources = catalogue(List.of("ghost"));
        resources.remove("assets/friendai/skills/ghost.txt");
        assertThrows(IllegalStateException.class, () -> BuiltinSkills.load(loader(resources)));

        var nameless = catalogue(List.of("blank"));
        nameless.put(
                "assets/friendai/skills/index.json",
                "[{\"id\":\"blank\",\"name\":\" \",\"description\":\"Procedure blank\"}]");
        assertThrows(IllegalStateException.class, () -> BuiltinSkills.load(loader(nameless)));
    }

    @Test
    void acceptsTheBoundItselfAndLoadsIdsFromTheIndex() {
        var bound = catalogue(ids(BuiltinSkills.MAX_BUILTINS));
        var loaded = BuiltinSkills.load(loader(bound));
        assertEquals(BuiltinSkills.MAX_BUILTINS, loaded.size());
        assertEquals("builtin:aa", loaded.getFirst().id());
        assertEquals("builtin:cl", loaded.getLast().id());
    }

    private static JsonArray readIndex(ClassLoader loader) throws IOException {
        try (InputStream in = loader.getResourceAsStream("assets/friendai/skills/index.json")) {
            assertNotNull(in, "the packaged index must exist");
            return JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8))
                    .getAsJsonArray();
        }
    }

    private static List<String> indexIds(JsonArray index) {
        List<String> ids = new ArrayList<>();
        index.forEach(
                row -> ids.add("builtin:" + row.getAsJsonObject().get("id").getAsString()));
        return ids;
    }

    /** Two-letter ids keep every generated member inside the packaged id rule. */
    private static List<String> ids(int count) {
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            ids.add("" + (char) ('a' + i / 26) + (char) ('a' + i % 26));
        }
        return ids;
    }

    private static Map<String, String> catalogue(List<String> ids) {
        JsonArray index = new JsonArray();
        Map<String, String> resources = new HashMap<>();
        for (String id : ids) {
            JsonObject row = new JsonObject();
            row.addProperty("id", id);
            row.addProperty("name", "Name " + id);
            row.addProperty("description", "Procedure " + id);
            index.add(row);
            resources.put("assets/friendai/skills/" + id + ".txt", "Inspect the world before acting on " + id);
        }
        resources.put("assets/friendai/skills/index.json", index.toString());
        return resources;
    }

    private static ClassLoader loader(Map<String, String> resources) {
        return new ClassLoader(null) {
            @Override
            public InputStream getResourceAsStream(String name) {
                String text = resources.get(name);
                return text == null ? null : new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
            }
        };
    }
}
