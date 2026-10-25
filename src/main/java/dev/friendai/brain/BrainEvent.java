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

    record TaskFinished(int taskId, String toolName, boolean success, String summary) implements BrainEvent {
        @Override
        public String toPromptLine() {
            return "[event] task #" + taskId + " " + toolName + (success ? " finished: " : " failed: ") + summary;
        }
    }

    record TaskCancelled(int taskId, String toolName, String reason) implements BrainEvent {
        @Override
        public String toPromptLine() {
            return "[event] task #" + taskId + " " + toolName + " cancelled: " + reason;
        }
    }

    record PlanFinished(long planId, String goal, boolean success, String summary) implements BrainEvent {
        @Override public String toPromptLine() {
            return "[event] plan #" + planId + " " + goal + (success ? " finished: " : " failed: ") + summary;
        }
    }

    record PlanBlocked(long planId, String goal, long jobId, String toolName, String reason, int remaining)
            implements BrainEvent {
        @Override public String toPromptLine() {
            return "[event] plan #" + planId + " needs a decision at job #" + jobId + " (" + toolName
                    + "): " + reason + "; " + remaining + " step(s) remain. Use queue_edit to repair the plan.";
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
