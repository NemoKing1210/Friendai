package dev.friendai.client;

import dev.friendai.client.companion.session.CompanionClientSession;
import dev.friendai.client.debug.MovementDebugRenderer;
import dev.friendai.client.net.ClientProtocolNetworking;
import dev.friendai.client.render.CompanionRenderer;
import dev.friendai.client.runtime.ClientRuntime;
import dev.friendai.client.tts.SpeechKeybind;
import dev.friendai.client.tts.SpeechRuntime;
import dev.friendai.client.ui.CompanionInventoryScreen;
import dev.friendai.client.ui.CompanionRequests;
import dev.friendai.client.ui.PauseMenuButton;
import dev.friendai.client.voice.PushToTalkHandler;
import dev.friendai.client.voice.VoiceHud;
import dev.friendai.client.voice.VoiceRuntime;
import dev.friendai.entity.FriendaiEntities;
import dev.friendai.menu.FriendaiMenus;
import dev.friendai.protocol.speech.SpeechRequest;
import dev.friendai.transport.payload.ProtocolAcceptC2SPayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraft.client.renderer.entity.EntityRenderers;

public class FriendaiClient implements ClientModInitializer {
    private static final ClientRuntime RUNTIME = new ClientRuntime();

    public static ClientRuntime runtime() {
        return RUNTIME;
    }

    public static dev.friendai.infrastructure.config.ClientConfig clientConfig() {
        return RUNTIME.configuration().load();
    }

    /** Returns the active menu session, creating one for an already-open integrated-world UI if necessary. */
    public static CompanionClientSession companionSession() {
        return RUNTIME.companionSession();
    }

    @Override
    public void onInitializeClient() {
        EntityRenderers.register(FriendaiEntities.COMPANION, CompanionRenderer::new);
        MenuScreens.register(FriendaiMenus.TYPE, CompanionInventoryScreen::new);
        clientConfig();
        VoiceHud.register();
        MovementDebugRenderer.register();
        SpeechKeybind.register();
        ClientTickEvents.END_CLIENT_TICK.register(client -> SpeechRuntime.tick());
        PauseMenuButton.register();
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) ->
                RUNTIME.onConnect(ClientPlayNetworking.canSend(ProtocolAcceptC2SPayload.TYPE)));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> RUNTIME.onDisconnect());
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> RUNTIME.close());
        ClientProtocolNetworking.register(RUNTIME);

        PushToTalkHandler pushToTalk = new PushToTalkHandler(
                VoiceRuntime::stt,
                RUNTIME::sessionId,
                (sessionId, text) -> CompanionRequests.send(sessionId, new SpeechRequest.Transcript(text)));
        RUNTIME.registerPushToTalk(pushToTalk);
        pushToTalk.register();
    }
}
