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
        VIEW_MOVEMENT,
        RENAME,
        SET_AUTO_RESPAWN,
        VIEW_PLACES,
        EDIT_PLACES,
        SET_CREATIVE_MODE,
        SET_FREE_TIME
    }

    private DebugAccess() {}

    public static boolean allowed(Action action, boolean operator) {
        return switch (action) {
            case SUMMON, TELEPORT, DISMISS, STOP, VIEW_STATUS, VIEW_MEMORY, EDIT_MEMORY, RENAME, SET_AUTO_RESPAWN,
                    VIEW_PLACES, EDIT_PLACES, SET_CREATIVE_MODE, SET_FREE_TIME -> true;
            case VIEW_LOG, VIEW_PROMPT, SEND_MESSAGE, RUN_TOOL, VIEW_MOVEMENT -> operator;
        };
    }
}
