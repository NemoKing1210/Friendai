package dev.friendai.features.conversation;

/**
 * Who is meant to receive a companion line. This is intent, not physics: {@link #PUBLIC} chat is visible to
 * everyone, {@link #NEARBY} speech reaches players around the companion, and {@link #PRIVATE} speech reaches only
 * the player the turn answers.
 */
public enum Audience {
    /** Typed chat: the reply is public, exactly like the player's own chat line. */
    PUBLIC,
    /** A line the companion starts, or the spoken form of a public reply: heard around the companion. */
    NEARBY,
    /** Push-to-talk and wake-word speech: the reply belongs to the speaker alone. */
    PRIVATE
}
