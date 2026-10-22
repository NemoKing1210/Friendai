package dev.friedai.client;

import dev.friedai.FriedaiMod;
import dev.friedai.client.render.CompanionRenderer;
import dev.friedai.config.ClientConfig;
import dev.friedai.config.ConfigStore;
import dev.friedai.entity.FriedaiEntities;
import net.fabricmc.api.ClientModInitializer;
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
        FriedaiMod.LOGGER.debug("Friedai client initialized");
    }
}
