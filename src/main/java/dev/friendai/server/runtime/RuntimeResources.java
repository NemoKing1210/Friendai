package dev.friendai.server.runtime;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Owns runtime close actions and releases them in reverse acquisition order. */
public final class RuntimeResources implements AutoCloseable {

    private final Deque<AutoCloseable> closeActions = new ArrayDeque<>();
    private final Consumer<? super Exception> closeFailure;
    private boolean closed;

    public RuntimeResources(Consumer<? super Exception> closeFailure) {
        this.closeFailure = closeFailure == null ? ignored -> {} : closeFailure;
    }

    /** Registers an owned resource for closing and returns it for construction chains. */
    public synchronized <T extends AutoCloseable> T own(T resource) {
        Objects.requireNonNull(resource, "resource");
        if (closed) throw new IllegalStateException("runtime resources are closed");
        closeActions.addFirst(resource);
        return resource;
    }

    /** Registers a close action at the current point in acquisition order. */
    public synchronized void onClose(Runnable action) {
        Objects.requireNonNull(action, "action");
        if (closed) throw new IllegalStateException("runtime resources are closed");
        closeActions.addFirst(() -> action.run());
    }

    /** Returns a borrowed resource without taking responsibility for closing it. */
    public <T> T borrowed(T resource) {
        return Objects.requireNonNull(resource, "resource");
    }

    /** Closes resources acquired by {@code construction} if construction fails, then rethrows the original failure. */
    public <T> T construct(Supplier<? extends T> construction) {
        Objects.requireNonNull(construction, "construction");
        try {
            return construction.get();
        } catch (RuntimeException | Error failure) {
            close();
            throw failure;
        }
    }

    @Override
    public void close() {
        Deque<AutoCloseable> pending;
        synchronized (this) {
            if (closed) return;
            closed = true;
            pending = new ArrayDeque<>(closeActions);
            closeActions.clear();
        }
        while (!pending.isEmpty()) {
            try {
                pending.removeFirst().close();
            } catch (Exception failure) {
                try {
                    closeFailure.accept(failure);
                } catch (RuntimeException ignored) {
                    // A broken reporter must not prevent later resources from closing.
                }
            }
        }
    }
}
