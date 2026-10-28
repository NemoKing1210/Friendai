package dev.friendai.features.instructions;

import static org.junit.jupiter.api.Assertions.*;

import dev.friendai.execution.tool.*;
import dev.friendai.features.memory.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class BuiltinSkillsTest {
    @Test
    void resourcesLoadAndDisabledGuidanceDisappears() {
        var all = BuiltinSkills.load(getClass().getClassLoader());
        assertEquals(9, all.stream().map(InstructionSkill::id).distinct().count());
        assertTrue(all.stream().allMatch(s -> s.source() == InstructionSkill.Source.BUILTIN && s.enabled()));
        var context = new SkillContext(
                new Caller(UUID.randomUUID(), "Owner", AccessLevel.OWNER),
                "",
                "",
                Set.of(InstructionSkill.Kind.PLANNING),
                false,
                CompanionDescription.EMPTY,
                OwnerProfile.EMPTY);
        var selected = SkillSelector.select(all, context, Set.of(), 24000, 2400);
        assertTrue(selected.skills().stream().anyMatch(s -> s.id().equals("builtin:smart-planning")));
        var filtered = all.stream()
                .filter(s -> !s.id().equals("builtin:smart-planning"))
                .toList();
        assertFalse(SkillSelector.select(filtered, context, Set.of(), 24000, 2400).skills().stream()
                .anyMatch(s -> s.id().equals("builtin:smart-planning")));
    }
}
