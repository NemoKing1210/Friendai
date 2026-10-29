package dev.friendai.client;

import dev.friendai.FriendaiMod;
import dev.friendai.client.companion.session.CompanionClientSession;
import dev.friendai.client.debug.MovementDebugRenderer;
import dev.friendai.client.render.CompanionRenderer;
import dev.friendai.client.runtime.ClientRuntime;
import dev.friendai.client.tts.SpeechKeybind;
import dev.friendai.client.tts.SpeechRuntime;
import dev.friendai.client.ui.CompanionInventoryScreen;
import dev.friendai.client.ui.PauseMenuButton;
import dev.friendai.client.voice.PushToTalkHandler;
import dev.friendai.client.voice.VoiceHud;
import dev.friendai.client.voice.VoiceRuntime;
import dev.friendai.entity.FriendaiEntities;
import dev.friendai.features.memory.MemoryViewCodec;
import dev.friendai.features.places.PlaceViewCodec;
import dev.friendai.features.trading.TradeViewCodec;
import dev.friendai.menu.FriendaiMenus;
import dev.friendai.net.CompanionSnapshotS2CPayload;
import dev.friendai.net.CompanionSpeechS2CPayload;
import dev.friendai.net.MemoryViewS2CPayload;
import dev.friendai.net.MovementDebugS2CPayload;
import dev.friendai.net.PlacesViewS2CPayload;
import dev.friendai.net.PromptS2CPayload;
import dev.friendai.net.SkinS2CPayload;
import dev.friendai.net.TradeViewS2CPayload;
import dev.friendai.net.VoiceTranscriptC2SPayload;
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
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> RUNTIME.onConnect());
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> RUNTIME.onDisconnect());
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> RUNTIME.close());
        ClientPlayNetworking.registerGlobalReceiver(CompanionSnapshotS2CPayload.TYPE, (payload, context) -> {
            CompanionClientSession session = RUNTIME.activeCompanionSession();
            if (session == null) return;
            var sessionId = session.id();
            context.client()
                    .execute(() ->
                            session.viewState().onSnapshot(sessionId, payload.snapshot(), System.currentTimeMillis()));
        });
        ClientPlayNetworking.registerGlobalReceiver(PromptS2CPayload.TYPE, (payload, context) -> {
            CompanionClientSession session = RUNTIME.activeCompanionSession();
            if (session == null) return;
            var sessionId = session.id();
            context.client().execute(() -> session.viewState().onPrompt(sessionId, payload.prompt()));
        });
        ClientPlayNetworking.registerGlobalReceiver(CompanionSpeechS2CPayload.TYPE, (payload, context) -> {
            var sessionId = RUNTIME.sessionId();
            if (sessionId == null) return;
            context.client().execute(() -> SpeechRuntime.onCompanionSpeech(sessionId, payload));
        });
        ClientPlayNetworking.registerGlobalReceiver(dev.friendai.net.SkillsViewS2CPayload.TYPE, (payload, context) -> {
            CompanionClientSession session = RUNTIME.activeCompanionSession();
            if (session == null) return;
            var sessionId = session.id();
            context.client()
                    .execute(() -> dev.friendai.features.instructions.SkillViewCodec.decode(payload.data())
                            .ifPresent(view -> session.viewState().onSkillsView(sessionId, view)));
        });
        ClientPlayNetworking.registerGlobalReceiver(MemoryViewS2CPayload.TYPE, (payload, context) -> {
            CompanionClientSession session = RUNTIME.activeCompanionSession();
            if (session == null) return;
            var sessionId = session.id();
            context.client().execute(() -> {
                try {
                    session.viewState().onMemoryView(sessionId, MemoryViewCodec.decode(payload.data()));
                } catch (IllegalArgumentException e) {
                    // Keep the last good view: a bad packet must not empty the tab.
                    FriendaiMod.LOGGER.warn("Dropping a malformed memory view: {}", e.getMessage());
                }
            });
        });
        ClientPlayNetworking.registerGlobalReceiver(PlacesViewS2CPayload.TYPE, (payload, context) -> {
            CompanionClientSession session = RUNTIME.activeCompanionSession();
            if (session == null) return;
            var sessionId = session.id();
            context.client().execute(() -> {
                try {
                    session.viewState().onPlaceView(sessionId, PlaceViewCodec.decode(payload.data()));
                } catch (IllegalArgumentException e) {
                    // Keep the last good view: a bad packet must not empty the tab.
                    FriendaiMod.LOGGER.warn("Dropping a malformed places view: {}", e.getMessage());
                }
            });
        });
        ClientPlayNetworking.registerGlobalReceiver(TradeViewS2CPayload.TYPE, (payload, context) -> {
            CompanionClientSession session = RUNTIME.activeCompanionSession();
            if (session == null) return;
            var sessionId = session.id();
            context.client()
                    .execute(() -> TradeViewCodec.decode(payload.encoded())
                            .ifPresent(view -> session.viewState().onTradeView(sessionId, view)));
        });
        ClientPlayNetworking.registerGlobalReceiver(MovementDebugS2CPayload.TYPE, (payload, context) -> {
            var sessionId = RUNTIME.sessionId();
            if (sessionId == null) return;
            context.client().execute(() -> {
                if (!RUNTIME.accepts(sessionId)) return;
                try {
                    dev.friendai.client.debug.MovementDebugState.onData(payload.data());
                } catch (IllegalArgumentException e) {
                    FriendaiMod.LOGGER.warn("Dropping a malformed movement debug view: {}", e.getMessage());
                }
            });
        });
        ClientPlayNetworking.registerGlobalReceiver(SkinS2CPayload.TYPE, (payload, context) -> {
            var sessionId = RUNTIME.sessionId();
            if (sessionId == null) return;
            context.client().execute(() -> {
                if (RUNTIME.accepts(sessionId)) dev.friendai.client.skin.SkinCache.onSkin(payload);
            });
        });
        PushToTalkHandler pushToTalk = new PushToTalkHandler(VoiceRuntime::stt, RUNTIME::sessionId, text -> {
            if (ClientPlayNetworking.canSend(VoiceTranscriptC2SPayload.TYPE)) {
                ClientPlayNetworking.send(new VoiceTranscriptC2SPayload(text));
            } else {
                FriendaiMod.LOGGER.warn("Not connected to a server with Friendai, dropping transcript");
            }
        });
        RUNTIME.registerPushToTalk(pushToTalk);
        pushToTalk.register();
    }
}
