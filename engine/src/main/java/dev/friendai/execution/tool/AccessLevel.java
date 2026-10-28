package dev.friendai.execution.tool;

import java.util.Locale;

/** What a player may do with a companion, ordered by rights. */
public enum AccessLevel {
    GUEST,
    TRUSTED,
    OWNER;

    /** Whether a caller at this level satisfies a tool that requires {@code required}. */
    public boolean allows(AccessLevel required) {
        return compareTo(required) >= 0;
    }

    /** Lower-case name, used in prompts and tool errors. */
    public String label() {
        return name().toLowerCase(Locale.ROOT);
    }
}
