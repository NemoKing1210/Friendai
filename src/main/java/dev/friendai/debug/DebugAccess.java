package dev.friendai.debug;

/** Who may do what in the companion menu. Everyone controls their own companion; debugging is for operators. */
public final class DebugAccess {
    public enum Action {
        SUMMON,
        TELEPORT,
        DISMISS,
        STOP,
        VIEW_STATUS,
        VIEW_LOG,
        VIEW_PROMPT,
        SEND_MESSAGE,
        RUN_TOOL,
        VIEW_MEMORY,
        EDIT_MEMORY,
        VIEW_MOVEMENT
    }

    private DebugAccess() {}

    public static boolean allowed(Action action, boolean operator) {
        return switch (action) {
            case SUMMON, TELEPORT, DISMISS, STOP, VIEW_STATUS, VIEW_MEMORY, EDIT_MEMORY -> true;
            case VIEW_LOG, VIEW_PROMPT, SEND_MESSAGE, RUN_TOOL, VIEW_MOVEMENT -> operator;
        };
    }
}
