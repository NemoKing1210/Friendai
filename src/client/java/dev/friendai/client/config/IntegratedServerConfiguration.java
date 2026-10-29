package dev.friendai.client.config;

import dev.friendai.infrastructure.config.ServerConfig;

/** Client-side port for applying editable settings to an integrated server. */
@FunctionalInterface
public interface IntegratedServerConfiguration {
    void apply(ServerConfig config);
}
