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

    /** One scheduled occurrence: the game tick it becomes due (display and intervals) and, for phases, its day index. */
    public record Next(long tick, long day) {}

    public static Next nextFor(Trigger trigger, long nowGameTick, long dayTime) {
        return switch (trigger) {
            case PhaseTrigger phase -> nextPhase(phase, nowGameTick, dayTime);
            case IntervalTrigger ignored -> new Next(nowGameTick + period(trigger), 0);
        };
    }

    public static long dayOf(long dayTime) {
        return Math.floorDiv(dayTime, Routine.DAY_TICKS);
    }

    /** The first phase occurrence at or after creation; a phase created inside its own window waits a day. */
    public static Next nextPhase(PhaseTrigger trigger, long nowGameTick, long dayTime) {
        long day = dayOf(dayTime);
        if (Math.floorMod(dayTime, Routine.DAY_TICKS) >= trigger.phase().start()) {
            day++;
        }
        return phaseAt(trigger, nowGameTick, dayTime, day);
    }

    /** The occurrence after both the stored day and today, so a window the server slept through is not replayed. */
    public static Next afterPhase(PhaseTrigger trigger, long nowGameTick, long dayTime, long nextDueDay) {
        return phaseAt(trigger, nowGameTick, dayTime, Math.max(nextDueDay, dayOf(dayTime)) + 1);
    }

    private static Next phaseAt(PhaseTrigger trigger, long nowGameTick, long dayTime, long day) {
        long start = day * Routine.DAY_TICKS + trigger.phase().start();
        return new Next(nowGameTick + Math.max(0L, start - dayTime), day);
    }

    /** Whether the phase window belonging to {@code nextDueDay} is open now. */
    public static boolean phaseDue(PhaseTrigger trigger, long dayTime, long nextDueDay) {
        if (nextDueDay <= 0) {
            return false;
        }
        long timeOfDay = Math.floorMod(dayTime, Routine.DAY_TICKS);
        return dayOf(dayTime) == nextDueDay
                && timeOfDay >= trigger.phase().start()
                && timeOfDay < trigger.phase().start() + Routine.PHASE_TICKS;
    }

    /** Whether the day clock moved past the whole window, so running it now would run the wrong phase. */
    public static boolean phaseMissed(PhaseTrigger trigger, long dayTime, long nextDueDay) {
        if (nextDueDay <= 0) {
            return false;
        }
        long day = dayOf(dayTime);
        if (day != nextDueDay) {
            return day > nextDueDay;
        }
        return Math.floorMod(dayTime, Routine.DAY_TICKS) >= trigger.phase().start() + Routine.PHASE_TICKS;
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
