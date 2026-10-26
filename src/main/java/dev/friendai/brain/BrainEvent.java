package dev.friendai.brain;

import dev.friendai.tool.AccessLevel;
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

    record PlanFinished(long planId, String goal, boolean success, String summary, String extra)
            implements BrainEvent {
        public PlanFinished(long planId, String goal, boolean success, String summary) {
            this(planId, goal, success, summary, "");
        }

        @Override public String toPromptLine() {
            return "[event] plan #" + planId + " " + goal + (success ? " finished: " : " failed: ") + summary
                    + withExtra(extra);
        }
    }

    record PlanBlocked(long planId, String goal, long jobId, String toolName, String reason, int remaining,
                       String extra) implements BrainEvent {
        public PlanBlocked(long planId, String goal, long jobId, String toolName, String reason, int remaining) {
            this(planId, goal, jobId, toolName, reason, remaining, "");
        }

        @Override public String toPromptLine() {
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

    /** Something the companion noticed in the world, like a furnace finishing. Belongs to the owner. */
    record WorldNotice(String text) implements BrainEvent {
        @Override
        public String toPromptLine() {
            return "[event] " + text;
        }
    }
}
