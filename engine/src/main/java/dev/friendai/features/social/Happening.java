package dev.friendai.features.social;

/** One observed world fact worth mentioning, already stripped of private chat by its producer. */
public record Happening(Kind kind, Importance importance, int tick, String key, String text) {
    public enum Kind {
        ADVANCEMENT,
        DEATH,
        RESPAWN,
        DIMENSION,
        JOIN,
        LEAVE,
        TASK_FINISHED,
        PLAN_BLOCKED,
        TRUST_REQUEST
    }

    public enum Importance {
        LOW,
        NORMAL,
        HIGH
    }
}
