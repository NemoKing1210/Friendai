package dev.friendai.debug;

import java.util.UUID;

/** Who may collect a diagnostic bundle: the owner themselves, or an operator for a named owner. */
public final class DiagnoseAccess {
    private DiagnoseAccess() {}

    public static boolean allowed(UUID sender, UUID owner, boolean operator) {
        return sender.equals(owner) || operator;
    }
}
