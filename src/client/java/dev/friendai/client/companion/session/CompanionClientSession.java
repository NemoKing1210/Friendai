package dev.friendai.client.companion.session;

import dev.friendai.presentation.companion.ClientSessionId;
import dev.friendai.presentation.companion.CompanionViewState;
import java.util.Objects;

/** One connection's mutable companion views and their rejection boundary for late callbacks. */
public final class CompanionClientSession implements AutoCloseable {
    private final ClientSessionId id;
    private final CompanionViewState viewState;

    public CompanionClientSession() {
        this(ClientSessionId.create());
    }

    public CompanionClientSession(ClientSessionId id) {
        this.id = Objects.requireNonNull(id, "id");
        this.viewState = new CompanionViewState(id);
    }

    public ClientSessionId id() {
        return id;
    }

    public CompanionViewState viewState() {
        return viewState;
    }

    public boolean accepts(ClientSessionId responseSession) {
        return viewState.accepts(responseSession);
    }

    public boolean isClosed() {
        return !viewState.accepts(id);
    }

    @Override
    public void close() {
        viewState.close();
    }
}