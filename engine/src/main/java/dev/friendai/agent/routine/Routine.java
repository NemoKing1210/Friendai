package dev.friendai.agent.routine;

import com.google.gson.JsonObject;
import java.util.List;
import java.util.Objects;

/**
 * One owner-defined recurring timetable: a bounded list of existing plannable tools and a game-time trigger.
 *
 * <p>The record is the durable occurrence state as well as the schedule: an interval is due at {@code nextDueTick},
 * a phase at the start of {@code nextDueDay} (a day index on the day clock, which sleep and {@code /time set} move),
 * and {@code lastPlanId}/{@code lastOutcome} join the routine to one ordinary queue plan. The pure type never reads the
 * world, the queue or the owner file; a server adapter fills those in.
 */
public record Routine(
        String name,
        Trigger trigger,
        List<Step> steps,
        int maxRuns,
        int runs,
        boolean enabled,
        long nextDueTick,
        long lastStartedTick,
        long lastPlanId,
        Outcome lastOutcome,
        String lastSummary,
        long nextDueDay) {

    public static final int MAX_NAME = 40;
    public static final int MAX_STEPS = 8;
    public static final int MAX_ARGS_CHARS = 2_000;
    public static final int MAX_STEPS_JSON_CHARS = 8_000;
    public static final int MAX_SUMMARY = 160;
    public static final int MAX_RUNS = 1_000;
    public static final int MIN_INTERVAL_MINUTES = 5;
    public static final int MAX_INTERVAL_MINUTES = 1_440;
    public static final long DAY_TICKS = 24_000L;
    public static final long PHASE_TICKS = 6_000L;
    public static final long TICKS_PER_MINUTE = 1_200L;

    public Routine {
        name = name == null ? "" : name.strip();
        Objects.requireNonNull(trigger, "trigger");
        steps = steps == null ? List.of() : List.copyOf(steps);
        runs = Math.max(0, runs);
        maxRuns = Math.max(0, maxRuns);
        nextDueTick = Math.max(0, nextDueTick);
        lastStartedTick = Math.max(0, lastStartedTick);
        lastPlanId = Math.max(0, lastPlanId);
        lastOutcome = lastOutcome == null ? Outcome.NONE : lastOutcome;
        lastSummary = cut(lastSummary, MAX_SUMMARY);
        nextDueDay = Math.max(0, nextDueDay);
    }

    /** A fresh routine whose first occurrence is the next future trigger boundary. */
    public static Routine create(
            String name, Trigger trigger, List<Step> steps, int maxRuns, long nowGameTick, long dayTime) {
        Routine routine = new Routine(name, trigger, steps, maxRuns, 0, true, 0, 0, 0, Outcome.NONE, "", 0);
        validate(routine);
        return routine.withNextDue(RoutineSchedule.nextFor(trigger, nowGameTick, dayTime));
    }

    /** Rejects a routine that cannot be scheduled or would produce an unbounded plan. */
    public static void validate(Routine routine) {
        Objects.requireNonNull(routine, "routine");
        if (routine.name().isBlank() || routine.name().length() > MAX_NAME) {
            throw new IllegalArgumentException("routine name must be 1 to " + MAX_NAME + " characters");
        }
        if (routine.name().chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("routine name contains a control character");
        }
        if (routine.steps().isEmpty() || routine.steps().size() > MAX_STEPS) {
            throw new IllegalArgumentException("routine must have 1 to " + MAX_STEPS + " steps");
        }
        int chars = 0;
        for (Step step : routine.steps()) {
            if (step == null) {
                throw new IllegalArgumentException("routine has an empty step");
            }
            if (!step.tool().matches("[a-z0-9_]{1,64}")) {
                throw new IllegalArgumentException("routine step has an invalid tool name: " + step.tool());
            }
            int args = step.args().toString().length();
            if (args > MAX_ARGS_CHARS) {
                throw new IllegalArgumentException("routine step arguments are too large: " + step.tool());
            }
            chars += args;
        }
        if (chars > MAX_STEPS_JSON_CHARS) {
            throw new IllegalArgumentException("routine steps are too large");
        }
        switch (routine.trigger()) {
            case PhaseTrigger phase -> Objects.requireNonNull(phase.phase(), "phase");
            case IntervalTrigger interval -> {
                if (interval.minutes() < MIN_INTERVAL_MINUTES || interval.minutes() > MAX_INTERVAL_MINUTES) {
                    throw new IllegalArgumentException("routine interval must be " + MIN_INTERVAL_MINUTES + " to "
                            + MAX_INTERVAL_MINUTES + " minutes");
                }
            }
        }
        if (routine.maxRuns() < 0 || routine.maxRuns() > MAX_RUNS) {
            throw new IllegalArgumentException("routine max runs must be 0 to " + MAX_RUNS);
        }
    }

    public boolean exhausted() {
        return maxRuns > 0 && runs >= maxRuns;
    }

    public Routine withNextDue(long tick, long day) {
        return new Routine(
                name,
                trigger,
                steps,
                maxRuns,
                runs,
                enabled,
                tick,
                lastStartedTick,
                lastPlanId,
                lastOutcome,
                lastSummary,
                day);
    }

    public Routine withNextDue(RoutineSchedule.Next next) {
        return withNextDue(next.tick(), next.day());
    }

    /** The occurrence was accepted: its next due moment advances before the plan is enqueued. */
    public Routine started(long nowGameTick, RoutineSchedule.Next next, long planId) {
        return new Routine(
                name,
                trigger,
                steps,
                maxRuns,
                runs + 1,
                enabled,
                next.tick(),
                nowGameTick,
                planId,
                Outcome.RUNNING,
                "",
                next.day());
    }

    public Routine withPlan(long planId) {
        return new Routine(
                name,
                trigger,
                steps,
                maxRuns,
                runs,
                enabled,
                nextDueTick,
                lastStartedTick,
                planId,
                lastOutcome,
                lastSummary,
                nextDueDay);
    }

    public Routine finished(Outcome outcome, String summary, RoutineSchedule.Next next) {
        return new Routine(
                name,
                trigger,
                steps,
                maxRuns,
                runs,
                enabled,
                next.tick(),
                lastStartedTick,
                0,
                outcome,
                summary,
                next.day());
    }

    public Routine skipped(RoutineSchedule.Next next, String summary) {
        return new Routine(
                name,
                trigger,
                steps,
                maxRuns,
                runs,
                enabled,
                next.tick(),
                lastStartedTick,
                0,
                Outcome.SKIPPED,
                summary,
                next.day());
    }

    public Routine withEnabled(boolean on) {
        return new Routine(
                name,
                trigger,
                steps,
                maxRuns,
                runs,
                on,
                nextDueTick,
                lastStartedTick,
                lastPlanId,
                lastOutcome,
                lastSummary,
                nextDueDay);
    }

    public Routine withOutcome(Outcome outcome, String summary) {
        return new Routine(
                name,
                trigger,
                steps,
                maxRuns,
                runs,
                enabled,
                nextDueTick,
                lastStartedTick,
                lastPlanId,
                outcome,
                summary,
                nextDueDay);
    }

    /**
     * The stable queue goal marker, so a crash between the queue write and the routine write adopts one plan. A phase
     * is identified by the day it belongs to, not by a game tick: the day clock is what sleep and time changes move.
     */
    public String occurrenceKey() {
        return switch (trigger) {
            case PhaseTrigger phase ->
                "routine:" + name + "#phase:" + nextDueDay + ":" + phase.phase().name();
            case IntervalTrigger ignored -> "routine:" + name + "#" + nextDueTick;
        };
    }

    public static String cut(String value, int max) {
        String text = value == null ? "" : value.strip();
        return text.length() <= max ? text : text.substring(0, max);
    }

    /** What starts an occurrence. */
    public sealed interface Trigger permits PhaseTrigger, IntervalTrigger {}

    /** Once per game day at the phase boundary. */
    public record PhaseTrigger(Phase phase) implements Trigger {}

    /** Every {@code minutes} of game time. */
    public record IntervalTrigger(int minutes) implements Trigger {}

    /** Minecraft day-time boundaries: 0 is 06:00, 6000 noon, 12000 18:00, 18000 midnight. */
    public enum Phase {
        MORNING(0),
        DAY(6_000),
        EVENING(12_000),
        NIGHT(18_000);

        private final long start;

        Phase(long start) {
            this.start = start;
        }

        public long start() {
            return start;
        }

        public long end() {
            return (start + PHASE_TICKS) % DAY_TICKS;
        }

        public static Phase at(long dayTime) {
            long time = Math.floorMod(dayTime, DAY_TICKS);
            if (time < DAY.start()) return MORNING;
            if (time < EVENING.start()) return DAY;
            if (time < NIGHT.start()) return EVENING;
            return NIGHT;
        }
    }

    /** One flat queued action. */
    public record Step(String tool, JsonObject args) {
        public Step {
            tool = tool == null ? "" : tool.strip();
            args = args == null ? new JsonObject() : args.deepCopy();
        }
    }

    /** The durable outcome of the last occurrence. */
    public enum Outcome {
        NONE,
        RUNNING,
        SUCCEEDED,
        FAILED,
        INTERRUPTED,
        SKIPPED,
        EXHAUSTED
    }
}
