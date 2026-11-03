package dev.friendai.agent.routine;

import dev.friendai.agent.routine.Routine.IntervalTrigger;
import dev.friendai.agent.routine.Routine.PhaseTrigger;
import dev.friendai.agent.routine.Routine.Trigger;

/**
 * Pure game-tick math for routine occurrences. No wall clock and no offline elapsed time: a server that was down
 * simply skips the windows it missed instead of replaying them.
 */
public final class RoutineSchedule {
    private RoutineSchedule() {}

    /** The first occurrence strictly after a creation at {@code nowGameTick}; a phase created in its own window waits a day. */
    public static long nextAfter(Trigger trigger, long nowGameTick, long dayTime) {
        return switch (trigger) {
            case PhaseTrigger phase -> {
                long timeOfDay = Math.floorMod(dayTime, Routine.DAY_TICKS);
                long delta = Math.floorMod(phase.phase().start() - timeOfDay, Routine.DAY_TICKS);
                if (delta == 0) {
                    delta = Routine.DAY_TICKS;
                }
                yield nowGameTick + delta;
            }
            case IntervalTrigger interval -> nowGameTick + period(trigger);
        };
    }

    /** Moves a due tick past {@code now} without ever replaying a missed window or a backward clock jump. */
    public static long advance(Trigger trigger, long nowGameTick, long nextDueTick) {
        long period = period(trigger);
        if (nextDueTick > nowGameTick) {
            return nextDueTick;
        }
        long late = nowGameTick - nextDueTick;
        long steps = late / period + 1;
        return nextDueTick + steps * period;
    }

    public static boolean due(long nowGameTick, long nextDueTick) {
        return nextDueTick > 0 && nowGameTick >= nextDueTick;
    }

    /**
     * Whether the server was late enough that the occurrence should be skipped rather than run: a phase window is
     * still open for six thousand ticks, and an interval for one whole interval.
     */
    public static boolean missed(Trigger trigger, long nowGameTick, long nextDueTick) {
        if (nextDueTick <= 0 || nowGameTick < nextDueTick) {
            return false;
        }
        return nowGameTick - nextDueTick >= window(trigger);
    }

    public static long ticksUntil(long nowGameTick, long nextDueTick) {
        return Math.max(0, nextDueTick - nowGameTick);
    }

    public static long period(Trigger trigger) {
        return switch (trigger) {
            case PhaseTrigger ignored -> Routine.DAY_TICKS;
            case IntervalTrigger interval -> interval.minutes() * Routine.TICKS_PER_MINUTE;
        };
    }

    private static long window(Trigger trigger) {
        return switch (trigger) {
            case PhaseTrigger ignored -> Routine.PHASE_TICKS;
            case IntervalTrigger ignored -> period(trigger);
        };
    }
}
