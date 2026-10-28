package dev.friendai.client;

import dev.friendai.FriendaiMod;
import dev.friendai.client.debug.MovementDebugRenderer;
import dev.friendai.client.debug.MovementDebugState;
import dev.friendai.client.render.CompanionRenderer;
import dev.friendai.client.skin.SkinCache;
import dev.friendai.client.tts.SpeechKeybind;
import dev.friendai.client.tts.SpeechRuntime;
import dev.friendai.client.ui.CompanionClientState;
import dev.friendai.client.ui.CompanionInventoryScreen;
import dev.friendai.client.ui.PauseMenuButton;
import dev.friendai.client.voice.PushToTalkHandler;
import dev.friendai.client.voice.VoiceHud;
import dev.friendai.client.voice.VoiceRuntime;
import dev.friendai.entity.FriendaiEntities;
import dev.friendai.features.memory.MemoryViewCodec;
import dev.friendai.features.places.PlaceViewCodec;
import dev.friendai.features.trading.TradeViewCodec;
import dev.friendai.infrastructure.config.ClientConfig;
import dev.friendai.infrastructure.config.ConfigStore;
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
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraft.client.renderer.entity.EntityRenderers;

public class FriendaiClient implements ClientModInitializer {
    private static ConfigStore<ClientConfig> clientConfigStore;
    private static ClientConfig clientConfig;

    public static synchronized ConfigStore<ClientConfig> clientConfigStore() {
        if (clientConfigStore == null) {
            clientConfigStore = new ConfigStore<>(
                    FabricLoader.getInstance().getConfigDir().resolve("friendai-client.json"),
                    ClientConfig.class,
                    ClientConfig::defaults,
                    ClientConfig.CURRENT_VERSION,
                    null);
        }
        return clientConfigStore;
    }

    public static synchronized ClientConfig clientConfig() {
        if (clientConfig == null) {
            clientConfig = clientConfigStore().load();
            clientConfig.validate();
        }
        return clientConfig;
    }

    /** Replaces the in-memory client config (used by the settings screen after saving). */
    public static synchronized void setClientConfig(ClientConfig config) {
        clientConfig = config;
        // Both runtimes rebuild their engines lazily, so a changed provider or key takes effect at once.
        VoiceRuntime.invalidate();
        SpeechRuntime.invalidate();
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
        ClientPlayNetworking.registerGlobalReceiver(
                CompanionSnapshotS2CPayload.TYPE,
                (payload, context) ->
                        context.client().execute(() -> CompanionClientState.onSnapshot(payload.snapshot())));
        ClientPlayNetworking.registerGlobalReceiver(
                PromptS2CPayload.TYPE,
                (payload, context) -> context.client().execute(() -> CompanionClientState.onPrompt(payload.prompt())));
        ClientPlayNetworking.registerGlobalReceiver(
                CompanionSpeechS2CPayload.TYPE,
                (payload, context) -> context.client().execute(() -> SpeechRuntime.onCompanionSpeech(payload)));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> SpeechRuntime.shutdown());
        ClientPlayNetworking.registerGlobalReceiver(
                dev.friendai.net.SkillsViewS2CPayload.TYPE,
                (payload, context) -> context.client()
                        .execute(() -> dev.friendai.features.instructions.SkillViewCodec.decode(payload.data())
                                .ifPresent(CompanionClientState::onSkillsView)));
        ClientPlayNetworking.registerGlobalReceiver(
                MemoryViewS2CPayload.TYPE,
                (payload, context) -> context.client().execute(() -> {
                    try {
                        CompanionClientState.onMemoryView(MemoryViewCodec.decode(payload.data()));
                    } catch (IllegalArgumentException e) {
                        // Keep the last good view: a bad packet must not empty the tab.
                        FriendaiMod.LOGGER.warn("Dropping a malformed memory view: {}", e.getMessage());
                    }
                }));
        ClientPlayNetworking.registerGlobalReceiver(
                PlacesViewS2CPayload.TYPE,
                (payload, context) -> context.client().execute(() -> {
                    try {
                        CompanionClientState.onPlaceView(PlaceViewCodec.decode(payload.data()));
                    } catch (IllegalArgumentException e) {
                        // Keep the last good view: a bad packet must not empty the tab.
                        FriendaiMod.LOGGER.warn("Dropping a malformed places view: {}", e.getMessage());
                    }
                }));
        ClientPlayNetworking.registerGlobalReceiver(
                TradeViewS2CPayload.TYPE,
                (payload, context) -> context.client()
                        .execute(() ->
                                TradeViewCodec.decode(payload.encoded()).ifPresent(CompanionClientState::onTradeView)));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> CompanionClientState.clearTradeView());
        ClientPlayNetworking.registerGlobalReceiver(
                MovementDebugS2CPayload.TYPE,
                (payload, context) -> context.client().execute(() -> {
                    try {
                        MovementDebugState.onData(payload.data());
                    } catch (IllegalArgumentException e) {
                        FriendaiMod.LOGGER.warn("Dropping a malformed movement debug view: {}", e.getMessage());
                    }
                }));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> MovementDebugState.reset());
        ClientPlayNetworking.registerGlobalReceiver(
                SkinS2CPayload.TYPE, (payload, context) -> context.client().execute(() -> SkinCache.onSkin(payload)));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(SkinCache::clear));
        new PushToTalkHandler(VoiceRuntime::stt, text -> {
                    if (ClientPlayNetworking.canSend(VoiceTranscriptC2SPayload.TYPE)) {
                        ClientPlayNetworking.send(new VoiceTranscriptC2SPayload(text));
                    } else {
                        FriendaiMod.LOGGER.warn("Not connected to a server with Friendai, dropping transcript");
                    }
                })
                .register();
    }
}
