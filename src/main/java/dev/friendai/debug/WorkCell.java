package dev.friendai.debug;

import java.util.Objects;

/** A bounded, renderable cell and the action/status the task currently assigns to it. */
public record WorkCell(int x, int y, int z, DebugAction action, WorkStatus status, String detail) {
    public WorkCell {
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(status, "status");
        detail = detail == null ? "" : detail;
        if (detail.length() > MovementDebug.MAX_DETAIL_CHARS) {
            detail = detail.substring(0, MovementDebug.MAX_DETAIL_CHARS);
        }
    }
}
