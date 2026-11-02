package dev.friendai.features.social;

/**
 * One subject the companion may raise: the line it would say, and the stable identity used to avoid repeating it.
 *
 * <p>The identity is separate from the text because a grounded subject comes from a record that can be edited or
 * forgotten: {@code fact:7} stays the same record while its wording changes, so the ring never re-raises an edited
 * fact as if it were new. The static bank uses its own text as the identity, which keeps old text-keyed saves valid.
 */
public record TopicCandidate(String id, String text) {
    public TopicCandidate {
        id = id == null ? "" : id.strip();
        text = text == null ? "" : text.strip();
    }

    /** A static bank opener, identified by its own text. */
    public static TopicCandidate ofText(String text) {
        return new TopicCandidate(text, text);
    }
}
