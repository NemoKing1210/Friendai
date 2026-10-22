package dev.friedai.brain;

/** Something the companion's brain should react to. Prompt lines are English, LLM-facing. */
public sealed interface BrainEvent {
    String toPromptLine();

    enum Source {
        CHAT,
        VOICE
    }

    record PlayerSaid(String playerName, String text, Source source) implements BrainEvent {
        @Override
        public String toPromptLine() {
            String who = source == Source.VOICE ? playerName + ", by voice" : playerName;
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
}
