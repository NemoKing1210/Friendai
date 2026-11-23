package dev.friendai.features.conversation;

/** Maps the input that produced a player line to the channel it asks for. */
public final class AudienceOrigin {
    private AudienceOrigin() {}

    /** Typed chat is public; voice input is private to the player who held push-to-talk. */
    public static Audience of(boolean voice) {
        return voice ? Audience.PRIVATE : Audience.PUBLIC;
    }

    /**
     * Who may read the chat line of a companion message. Chat has no physical attenuation, so only a private turn
     * narrows it; a companion-initiated line stays ordinary, public chat even though its voice is heard nearby.
     */
    public static Audience chatFor(Audience audience) {
        return audience == Audience.PRIVATE ? Audience.PRIVATE : Audience.PUBLIC;
    }

    /**
     * The spoken form of a reply. A public chat answer may be heard by everyone nearby, while a private voice
     * answer is never handed to a client that did not ask for it.
     */
    public static Audience speechFor(Audience audience) {
        return audience == Audience.PRIVATE ? Audience.PRIVATE : Audience.NEARBY;
    }
}
