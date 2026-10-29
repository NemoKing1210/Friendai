package dev.friendai.client.runtime;

import dev.friendai.FriendaiMod;
import dev.friendai.client.companion.session.CompanionClientSession;
import dev.friendai.client.config.ClientConfigurationCoordinator;
import dev.friendai.client.debug.MovementDebugState;
import dev.friendai.client.skin.SkinCache;
import dev.friendai.client.tts.SpeechRuntime;
import dev.friendai.client.voice.PushToTalkHandler;
import dev.friendai.client.voice.VoiceRuntime;
import dev.friendai.infrastructure.config.ServerConfig;
import dev.friendai.presentation.companion.ClientSessionId;
import dev.friendai.server.runtime.ServerRuntimeRegistry;
import java.nio.file.Path;
import java.util.Objects;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.server.MinecraftServer;

/** Owns client connection state, application resources and configuration side effects. */
public final class ClientRuntime implements AutoCloseable {
    private final ClientLifecycle lifecycle = new ClientLifecycle(
            failure -> FriendaiMod.LOGGER.warn("Could not close a client runtime resource", failure));
    private final ClientConfigurationCoordinator configuration;
    private CompanionClientSession companionSession;
    private PushToTalkHandler pushToTalk;

    public ClientRuntime() {
        Path configDirectory = FabricLoader.getInstance().getConfigDir();
        configuration = new ClientConfigurationCoordinator(
                configDirectory, this::applyIntegratedServer, this::onClientConfigChanged);
        // Downloads and reusable workers belong to this client process, not to one server connection.
        lifecycle.ownApplication(VoiceRuntime::close);
        lifecycle.ownApplication(SpeechRuntime::close);
    }

    public ClientConfigurationCoordinator configuration() {
        return configuration;
    }

    public ClientSessionId sessionId() {
        synchronized (this) {
            return companionSession == null || companionSession.isClosed() ? null : companionSession.id();
        }
    }

    public boolean accepts(ClientSessionId sessionId) {
        return lifecycle.accepts(sessionId);
    }

    public CompanionClientSession companionSession() {
        synchronized (this) {
            if (companionSession != null && !companionSession.isClosed()) {
                return companionSession;
            }
        }
        onConnect();
        synchronized (this) {
            return companionSession;
        }
    }

    public synchronized CompanionClientSession activeCompanionSession() {
        return companionSession == null || companionSession.isClosed() ? null : companionSession;
    }

    public ClientSessionId onConnect() {
        ClientSessionId id = lifecycle.onConnect();
        CompanionClientSession next = new CompanionClientSession(id);
        synchronized (this) {
            companionSession = next;
        }
        VoiceRuntime.onConnect(id);
        SpeechRuntime.onConnect(id);
        lifecycle.ownSession(id, () -> closeCompanionSession(id, next));
        lifecycle.ownSession(id, () -> VoiceRuntime.onDisconnect(id));
        lifecycle.ownSession(id, () -> SpeechRuntime.onDisconnect(id));
        lifecycle.ownSession(id, MovementDebugState::reset);
        lifecycle.ownSession(id, SkinCache::clear);
        PushToTalkHandler handler;
        synchronized (this) {
            handler = pushToTalk;
        }
        if (handler != null) {
            lifecycle.ownSession(id, () -> handler.onDisconnect(id));
        }
        return id;
    }

    public void onDisconnect() {
        ClientSessionId current = sessionId();
        if (current != null) {
            lifecycle.onDisconnect(current);
        }
    }

    public void registerPushToTalk(PushToTalkHandler handler) {
        Objects.requireNonNull(handler, "handler");
        ClientSessionId current;
        synchronized (this) {
            pushToTalk = handler;
            current = companionSession == null ? null : companionSession.id();
        }
        if (current != null) {
            lifecycle.ownSession(current, () -> handler.onDisconnect(current));
        }
    }

    @Override
    public void close() {
        lifecycle.close();
    }

    private void onClientConfigChanged(dev.friendai.infrastructure.config.ClientConfig ignored) {
        VoiceRuntime.invalidate();
        SpeechRuntime.invalidate();
    }

    private void applyIntegratedServer(ServerConfig config) {
        MinecraftServer integrated = Minecraft.getInstance().getSingleplayerServer();
        if (integrated != null) {
            integrated.execute(
                    () -> ServerRuntimeRegistry.find(integrated).ifPresent(runtime -> runtime.applyConfig(config)));
        }
    }

    private void closeCompanionSession(ClientSessionId id, CompanionClientSession expected) {
        expected.close();
        synchronized (this) {
            if (companionSession == expected && companionSession.id().equals(id)) {
                companionSession = null;
            }
        }
    }
}
