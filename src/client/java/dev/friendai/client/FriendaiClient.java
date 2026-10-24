package dev.friendai.client;

import dev.friendai.FriendaiMod;
import dev.friendai.client.render.CompanionRenderer;
import dev.friendai.client.ui.CompanionClientState;
import dev.friendai.client.ui.PauseMenuButton;
import dev.friendai.net.CompanionSnapshotS2CPayload;
import dev.friendai.net.PromptS2CPayload;
import dev.friendai.client.voice.PushToTalkHandler;
import dev.friendai.client.voice.VoiceHud;
import dev.friendai.client.voice.VoiceRuntime;
import dev.friendai.config.ClientConfig;
import dev.friendai.config.ConfigStore;
import dev.friendai.entity.FriendaiEntities;
import dev.friendai.memory.MemoryViewCodec;
import dev.friendai.menu.FriendaiMenus;
import dev.friendai.net.MemoryViewS2CPayload;
import dev.friendai.net.VoiceTranscriptC2SPayload;
import dev.friendai.client.ui.CompanionInventoryScreen;
import net.fabricmc.api.ClientModInitializer;
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
                    ClientConfig::migrate);
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
    }

    @Override
    public void onInitializeClient() {
        EntityRenderers.register(FriendaiEntities.COMPANION, CompanionRenderer::new);
        MenuScreens.register(FriendaiMenus.TYPE, CompanionInventoryScreen::new);
        clientConfig();
        VoiceHud.register();
        PauseMenuButton.register();
        ClientPlayNetworking.registerGlobalReceiver(
                CompanionSnapshotS2CPayload.TYPE,
                (payload, context) -> context.client().execute(() -> CompanionClientState.onSnapshot(payload.snapshot())));
        ClientPlayNetworking.registerGlobalReceiver(
                PromptS2CPayload.TYPE,
                (payload, context) -> context.client().execute(() -> CompanionClientState.onPrompt(payload.prompt())));
        ClientPlayNetworking.registerGlobalReceiver(MemoryViewS2CPayload.TYPE, (payload, context) ->
                context.client().execute(() -> {
                    try {
                        CompanionClientState.onMemoryView(MemoryViewCodec.decode(payload.data()));
                    } catch (IllegalArgumentException e) {
                        // Keep the last good view: a bad packet must not empty the tab.
                        FriendaiMod.LOGGER.warn("Dropping a malformed memory view: {}", e.getMessage());
                    }
                }));
        new PushToTalkHandler(VoiceRuntime::stt, text -> {
            if (ClientPlayNetworking.canSend(VoiceTranscriptC2SPayload.TYPE)) {
                ClientPlayNetworking.send(new VoiceTranscriptC2SPayload(text));
            } else {
                FriendaiMod.LOGGER.warn("Not connected to a server with Friendai, dropping transcript");
            }
        }).register();
    }
}
