package dev.friendai.features.speech.tts;

import java.util.List;

/**
 * Whether the companion's voice uses the optional Simple Voice Chat mod. {@code auto}, the default, uses it whenever
 * it is installed, connected and can carry the reply, both for speech output and as the microphone the wake word
 * listens to; {@code builtin} never touches it. The built-in path always works and is the fallback of the other.
 */
public final class PlaybackBackends {
    /** Voice chat when it can be used, the built-in path otherwise: the default. */
    public static final String AUTO = "auto";
    /** The mod's own audio line and microphone only. */
    public static final String BUILTIN = "builtin";
    /**
     * The voice-chat backend {@link #choose} names. As a setting it is the earlier spelling of {@link #AUTO}: choosing
     * voice chat already meant "use it when it can", so a saved {@code voicechat} keeps that meaning.
     */
    public static final String VOICECHAT = "voicechat";

    private static final List<String> IDS = List.of(AUTO, BUILTIN);

    private PlaybackBackends() {}

    /** The settings the screen offers, default first. */
    public static List<String> ids() {
        return IDS;
    }

    /** The configured setting; a missing or unknown value is the default, the earlier {@code voicechat} is auto. */
    public static String normalize(String configured) {
        return BUILTIN.equals(configured) ? BUILTIN : AUTO;
    }

    /** True when the value is a known spelling, so validation warns only about values it had to replace. */
    public static boolean known(String configured) {
        return AUTO.equals(configured) || BUILTIN.equals(configured) || VOICECHAT.equals(configured);
    }

    /** Whether the setting lets the companion use voice chat at all, for output and for the microphone. */
    public static boolean voiceChatAllowed(String configured) {
        return AUTO.equals(normalize(configured));
    }

    /**
     * The backend for one reply. Voice chat needs the setting to allow it, the mod connected and a reply that comes
     * from a companion's position; anything else, including the settings preview, stays on the built-in path.
     */
    public static String choose(String configured, boolean voiceChatReady, boolean positional) {
        return voiceChatAllowed(configured) && voiceChatReady && positional ? VOICECHAT : BUILTIN;
    }
}
