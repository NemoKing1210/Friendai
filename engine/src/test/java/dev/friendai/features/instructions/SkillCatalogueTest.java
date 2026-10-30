package dev.friendai.features.instructions;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class SkillCatalogueTest {
    private static final String LONG_ID = "owner:" + "a".repeat(80);

    @Test
    void listsMetadataWithoutBodiesAndPagesTheCatalogue() {
        var rows = IntStream.range(0, 13)
                .mapToObj(i -> skill("builtin:s" + (char) ('a' + i), "Name" + i, "Does " + i, true))
                .toList();
        String first = SkillCatalogue.render(rows, Set.of(), 0);
        assertTrue(first.contains("13 total; page 1 of 2"), first);
        assertTrue(first.contains("call list_skills with page 1"), first);
        assertFalse(first.contains("Inspect the world"), "bodies never travel with the catalogue");
        assertEquals(12, first.lines().filter(line -> line.startsWith("- ")).count());

        String second = SkillCatalogue.render(rows, Set.of(), 9);
        assertTrue(second.contains("page 2 of 2"), second);
        assertTrue(second.contains("This is the last page."), second);
        assertTrue(SkillCatalogue.render(rows, Set.of(), -4).contains("page 1 of 2"));
    }

    @Test
    void marksDisabledRecordsAndAnEmptyCatalogueIsExplicit() {
        var rows = List.of(skill("builtin:verification", "Verification", "Check it", true));
        assertTrue(
                SkillCatalogue.render(rows, Set.of("builtin:verification"), 0).contains("| off |"));
        assertTrue(SkillCatalogue.render(rows, Set.of(), 0).contains("| on |"));
        assertEquals(
                "No instruction skills are recorded yet. Use manage_skill after a finished goal or a failed"
                        + " shift, and remember for facts.",
                SkillCatalogue.render(List.of(), Set.of(), 0));
    }

    @Test
    void theWorstLegalPageStaysInsideTheSizeBudget() {
        var rows = IntStream.range(0, 12)
                .mapToObj(i -> new InstructionSkill(
                        i % 2 == 0 ? LONG_ID : "builtin:" + "b".repeat(40),
                        "N".repeat(SkillLimits.NAME),
                        "D".repeat(SkillLimits.DESCRIPTION),
                        "Inspect the world before acting.",
                        true,
                        InstructionSkill.Scope.COMPANION,
                        InstructionSkill.Source.OWNER,
                        null,
                        0,
                        0,
                        1,
                        InstructionSkill.Activation.ACTIVE,
                        false,
                        Set.of(),
                        List.of(),
                        List.of(),
                        false))
                .toList();
        String page = SkillCatalogue.render(rows, Set.of(), 0);
        assertEquals(12, page.lines().filter(line -> line.startsWith("- ")).count());
        assertTrue(page.length() <= SkillCatalogue.MAX_CHARS, "page length " + page.length());
        assertTrue(page.contains("..."), "long fields are cut, not truncated mid-line");
    }

    private static InstructionSkill skill(String id, String name, String description, boolean enabled) {
        return new InstructionSkill(
                id,
                name,
                description,
                "Inspect the world before acting.",
                enabled,
                InstructionSkill.Scope.COMPANION,
                InstructionSkill.Source.BUILTIN,
                null,
                0,
                0,
                1,
                InstructionSkill.Activation.ACTIVE,
                false,
                Set.of(),
                List.of(),
                List.of(),
                false);
    }
}
