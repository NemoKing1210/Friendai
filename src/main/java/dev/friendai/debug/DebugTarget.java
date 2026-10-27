package dev.friendai.debug;

import java.util.Objects;

/** A block and the current action the companion intends to perform there. */
public record DebugTarget(int x, int y, int z, DebugAction action, String detail) {
    public DebugTarget {
        Objects.requireNonNull(action, "action");
        detail = detail == null ? "" : detail;
        if (detail.length() > MovementDebug.MAX_DETAIL_CHARS) {
            detail = detail.substring(0, MovementDebug.MAX_DETAIL_CHARS);
        }
    }
}
