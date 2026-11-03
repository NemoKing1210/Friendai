package dev.friendai.foundation.diagnostics;

import java.util.List;

/** The hard limits a serialized record respects; the encoder truncates instead of throwing. */
public final class LogLimits {
    public static final int MAX_MESSAGE_CHARS = 400;
    public static final int MAX_DATA_KEYS = 16;
    public static final int MAX_KEY_CHARS = 32;
    public static final int MAX_VALUE_CHARS = 200;
    public static final int MAX_STACK_CHARS = 2048;
    public static final int MAX_STACK_FRAMES = 24;
    public static final int MAX_LINE_BYTES = 8192;
    public static final int MAX_TRACE_CHARS = 32;
    public static final int MAX_SESSION_CHARS = 16;
    public static final int MAX_TOOL_CALL_CHARS = 64;
    public static final int MAX_EVENT_CHARS = 64;
    public static final int MAX_ERROR_TYPE_CHARS = 80;

    /**
     * The diagnostic identity keys in precedence order. They describe which execution a fact belongs to, so a record
     * builder and the pipeline bounds keep them ahead of optional facts, and a producer may not forge them through an
     * ordinary fact map.
     */
    public static final List<String> IDENTITY_KEYS = List.of("trace", "execution", "span", "parentSpan");

    private LogLimits() {}

    /** True for a key that carries diagnostic identity rather than an event-specific fact. */
    public static boolean isIdentityKey(String key) {
        return IDENTITY_KEYS.contains(key);
    }
}
