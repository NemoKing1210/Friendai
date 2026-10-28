package dev.friendai.features.construction.blueprint;

/** One world cell a clear-area plan wants to empty, with the kind of thing in it. */
public record ClearCell(int x, int y, int z, ClearKind kind) {}
