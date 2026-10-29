package dev.friendai.client.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.foundation.persistence.UnsupportedStoreVersionException;
import dev.friendai.infrastructure.config.ClientConfig;
import dev.friendai.infrastructure.config.ServerConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ClientConfigurationCoordinatorTest {
    @TempDir
    Path configDirectory;

    @Test
    void aSessionPublishesItsDetachedDraftsThroughTheCoordinatorOnce() {
        AtomicInteger applied = new AtomicInteger();
        ClientConfigurationCoordinator coordinator = new ClientConfigurationCoordinator(
                configDirectory, ignored -> applied.incrementAndGet(), ignored -> {});
        var session = dev.friendai.presentation.config.SettingsSession.open(
                1, coordinator.load(), coordinator.loadServer(), false);
        session.serverDraft().companion.name = "Session name";
        session.clientDraft().voice.stt.maxRecordSec = 999;

        assertEquals(30, coordinator.load().voice.stt.maxRecordSec, "the live config is untouched before Save");
        session.publish(coordinator::save, coordinator::saveServer);

        assertEquals(1, applied.get(), "the integrated server is asked to apply exactly once");
        assertEquals(120, coordinator.load().voice.stt.maxRecordSec, "Save validates the client draft");
        assertEquals("Session name", coordinator.loadServer().companion.name, "Save persists the server draft");
    }

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

    @Test
    void unsupportedServerConfigFallsBackWithoutChangingTheFileOrAllowingWrites() throws IOException {
        Path serverFile = configDirectory.resolve("friendai-server.json");
        String original = "{\"version\":15,\"activeProfile\":\"custom\"}";
        Files.writeString(serverFile, original);
        ClientConfigurationCoordinator coordinator =
                new ClientConfigurationCoordinator(configDirectory, ignored -> {}, ignored -> {});

        ServerConfig fallback = coordinator.loadServer();

        assertEquals("main", fallback.activeProfile);
        assertTrue(coordinator.serverConfigReadOnly());
        assertEquals(original, Files.readString(serverFile));
        assertThrows(UnsupportedStoreVersionException.class, () -> coordinator.saveServer(fallback));
        assertEquals(original, Files.readString(serverFile));

        coordinator.save(coordinator.load());
        assertTrue(Files.exists(configDirectory.resolve("friendai-client.json")));
        assertEquals(original, Files.readString(serverFile));
    }
}
