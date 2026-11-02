package dev.friendai.foundation.diagnostics;

/** The hard limits a serialized record respects; the encoder truncates instead of throwing. */
public final class LogLimits {
    public static final int MAX_MESSAGE_CHARS = 400;
    public static final int MAX_DATA_KEYS = 16;
    public static final int MAX_KEY_CHARS = 32;
    public static final int MAX_VALUE_CHARS = 200;
    public static final int MAX_STACK_CHARS = 2048;
    public static final int MAX_STACK_FRAMES = 24;
    public static final int MAX_LINE_BYTES = 8192;

    private LogLimits() {}
}
