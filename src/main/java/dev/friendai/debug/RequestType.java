package dev.friendai.debug;

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
    MEMORY_OPEN(DebugAccess.Action.VIEW_MEMORY),
    MEMORY_CLOSE(DebugAccess.Action.VIEW_MEMORY),
    MEMORY_ADD(DebugAccess.Action.EDIT_MEMORY),
    MEMORY_EDIT(DebugAccess.Action.EDIT_MEMORY),
    MEMORY_FORGET(DebugAccess.Action.EDIT_MEMORY),
    MEMORY_CLEAR(DebugAccess.Action.EDIT_MEMORY),
    MEMORY_TRUST(DebugAccess.Action.EDIT_MEMORY),
    TELEPORT(DebugAccess.Action.TELEPORT),
    MOVE_DEBUG_ON(DebugAccess.Action.VIEW_MOVEMENT),
    MOVE_DEBUG_OFF(DebugAccess.Action.VIEW_MOVEMENT),
    RENAME(DebugAccess.Action.RENAME),
    PERSONA_SET(DebugAccess.Action.EDIT_MEMORY),
    SET_AUTO_RESPAWN(DebugAccess.Action.SET_AUTO_RESPAWN),
    PLACES_OPEN(DebugAccess.Action.VIEW_PLACES),
    PLACES_CLOSE(DebugAccess.Action.VIEW_PLACES),
    PLACES_RENAME(DebugAccess.Action.EDIT_PLACES),
    PLACES_FORGET(DebugAccess.Action.EDIT_PLACES),
    INVALID(null),
    SET_CREATIVE_MODE(DebugAccess.Action.SET_CREATIVE_MODE),
    FREE_TIME_TOGGLE(DebugAccess.Action.SET_FREE_TIME);

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
