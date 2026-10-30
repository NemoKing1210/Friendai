package dev.friendai.agent.turn;

import dev.friendai.execution.tool.Caller;

@FunctionalInterface
public interface SkillSource {
    SkillTurnSession begin(Caller caller, String message, TurnKind turn);

    SkillSource NONE = (caller, message, turn) -> SkillTurnSession.NONE;
}
