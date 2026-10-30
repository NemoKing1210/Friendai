package dev.friendai.client.config;

import dev.friendai.infrastructure.config.ClientConfig;
import dev.friendai.infrastructure.config.ConfigStore;
import dev.friendai.infrastructure.config.ConfigStores;
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
        this.clientStore = ConfigStores.client(configDirectory.resolve("friendai-client.json"));
        this.serverStore = ConfigStores.server(configDirectory.resolve("friendai-server.json"));
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
        ServerConfig config = serverStore.loadWithDefaultsOnUnsupportedVersion();
        config.validate();
        return config;
    }

    /** True when server settings remain read-only after an unsupported version or failed recovery/upgrade. */
    public boolean serverConfigReadOnly() {
        return serverStore.hasUnsupportedVersion() || serverStore.hasProtectedRecovery();
    }

    /** True after a read, recovery or upgrade failure; the screen must not blame an unsupported version. */
    public boolean serverConfigProtected() {
        return serverStore.hasProtectedRecovery();
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
