package dev.friendai.client.debug;

import dev.friendai.debug.MovementDebug;
import dev.friendai.protocol.debug.MovementDebugView;
import dev.friendai.transport.protocol.MovementDebugProtocolAdapter;
import java.util.Optional;
import java.util.function.LongSupplier;

/**
 * What the movement overlay knows: whether it is switched on (always off at the start of a connection) and the latest
 * view the server sent. The view and its timestamp live in one immutable snapshot, so a reader never pairs a new view
 * with an old age. The server sends every four ticks; a longer pause than {@link #STALE_AFTER_MS} means it stopped.
 */
public final class MovementDebugState {
    private static final long STALE_AFTER_MS = 5000;

    private static volatile boolean enabled;
    private static volatile Snapshot snapshot;
    private static volatile boolean requestFailed;
    private static volatile LongSupplier clock = System::currentTimeMillis;

    private record Snapshot(MovementDebug view, long receivedAt) {}

    private MovementDebugState() {}

    public static boolean enabled() {
        return enabled;
    }

    public static void setEnabled(boolean on) {
        enabled = on;
        if (!on) {
            snapshot = null;
            requestFailed = false;
        }
    }

    /** The toggle request never left the client, so the switch must not claim the overlay is on. */
    public static void setRequestFailed(boolean failed) {
        requestFailed = failed;
    }

    public static boolean requestFailed() {
        return requestFailed;
    }

    /** Switched on but with no fresh view: the request is in flight, was dropped, or the server has nothing to show. */
    public static boolean awaitingView() {
        return enabled && view().isEmpty();
    }

    /** An empty array means the server has nothing to show; the previous view is dropped. */
    public static void onData(byte[] data) {
        if (data.length == 0) {
            snapshot = null;
            return;
        }
        snapshot = new Snapshot(MovementDebug.decode(data), now());
    }

    /** Accepts a decoded protocol view; companion id zero is the explicit stream-off marker. */
    public static void onView(MovementDebugView protocolView) {
        if (protocolView.companionId() == 0) {
            snapshot = null;
            return;
        }
        snapshot = new Snapshot(MovementDebugProtocolAdapter.fromProtocol(protocolView), now());
    }

    /** The latest view, unless it is switched off or the server stopped sending. */
    public static Optional<MovementDebug> view() {
        Snapshot current = snapshot;
        if (!enabled || current == null || now() - current.receivedAt() > STALE_AFTER_MS) {
            return Optional.empty();
        }
        return Optional.of(current.view());
    }

    public static void reset() {
        enabled = false;
        snapshot = null;
        requestFailed = false;
    }

    /** Test seam: the stale window is measured, not slept through. */
    static void useClockForTests(LongSupplier testClock) {
        clock = testClock == null ? System::currentTimeMillis : testClock;
    }

    private static long now() {
        return clock.getAsLong();
    }
}
