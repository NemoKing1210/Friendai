package dev.friendai.client.debug;

import dev.friendai.debug.MovementDebug;
import dev.friendai.protocol.debug.MovementDebugView;
import dev.friendai.transport.protocol.MovementDebugProtocolAdapter;
import java.util.Optional;

/** What the movement overlay knows: whether it is switched on and the latest view the server sent. */
public final class MovementDebugState {
    private static final long STALE_AFTER_MS = 2000;

    private static volatile boolean enabled;
    private static volatile boolean preferenceChecked;
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

    public static boolean preferenceRestorePending() {
        return !preferenceChecked;
    }

    public static void restorePreference(boolean on) {
        preferenceChecked = true;
        setEnabled(on);
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

    /** Accepts a decoded protocol view; companion id zero is the explicit stream-off marker. */
    public static void onView(MovementDebugView protocolView) {
        if (protocolView.companionId() == 0) {
            view = null;
            return;
        }
        view = MovementDebugProtocolAdapter.fromProtocol(protocolView);
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
        preferenceChecked = false;
        view = null;
    }
}
