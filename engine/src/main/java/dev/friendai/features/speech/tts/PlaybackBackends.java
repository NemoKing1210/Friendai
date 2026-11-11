package dev.friendai.features.speech.tts;

import java.util.List;

/**
 * Which playback path speaks a reply. The built-in path always works; any other one is a preference that falls
 * back to it whenever it cannot carry the reply.
 */
public final class PlaybackBackends {
    /** The mod's own audio line: the default, and the fallback of every other backend. */
    public static final String BUILTIN = "builtin";
    /** An entity channel of the optional Simple Voice Chat mod, so positional-audio mods can shape the voice. */
    public static final String VOICECHAT = "voicechat";

    private static final List<String> IDS = List.of(BUILTIN, VOICECHAT);

    private PlaybackBackends() {}

    public static List<String> ids() {
        return IDS;
    }

    /** The configured id, or the built-in one for a missing or unknown value. */
    public static String normalize(String configured) {
        return configured != null && IDS.contains(configured) ? configured : BUILTIN;
    }

    /**
     * The backend for one reply. Voice chat needs the mod connected and a reply that comes from a companion's
     * position; anything else, including the settings preview, stays on the built-in path.
     */
    public static String choose(String configured, boolean voiceChatReady, boolean positional) {
        return VOICECHAT.equals(normalize(configured)) && voiceChatReady && positional ? VOICECHAT : BUILTIN;
    }
}
