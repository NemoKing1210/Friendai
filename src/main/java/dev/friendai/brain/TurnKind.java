package dev.friendai.brain;

/** Whether a turn answers a player or reacts to background events. */
public enum TurnKind {
    /** The batch contains at least one player message. */
    PLAYER,
    /** The batch contains only task events and world notices. */
    EVENT
}
