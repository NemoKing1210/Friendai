package dev.friendai.skill;

import java.util.*;

/** Editable fields only; source and authority cannot be submitted in a draft. */
public record SkillDraft(
        String name,
        String description,
        String body,
        boolean enabled,
        boolean alwaysOn,
        Set<InstructionSkill.Kind> turnKinds,
        List<String> triggers,
        List<String> tags,
        boolean includeProfile) {
    public SkillDraft {
        turnKinds = turnKinds == null ? Set.of() : Set.copyOf(turnKinds);
        triggers = triggers == null ? List.of() : List.copyOf(triggers);
        tags = tags == null ? List.of() : List.copyOf(tags);
    }

    public static SkillDraft simple(String name, String body) {
        return new SkillDraft(
                name, "", body, true, false, Set.of(InstructionSkill.Kind.CHAT), List.of(), List.of(), false);
    }
}
