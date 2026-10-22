package dev.friedai.debug;

/** Who may do what in the companion menu. Everyone controls their own companion; debugging is for operators. */
public final class DebugAccess {
    public enum Action {
        SUMMON,
        DISMISS,
        STOP,
        VIEW_STATUS,
        VIEW_LOG,
        VIEW_PROMPT,
        SEND_MESSAGE,
        RUN_TOOL
    }

    private DebugAccess() {}

    public static boolean allowed(Action action, boolean operator) {
        return switch (action) {
            case SUMMON, DISMISS, STOP, VIEW_STATUS -> true;
            case VIEW_LOG, VIEW_PROMPT, SEND_MESSAGE, RUN_TOOL -> operator;
        };
    }
}
