package dev.friendai.presentation.config;

import com.google.gson.Gson;
import dev.friendai.infrastructure.config.ClientConfig;
import dev.friendai.infrastructure.config.ServerConfig;
import dev.friendai.infrastructure.config.VoiceDraft;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * One open settings screen: detached client/server/voice drafts plus a generation.
 *
 * <p>Widgets edit the drafts, never the live cached configuration. Rebuilds of the screen keep the same
 * session, so a voice or provider switch keeps unsaved edits in every category. {@link #publish} validates and
 * hands the drafts to the owning configuration adapter exactly once; {@link #close} discards them. Async work
 * captures {@link #generation()} and checks {@link #current(long)} before touching the UI, so a callback from an
 * abandoned screen can neither publish nor navigate.
 */
public final class SettingsSession implements AutoCloseable {
    private static final Gson GSON = new Gson();

    private final long generation;
    private final ClientConfig clientDraft;
    private final ServerConfig serverDraft;
    private final boolean serverReadOnly;
    private VoiceDraft voiceDraft;
    private volatile boolean open = true;

    private SettingsSession(
            long generation, ClientConfig clientDraft, ServerConfig serverDraft, boolean serverReadOnly) {
        this.generation = generation;
        this.clientDraft = clientDraft;
        this.serverDraft = serverDraft;
        this.serverReadOnly = serverReadOnly;
        this.voiceDraft = VoiceDraft.from(clientDraft);
    }

    /**
     * Copies the live client configuration and, when the server settings are editable, the loaded server draft.
     *
     * @param loadedServer null when this connection cannot edit server settings
     * @param serverReadOnly true when an unsupported server file must stay untouched
     */
    public static SettingsSession open(
            long generation, ClientConfig liveClient, ServerConfig loadedServer, boolean serverReadOnly) {
        Objects.requireNonNull(liveClient, "liveClient");
        ClientConfig client = copy(liveClient, ClientConfig.class);
        client.validate();
        ServerConfig server = loadedServer == null || serverReadOnly ? null : copy(loadedServer, ServerConfig.class);
        if (server != null) server.validate();
        return new SettingsSession(generation, client, server, serverReadOnly);
    }

    public long generation() {
        return generation;
    }

    /** True while this session is open and the caller captured this exact generation. */
    public boolean current(long capturedGeneration) {
        return open && generation == capturedGeneration;
    }

    public boolean isOpen() {
        return open;
    }

    public ClientConfig clientDraft() {
        return clientDraft;
    }

    /** The detached server draft, or null when this connection cannot edit server settings. */
    public ServerConfig serverDraft() {
        return serverDraft;
    }

    public boolean serverReadOnly() {
        return serverReadOnly;
    }

    public VoiceDraft voiceDraft() {
        return voiceDraft;
    }

    /** Rebuilds replace the voice draft so unwritten widget state survives the next screen. */
    public void voiceDraft(VoiceDraft next) {
        voiceDraft = Objects.requireNonNull(next, "voiceDraft");
    }

    /**
     * Validates and publishes both drafts once, then closes the session. The voice draft is folded into the
     * client draft first so provider entries match the widgets even when nothing looked changed.
     */
    public void publish(Consumer<ClientConfig> clientPublisher, Consumer<ServerConfig> serverPublisher) {
        Objects.requireNonNull(clientPublisher, "clientPublisher");
        Objects.requireNonNull(serverPublisher, "serverPublisher");
        synchronized (this) {
            if (!open) throw new IllegalStateException("settings session is closed");
            voiceDraft.applyTo(clientDraft);
            clientDraft.validate();
            if (serverDraft != null) {
                serverDraft.validate();
                serverPublisher.accept(serverDraft);
            }
            clientPublisher.accept(clientDraft);
            open = false;
        }
    }

    /** Closes the session without publishing anything; the live and persisted configuration stay untouched. */
    @Override
    public synchronized void close() {
        open = false;
    }

    private static <T> T copy(T value, Class<T> type) {
        return GSON.fromJson(GSON.toJson(value), type);
    }
}
