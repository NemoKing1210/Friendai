package dev.friendai.integration.voicechat;

import de.maxhenkel.voicechat.api.VoicechatClientApi;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.events.ClientSoundEvent;
import de.maxhenkel.voicechat.api.events.ClientVoicechatConnectionEvent;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import dev.friendai.FriendaiMod;
import java.util.function.Consumer;

/**
 * The entry point Simple Voice Chat asks for: it remembers the client API while a voice connection exists, and hands
 * the player's own microphone frames to whoever listens through voice chat. Simple Voice Chat is optional, so nothing
 * else may name this class; it is loaded through the {@code voicechat} entrypoint alone, which only that mod reads.
 * It lives in the common source set because the mod loads every plugin on a dedicated server too, where a client-only
 * class would fail to load; the events it listens for never fire there.
 */
public final class FriendaiVoicechatPlugin implements VoicechatPlugin {
    private static volatile VoicechatClientApi clientApi;
    /** Receives each 48 kHz mono frame the player sends to voice chat; null while nobody listens. */
    private static volatile Consumer<short[]> microphone;

    /** The client API while the voice connection is up, otherwise null. Safe to read from any thread. */
    public static VoicechatClientApi clientApi() {
        return clientApi;
    }

    /**
     * Who hears the player's voice-chat microphone, or null for nobody. The sink runs on voice chat's own audio thread
     * and must copy the frame if it keeps it.
     */
    public static void microphone(Consumer<short[]> sink) {
        microphone = sink;
    }

    @Override
    public String getPluginId() {
        return "friendai";
    }

    @Override
    public void registerEvents(EventRegistration registration) {
        registration.registerEvent(
                ClientVoicechatConnectionEvent.class,
                event -> clientApi = event.isConnected() ? event.getVoicechat() : null);
        // Fired for every frame the player sends, whether by push-to-talk or voice activation; the audio is read only.
        registration.registerEvent(ClientSoundEvent.class, FriendaiVoicechatPlugin::onOwnVoice);
    }

    private static void onOwnVoice(ClientSoundEvent event) {
        Consumer<short[]> sink = microphone;
        if (sink == null) {
            return;
        }
        try {
            sink.accept(event.getRawAudio());
        } catch (RuntimeException failure) {
            // A listener's fault must never break the player's own voice chat.
            FriendaiMod.LOGGER.warn("Listening through voice chat failed for one frame", failure);
        }
    }
}
