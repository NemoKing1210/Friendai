package dev.friedai.client;

import dev.friedai.FriedaiMod;
import dev.friedai.client.render.CompanionRenderer;
import dev.friedai.client.ui.CompanionClientState;
import dev.friedai.client.ui.PauseMenuButton;
import dev.friedai.net.CompanionSnapshotS2CPayload;
import dev.friedai.net.PromptS2CPayload;
import dev.friedai.client.voice.PushToTalkHandler;
import dev.friedai.client.voice.VoiceHud;
import dev.friedai.client.voice.VoiceRuntime;
import dev.friedai.config.ClientConfig;
import dev.friedai.config.ConfigStore;
import dev.friedai.entity.FriedaiEntities;
import dev.friedai.net.VoiceTranscriptC2SPayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.renderer.entity.EntityRenderers;

public class FriedaiClient implements ClientModInitializer {
    private static ConfigStore<ClientConfig> clientConfigStore;
    private static ClientConfig clientConfig;

    public static synchronized ConfigStore<ClientConfig> clientConfigStore() {
        if (clientConfigStore == null) {
            clientConfigStore = new ConfigStore<>(
                    FabricLoader.getInstance().getConfigDir().resolve("friedai-client.json"),
                    ClientConfig.class,
                    ClientConfig::defaults,
                    1,
                    (json, from) -> json);
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
        EntityRenderers.register(FriedaiEntities.COMPANION, CompanionRenderer::new);
        clientConfig();
        VoiceHud.register();
        PauseMenuButton.register();
        ClientPlayNetworking.registerGlobalReceiver(
                CompanionSnapshotS2CPayload.TYPE,
                (payload, context) -> context.client().execute(() -> CompanionClientState.onSnapshot(payload.snapshot())));
        ClientPlayNetworking.registerGlobalReceiver(
                PromptS2CPayload.TYPE,
                (payload, context) -> context.client().execute(() -> CompanionClientState.onPrompt(payload.prompt())));
        new PushToTalkHandler(VoiceRuntime::stt, text -> {
            if (ClientPlayNetworking.canSend(VoiceTranscriptC2SPayload.TYPE)) {
                ClientPlayNetworking.send(new VoiceTranscriptC2SPayload(text));
            } else {
                FriedaiMod.LOGGER.warn("Not connected to a server with Friendai, dropping transcript");
            }
        }).register();
    }
}
