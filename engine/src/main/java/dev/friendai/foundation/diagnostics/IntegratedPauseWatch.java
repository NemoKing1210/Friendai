package dev.friendai.foundation.diagnostics;

/**
 * How much wall time the client's pause menu has kept the integrated server from ticking. The client tick records
 * the menu state; the server runner reads the total when it attributes a clock gap. A dedicated server never writes
 * it, and only a singleplayer pause sets the client flag, so a remote game and a dedicated server both read zero.
 *
 * <p>The state is process-wide because one client owns at most one integrated server. An interval that has not been
 * observed ending yet still counts up to the moment it is read: the server resumes and asks before the client's next
 * tick can close the interval it just left.
 */
public final class IntegratedPauseWatch {
    private static final Object LOCK = new Object();
    private static long closedMillis;
    private static long openSince = -1L;

    private IntegratedPauseWatch() {}

    /** One client tick: whether the pause menu is open at {@code wallMillis}. */
    public static void observe(boolean paused, long wallMillis) {
        synchronized (LOCK) {
            if (paused) {
                if (openSince < 0) openSince = wallMillis;
            } else if (openSince >= 0) {
                closedMillis += Math.max(0L, wallMillis - openSince);
                openSince = -1L;
            }
        }
    }

    /** Total paused wall time up to {@code wallMillis}, including a pause that has not been seen closing yet. */
    public static long pausedMillisAt(long wallMillis) {
        synchronized (LOCK) {
            return closedMillis + (openSince < 0 ? 0L : Math.max(0L, wallMillis - openSince));
        }
    }

    /** Clears the process-wide state between tests. */
    public static void resetForTests() {
        synchronized (LOCK) {
            closedMillis = 0L;
            openSince = -1L;
        }
    }
}
