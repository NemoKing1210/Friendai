package dev.friendai.brain;

import dev.friendai.skill.SkillPromptSession;
import dev.friendai.tool.Caller;

@FunctionalInterface
public interface SkillSource {
    SkillPromptSession begin(Caller caller, String message, TurnKind turn);

    SkillSource NONE = (caller, message, turn) -> SkillPromptSession.NONE;
}
