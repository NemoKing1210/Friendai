package dev.friendai.integration.voicechat;

import de.maxhenkel.voicechat.api.VoicechatClientApi;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.events.ClientVoicechatConnectionEvent;
import de.maxhenkel.voicechat.api.events.EventRegistration;

/**
 * The entry point Simple Voice Chat asks for: it only remembers the client API while a voice connection exists.
 * Simple Voice Chat is optional, so nothing else may name this class; it is loaded through the {@code voicechat}
 * entrypoint alone, which only that mod reads. It lives in the common source set because the mod loads every
 * plugin on a dedicated server too, where a client-only class would fail to load; the event it listens for never
 * fires there.
 */
public final class FriendaiVoicechatPlugin implements VoicechatPlugin {
    private static volatile VoicechatClientApi clientApi;

    /** The client API while the voice connection is up, otherwise null. Safe to read from any thread. */
    public static VoicechatClientApi clientApi() {
        return clientApi;
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
    }
}
