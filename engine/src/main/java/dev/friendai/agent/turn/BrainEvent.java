package dev.friendai.agent.turn;

import dev.friendai.execution.tool.AccessLevel;
import java.util.UUID;

/** Something the companion's brain should react to. Prompt lines are English, LLM-facing. */
public sealed interface BrainEvent {
    /** The line used by the debug log, without a role. */
    String toPromptLine();

    /** The line the model sees in a turn that acts for {@code speaker}. */
    default String toPromptLine(AccessLevel speaker) {
        return toPromptLine();
    }

    /** The suffix an event line gets when the agent layer has facts or a diagnosis to add. */
    private static String withExtra(String extra) {
        return extra == null || extra.isBlank() ? "" : " " + extra;
    }

    enum Source {
        CHAT,
        VOICE
    }

    record SkillOutcome(
            String outcomeId, dev.friendai.foundation.teaching.TeachingContext.OutcomeKind kind, String summary)
            implements BrainEvent {
        public SkillOutcome {
            if (outcomeId == null || outcomeId.length() > 120 || kind == null)
                throw new IllegalArgumentException("Invalid outcome.");
            summary = summary == null ? "" : summary.substring(0, Math.min(summary.length(), 512));
        }

        @Override
        public String toPromptLine() {
            return "[event] completed work outcome: " + kind + ": " + summary
                    + ". If a reusable procedure was learned, manage_skill may save it. Personal facts belong in remember. "
                    + "Do not create another goal or shift just to teach yourself. Otherwise reply [silent].";
        }
    }

    record PlayerSaid(UUID playerUuid, String playerName, String text, Source source) implements BrainEvent {
        @Override
        public String toPromptLine() {
            String who = source == Source.VOICE ? playerName + ", by voice" : playerName;
            return "[" + who + "]: " + text;
        }

        @Override
        public String toPromptLine(AccessLevel speaker) {
            String who = playerName + " (" + speaker.label() + ")" + (source == Source.VOICE ? ", by voice" : "");
            return "[" + who + "]: " + text;
        }
    }

    /** @param extra verified facts and diagnosis appended to the line, or empty */
    record TaskFinished(int taskId, String toolName, boolean success, String summary, String extra)
            implements BrainEvent {
        public TaskFinished(int taskId, String toolName, boolean success, String summary) {
            this(taskId, toolName, success, summary, "");
        }

        @Override
        public String toPromptLine() {
            return "[event] task #" + taskId + " " + toolName + (success ? " finished: " : " failed: ") + summary
                    + withExtra(extra);
        }
    }

    record TaskCancelled(int taskId, String toolName, String reason) implements BrainEvent {
        @Override
        public String toPromptLine() {
            return "[event] task #" + taskId + " " + toolName + " cancelled: " + reason;
        }
    }

    record PlanFinished(long planId, String goal, boolean success, String summary, String extra) implements BrainEvent {
        public PlanFinished(long planId, String goal, boolean success, String summary) {
            this(planId, goal, success, summary, "");
        }

        @Override
        public String toPromptLine() {
            return "[event] plan #" + planId + " " + goal + (success ? " finished: " : " failed: ") + summary
                    + withExtra(extra);
        }
    }

    record PlanBlocked(
            long planId, String goal, long jobId, String toolName, String reason, int remaining, String extra)
            implements BrainEvent {
        public PlanBlocked(long planId, String goal, long jobId, String toolName, String reason, int remaining) {
            this(planId, goal, jobId, toolName, reason, remaining, "");
        }

        @Override
        public String toPromptLine() {
            return "[event] plan #" + planId + " needs a decision at job #" + jobId + " (" + toolName
                    + "): " + reason + "; " + remaining + " step(s) remain. Use queue_edit to repair the plan."
                    + withExtra(extra);
        }
    }

    /** The queue is idle but the goal is not finished: a nudge to continue or to say what blocks it. */
    record GoalTick(String goal, java.util.List<String> unmet) implements BrainEvent {
        public GoalTick {
            unmet = java.util.List.copyOf(unmet);
        }

        @Override
        public String toPromptLine() {
            String state = unmet.isEmpty()
                    ? "nothing is confirmed missing, check whether it is done"
                    : "unmet: " + String.join("; ", unmet);
            return "[event] goal \"" + goal + "\" is not finished; " + state
                    + "; the queue is idle. Continue it or say what blocks you.";
        }
    }

    /** An autonomous companion with nothing to do: it may ask its owner what is next, but must not act on its own. */
    record IdleNudge() implements BrainEvent {
        @Override
        public String toPromptLine() {
            return "[event] you have no goal and nothing queued. If something useful comes to mind, ask your owner "
                    + "one short question about what to do next; do not start any work without an answer, and if "
                    + "there is nothing worth asking reply exactly [silent].";
        }
    }

    /**
     * Free time: the companion may choose its own next activity. The director answers with one
     * {@code choose_activity} call; the planner then turns the choice into a low-priority plan.
     *
     * @param agenda the needs, most urgent first, already rendered as lines
     * @param ladder the development line
     * @param recent the last shifts, newest first
     * @param wish the owner's hint, or empty
     */
    record FreeTimeShift(
            java.util.List<String> agenda,
            String ladder,
            java.util.List<String> recent,
            java.util.List<String> lessons,
            String wish)
            implements BrainEvent {
        public FreeTimeShift {
            agenda = java.util.List.copyOf(agenda);
            recent = java.util.List.copyOf(recent);
            lessons = java.util.List.copyOf(lessons);
            ladder = ladder == null ? "" : ladder;
            wish = wish == null ? "" : wish;
        }

        @Override
        public String toPromptLine() {
            StringBuilder sb = new StringBuilder(
                    "[event] free time: you decide what to do next. Act like a survival "
                            + "player working for the base: keep the companion safe and fed, improve the gear, bring loot home.");
            sb.append("\nAgenda:");
            agenda.forEach(line -> sb.append('\n').append(line));
            if (!ladder.isBlank()) {
                sb.append('\n').append(ladder);
            }
            section(sb, "Recent shifts:", recent);
            section(sb, "Lessons:", lessons);
            if (!wish.isBlank()) {
                sb.append("\nOwner wish: ").append(wish);
            }
            sb.append("\nCall choose_activity exactly once with one concrete activity and the agenda need it serves, "
                    + "or reply exactly [silent] if nothing is worth doing.");
            sb.append("\nFree time happens on its own: the owner is not a destination. Never walk to them, and do "
                    + "not put approaching or following the owner into the plan.");
            return sb.toString();
        }

        private static void section(StringBuilder sb, String title, java.util.List<String> lines) {
            if (!lines.isEmpty()) {
                sb.append('\n').append(title);
                lines.forEach(line -> sb.append("\n- ").append(line));
            }
        }
    }

    /** Something the companion noticed in the world, like a furnace finishing. Belongs to the owner. */
    record WorldNotice(String text) implements BrainEvent {
        @Override
        public String toPromptLine() {
            return "[event] " + text;
        }
    }
}
