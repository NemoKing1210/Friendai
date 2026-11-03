package dev.friendai.agent.routine;

import dev.friendai.agent.routine.Routine.IntervalTrigger;
import dev.friendai.agent.routine.Routine.Outcome;
import dev.friendai.agent.routine.Routine.PhaseTrigger;

/** Short English technical lines for the model, `list_routines` and the menu. Player labels are translated there. */
public final class RoutineText {
    public static final int MAX_LINE = 240;

    private RoutineText() {}

    public static String describe(Routine routine, long nowGameTick) {
        if (routine == null) {
            return "no routine";
        }
        StringBuilder text = new StringBuilder()
                .append(routine.name())
                .append(" · ")
                .append(trigger(routine.trigger()))
                .append(" · ")
                .append(routine.enabled() ? "on" : "off")
                .append(" · next in ")
                .append(RoutineSchedule.ticksUntil(nowGameTick, routine.nextDueTick()))
                .append(" ticks");
        if (routine.maxRuns() > 0) {
            text.append(" · runs ").append(routine.runs()).append('/').append(routine.maxRuns());
        } else if (routine.runs() > 0) {
            text.append(" · runs ").append(routine.runs());
        }
        if (routine.lastOutcome() != Outcome.NONE) {
            text.append(" · last: ").append(outcome(routine.lastOutcome()));
            if (!routine.lastSummary().isBlank()) {
                text.append(" (").append(routine.lastSummary()).append(')');
            }
        }
        return Routine.cut(text.toString(), MAX_LINE);
    }

    public static String trigger(Routine.Trigger trigger) {
        return switch (trigger) {
            case PhaseTrigger phase -> phase.phase().name().toLowerCase(java.util.Locale.ROOT);
            case IntervalTrigger interval -> "every " + interval.minutes() + " min";
        };
    }

    public static String outcome(Outcome outcome) {
        return outcome.name().toLowerCase(java.util.Locale.ROOT);
    }
}
