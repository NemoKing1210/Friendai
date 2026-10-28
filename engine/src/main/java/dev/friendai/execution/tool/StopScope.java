package dev.friendai.execution.tool;

import java.util.Locale;

/** What a {@code stop} request targets: the running task, everything, or one task by name. */
public sealed interface StopScope permits StopScope.Current, StopScope.All, StopScope.Named {
    /** The task that is running right now. */
    record Current() implements StopScope {}

    /** The running task and everything queued behind it. */
    record All() implements StopScope {}

    /** A task identified by its (lower-case) name. */
    record Named(String name) implements StopScope {}

    /** Parses the model's free-form {@code what} argument; blank or missing means the current task. */
    static StopScope parse(String what) {
        String s = what == null ? "" : what.trim().toLowerCase(Locale.ROOT);
        return switch (s) {
            case "", "current" -> new Current();
            case "all", "everything" -> new All();
            default -> new Named(s);
        };
    }
}
