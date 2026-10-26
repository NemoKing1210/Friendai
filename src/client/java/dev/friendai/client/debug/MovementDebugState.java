package dev.friendai.client.debug;

import dev.friendai.debug.MovementDebug;
import java.util.Optional;

/** What the movement overlay knows: whether it is switched on and the latest view the server sent. */
public final class MovementDebugState {
    private static final long STALE_AFTER_MS = 2000;

    private static volatile boolean enabled;
    private static volatile MovementDebug view;
    private static volatile long receivedAt;

    private MovementDebugState() {}

    public static boolean enabled() {
        return enabled;
    }

    public static void setEnabled(boolean on) {
        enabled = on;
        if (!on) {
            view = null;
        }
    }

    /** An empty array means the server has nothing to show; the previous view is dropped. */
    public static void onData(byte[] data) {
        if (data.length == 0) {
            view = null;
            return;
        }
        view = MovementDebug.decode(data);
        receivedAt = System.currentTimeMillis();
    }

    /** The latest view, unless it is switched off or the server stopped sending. */
    public static Optional<MovementDebug> view() {
        MovementDebug current = view;
        if (!enabled || current == null || System.currentTimeMillis() - receivedAt > STALE_AFTER_MS) {
            return Optional.empty();
        }
        return Optional.of(current);
    }

    public static void reset() {
        enabled = false;
        view = null;
    }
}
