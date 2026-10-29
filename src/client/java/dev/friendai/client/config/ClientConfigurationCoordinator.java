package dev.friendai.client.config;

import dev.friendai.infrastructure.config.ClientConfig;
import dev.friendai.infrastructure.config.ConfigStore;
import dev.friendai.infrastructure.config.ServerConfig;
import java.nio.file.Path;
import java.util.Objects;
import java.util.function.Consumer;

/** Owns client/server config persistence and applies validated changes outside the settings widgets. */
public final class ClientConfigurationCoordinator {
    private final ConfigStore<ClientConfig> clientStore;
    private final ConfigStore<ServerConfig> serverStore;
    private final IntegratedServerConfiguration integratedServer;
    private final Consumer<ClientConfig> clientConfigChanged;
    private ClientConfig clientConfig;

    public ClientConfigurationCoordinator(
            Path configDirectory,
            IntegratedServerConfiguration integratedServer,
            Consumer<ClientConfig> clientConfigChanged) {
        Objects.requireNonNull(configDirectory, "configDirectory");
        this.clientStore = new ConfigStore<>(
                configDirectory.resolve("friendai-client.json"),
                ClientConfig.class,
                ClientConfig::defaults,
                ClientConfig.CURRENT_VERSION,
                null);
        this.serverStore = new ConfigStore<>(
                configDirectory.resolve("friendai-server.json"),
                ServerConfig.class,
                ServerConfig::defaults,
                ServerConfig.VERSION,
                ServerConfig::migrate);
        this.integratedServer = Objects.requireNonNull(integratedServer, "integratedServer");
        this.clientConfigChanged = Objects.requireNonNull(clientConfigChanged, "clientConfigChanged");
    }

    /** Loads and caches the client-local configuration. No audio engine or model is initialized here. */
    public synchronized ClientConfig load() {
        if (clientConfig == null) {
            clientConfig = clientStore.load();
            clientConfig.validate();
        }
        return clientConfig;
    }

    /** Loads a fresh server-settings draft for a screen; cancelled widget edits are not cached. */
    public ServerConfig loadServer() {
        ServerConfig config = serverStore.load();
        config.validate();
        return config;
    }

    /** Validates, persists and publishes one client configuration. */
    public void save(ClientConfig config) {
        Objects.requireNonNull(config, "config").validate();
        clientStore.save(config);
        synchronized (this) {
            clientConfig = config;
        }
        clientConfigChanged.accept(config);
    }

    /** Persists server settings and asks the integrated-server adapter to apply them when available. */
    public void saveServer(ServerConfig config) {
        Objects.requireNonNull(config, "config").validate();
        serverStore.save(config);
        applyServer(config);
    }

    public void applyServer(ServerConfig config) {
        integratedServer.apply(Objects.requireNonNull(config, "config"));
    }
}
