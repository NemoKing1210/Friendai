package dev.friendai.client.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.foundation.persistence.ProtectedConfigException;
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
    void olderServerSettingsAreUpgradedAndEditableWithoutLosingTheOwnersProfile() throws IOException {
        Path file = configDirectory.resolve("friendai-server.json");
        Files.writeString(file, """
                {"version":14,"activeProfile":"custom","profiles":{"custom":{
                "provider":"openai","model":"custom-model","apiKey":"test-secret"}},
                "chat":{"listenAll":true},"tasks":{"defaultFollow":false}}
                """);
        ClientConfigurationCoordinator coordinator =
                new ClientConfigurationCoordinator(configDirectory, ignored -> {}, ignored -> {});

        ServerConfig loaded = coordinator.loadServer();

        assertEquals("custom", loaded.activeProfile);
        assertEquals("test-secret", loaded.profiles.get("custom").apiKey);
        assertTrue(loaded.chat.listenAll);
        assertFalse(loaded.tasks.defaultFollow);
        assertFalse(coordinator.serverConfigReadOnly());
        coordinator.saveServer(loaded);
        assertEquals("custom", coordinator.loadServer().activeProfile);
    }

    @Test
    void aSessionPublishesItsDetachedDraftsThroughTheCoordinatorOnce() {
        AtomicInteger applied = new AtomicInteger();
        ClientConfigurationCoordinator coordinator = new ClientConfigurationCoordinator(
                configDirectory, ignored -> applied.incrementAndGet(), ignored -> {});
        var session = dev.friendai.infrastructure.config.SettingsSession.open(
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
    void protectedServerConfigIsReportedReadOnlyAndRefusesSaves() throws IOException {
        Path serverFile = configDirectory.resolve("friendai-server.json");
        String broken = "{oops";
        Files.writeString(serverFile, broken);
        Files.createDirectory(configDirectory.resolve("friendai-server.json.bak"));
        ClientConfigurationCoordinator coordinator =
                new ClientConfigurationCoordinator(configDirectory, ignored -> {}, ignored -> {});

        ServerConfig fallback = coordinator.loadServer();

        assertEquals("main", fallback.activeProfile);
        assertTrue(coordinator.serverConfigReadOnly(), "an unbacked corrupt file must stay read-only");
        assertTrue(coordinator.serverConfigProtected(), "the screen must explain the protection, not a version");
        assertThrows(ProtectedConfigException.class, () -> coordinator.saveServer(fallback));
        assertEquals(broken, Files.readString(serverFile), "the damaged original must survive");
    }

    @Test
    void unsupportedServerConfigFallsBackWithoutChangingTheFileOrAllowingWrites() throws IOException {
        Path serverFile = configDirectory.resolve("friendai-server.json");
        String original = "{\"version\":" + (ServerConfig.VERSION + 1) + ",\"activeProfile\":\"custom\"}";
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
