package dev.friendai.structure;

/** The finishing action that makes a built structure come alive or activate. */
public sealed interface Trigger {
    /** The body is built first, then this block is placed last (the pumpkin on a golem). */
    record LastBlock(String state, Offset at) implements Trigger {}

    /** Use {@code item} on the block at {@code at} (flint and steel on a portal frame). */
    record Ignite(String item, Offset at) implements Trigger {}
}
