package dev.friendai.client.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.infrastructure.config.ClientConfig;
import dev.friendai.infrastructure.config.ServerConfig;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ClientConfigurationCoordinatorTest {
    @TempDir
    Path configDirectory;

    @Test
    void savesValidatedClientConfigAndPublishesTheSameInstance() {
        AtomicReference<ClientConfig> published = new AtomicReference<>();
        ClientConfigurationCoordinator coordinator =
                new ClientConfigurationCoordinator(configDirectory, ignored -> {}, published::set);

        ClientConfig config = coordinator.load();
        config.voice.stt.maxRecordSec = 999;
        coordinator.save(config);

        assertEquals(120, config.voice.stt.maxRecordSec);
        assertSame(config, published.get());
        assertSame(config, coordinator.load());
        assertEquals(
                120,
                new ClientConfigurationCoordinator(configDirectory, ignored -> {}, ignored -> {})
                        .load()
                        .voice
                        .stt
                        .maxRecordSec);
    }

    @Test
    void savesServerConfigAndDelegatesApplicationWithoutMinecraftOrUiTypes() {
        AtomicReference<ServerConfig> applied = new AtomicReference<>();
        AtomicInteger calls = new AtomicInteger();
        ClientConfigurationCoordinator coordinator = new ClientConfigurationCoordinator(
                configDirectory,
                config -> {
                    applied.set(config);
                    calls.incrementAndGet();
                },
                ignored -> {});
        ServerConfig server = coordinator.loadServer();

        coordinator.saveServer(server);

        assertSame(server, applied.get());
        assertEquals(1, calls.get());
        assertTrue(new ClientConfigurationCoordinator(configDirectory, ignored -> {}, ignored -> {})
                .loadServer()
                .profiles
                .containsKey(server.activeProfile));
    }
}
