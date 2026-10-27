package dev.friendai.agent.route;

/** What a turn is for; it decides which tool groups the model sees and whether the planner is consulted. */
public enum TurnMode {
    /** A question or small talk: no world actions needed. */
    CHAT,
    /** One concrete request. */
    ACTION,
    /** A multi-part objective with no goal yet. */
    GOAL,
    /** A plan failed or needs a decision. */
    RECOVERY,
    /** Background events with no player message. */
    EVENT
}
