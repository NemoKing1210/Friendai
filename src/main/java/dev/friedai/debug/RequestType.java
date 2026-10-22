package dev.friedai.debug;

import java.util.Optional;

/** What a menu client asks of the server. {@link #INVALID} stands for anything the server does not know. */
public enum RequestType {
    SUBSCRIBE(DebugAccess.Action.VIEW_STATUS),
    UNSUBSCRIBE(DebugAccess.Action.VIEW_STATUS),
    SUMMON(DebugAccess.Action.SUMMON),
    DISMISS(DebugAccess.Action.DISMISS),
    STOP(DebugAccess.Action.STOP),
    REQUEST_PROMPT(DebugAccess.Action.VIEW_PROMPT),
    SEND_MESSAGE(DebugAccess.Action.SEND_MESSAGE),
    RUN_TOOL(DebugAccess.Action.RUN_TOOL),
    INVALID(null);

    private final DebugAccess.Action action;

    RequestType(DebugAccess.Action action) {
        this.action = action;
    }

    public Optional<DebugAccess.Action> action() {
        return Optional.ofNullable(action);
    }

    public static RequestType fromOrdinal(int ordinal) {
        RequestType[] all = values();
        return ordinal >= 0 && ordinal < all.length ? all[ordinal] : INVALID;
    }
}
