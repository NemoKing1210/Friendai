package dev.friendai.agent.autonomy;

/**
 * Free time standing still with nothing running is a bug state, not a rest.
 *
 * <p>The driver ticks once a second and asks this before forcing a decision. Every deliberate state is a reason to
 * wait: a shift in progress, a queued or running job, a director turn that has not answered, a brain still thinking, a
 * planner request in flight, a fight, or a movement order the owner gave. Only a free time that is on and has none of
 * those for the whole window is stalled.
 */
public final class IdleWatchdog {
    /** How long free time may stay that quiet before the driver forces a decision. */
    public static final int DEFAULT_WINDOW_SEC = 120;

    private IdleWatchdog() {}

    /** What the companion is in the middle of; any true value is a reason to wait. */
    public record Busy(boolean shift, boolean anyJob, boolean awaitingChoice, boolean brainThinking, boolean planning,
                       boolean inCombat, boolean movementOrder) {
        public static final Busy NONE = new Busy(false, false, false, false, false, false, false);

        public boolean anything() {
            return shift || anyJob || awaitingChoice || brainThinking || planning || inCombat || movementOrder;
        }
    }

    /**
     * @param freeTimeActive free time is on and enabled on this server; neither a paused server rule nor a passive
     *                       ceiling is a stall
     * @param quietSinceMillis when the companion last had something to do; {@code 0} or less means the driver has not
     *                         seen a quiet tick yet
     */
    public static boolean due(boolean freeTimeActive, Busy busy, long quietSinceMillis, long nowMillis,
                              int windowSec) {
        return freeTimeActive && !busy.anything() && quietSinceMillis > 0
                && nowMillis - quietSinceMillis >= Math.max(1, windowSec) * 1000L;
    }
}
