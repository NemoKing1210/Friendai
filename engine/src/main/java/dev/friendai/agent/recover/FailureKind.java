package dev.friendai.agent.recover;

public enum FailureKind {
    MISSING_ITEM,
    UNREACHABLE,
    NO_TOOL,
    DANGER,
    RESOURCE_NOT_FOUND,
    /** The world or a protection rule forbids it; only the player can change that. */
    FORBIDDEN,
    UNKNOWN
}
