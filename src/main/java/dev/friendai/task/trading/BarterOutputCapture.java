package dev.friendai.task.trading;

import java.util.UUID;

/** Thread-local marker active only during vanilla's exact adult piglin barter-response emission. */
public final class BarterOutputCapture {
    public record Context(UUID attempt, UUID piglin) {}

    private static final ThreadLocal<Context> CURRENT = new ThreadLocal<>();

    private BarterOutputCapture() {}

    public static void begin(UUID attempt, UUID piglin) {
        CURRENT.set(new Context(attempt, piglin));
    }

    public static void end() {
        CURRENT.remove();
    }

    public static Context current() {
        return CURRENT.get();
    }
}
