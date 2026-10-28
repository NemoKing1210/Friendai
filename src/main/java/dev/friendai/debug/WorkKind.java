package dev.friendai.debug;

/** Category of a task-owned set shown by the movement debug overlay. Keep ordinals stable after encoding. */
public enum WorkKind {
    BLOCKS,
    SECTORS,
    PLOT,
    BUILD
}
