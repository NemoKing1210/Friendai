package dev.friendai.client.runtime;

import dev.friendai.presentation.companion.ClientSessionId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

/** Owns connection-scoped resources separately from resources that live for the whole client process. */
public final class ClientLifecycle implements AutoCloseable {
    private final Consumer<Throwable> closeFailure;
    private final List<AutoCloseable> applicationResources = new ArrayList<>();
    private final Set<AutoCloseable> applicationResourceIdentities = Collections.newSetFromMap(new IdentityHashMap<>());

    private ConnectionScope connection;
    private boolean closed;

    public ClientLifecycle() {
        this(ignored -> {});
    }

    public ClientLifecycle(Consumer<Throwable> closeFailure) {
        this.closeFailure = Objects.requireNonNull(closeFailure, "closeFailure");
    }

    /** Starts a connection and closes any previous connection's resources before returning. */
    public ClientSessionId onConnect() {
        ConnectionScope previous;
        ConnectionScope next;
        synchronized (this) {
            if (closed) {
                throw new IllegalStateException("client lifecycle is closed");
            }
            previous = connection;
            next = new ConnectionScope(ClientSessionId.create());
            connection = next;
        }
        closeScope(previous);
        return next.id;
    }

    /** Closes only the matching connection. A stale disconnect cannot close a newer session. */
    public void onDisconnect(ClientSessionId sessionId) {
        ConnectionScope ending;
        synchronized (this) {
            if (connection == null || !connection.id.equals(sessionId)) {
                return;
            }
            ending = connection;
            connection = null;
        }
        closeScope(ending);
    }

    public synchronized boolean accepts(ClientSessionId sessionId) {
        return !closed && connection != null && connection.id.equals(sessionId);
    }

    /** Takes ownership of a resource for one connection, or closes it immediately if that session is stale. */
    public void ownSession(ClientSessionId sessionId, AutoCloseable resource) {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(resource, "resource");
        boolean closeNow;
        synchronized (this) {
            closeNow = closed || connection == null || !connection.id.equals(sessionId);
            if (!closeNow) {
                connection.own(resource);
            }
        }
        if (closeNow) {
            closeResource(resource);
        }
    }

    /** Takes ownership of an application-wide resource, such as a model download, until client shutdown. */
    public void ownApplication(AutoCloseable resource) {
        Objects.requireNonNull(resource, "resource");
        boolean closeNow;
        synchronized (this) {
            closeNow = closed;
            if (!closeNow && applicationResourceIdentities.add(resource)) {
                applicationResources.add(resource);
            }
        }
        if (closeNow) {
            closeResource(resource);
        }
    }

    @Override
    public void close() {
        ConnectionScope ending;
        List<AutoCloseable> application;
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
            ending = connection;
            connection = null;
            application = new ArrayList<>(applicationResources);
            applicationResources.clear();
            applicationResourceIdentities.clear();
        }
        closeScope(ending);
        closeInReverse(application);
    }

    private void closeScope(ConnectionScope scope) {
        if (scope != null) {
            closeInReverse(scope.resources);
        }
    }

    private void closeInReverse(List<AutoCloseable> resources) {
        for (int i = resources.size() - 1; i >= 0; i--) {
            closeResource(resources.get(i));
        }
    }

    private void closeResource(AutoCloseable resource) {
        try {
            resource.close();
        } catch (Exception | LinkageError failure) {
            try {
                closeFailure.accept(failure);
            } catch (RuntimeException ignored) {
                // A reporting failure must not prevent the remaining resources from closing.
            }
        }
    }

    private static final class ConnectionScope {
        private final ClientSessionId id;
        private final List<AutoCloseable> resources = new ArrayList<>();
        private final Set<AutoCloseable> identities = Collections.newSetFromMap(new IdentityHashMap<>());

        private ConnectionScope(ClientSessionId id) {
            this.id = id;
        }

        private void own(AutoCloseable resource) {
            if (identities.add(resource)) {
                resources.add(resource);
            }
        }
    }
}
