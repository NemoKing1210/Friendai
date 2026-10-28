package dev.friendai.skill;

import dev.friendai.memory.*;
import dev.friendai.tool.Caller;
import java.util.Set;

/** Plain per-turn facts. No entity or server object can escape into a provider callback. */
public record SkillContext(
        Caller caller,
        String message,
        String taskId,
        Set<InstructionSkill.Kind> kinds,
        boolean profileIncomplete,
        CompanionDescription description,
        OwnerProfile ownerProfile) {
    public SkillContext {
        kinds = Set.copyOf(kinds);
        message = message == null ? "" : message;
        taskId = taskId == null ? "" : taskId;
    }
}
