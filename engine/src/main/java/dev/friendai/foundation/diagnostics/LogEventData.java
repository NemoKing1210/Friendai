package dev.friendai.foundation.diagnostics;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Builds the {@code data} map of a structured record from its diagnostic context and event-specific facts.
 *
 * <p>Identity keys come from {@link LogContext} only: a fact named {@code trace} or {@code execution} cannot replace
 * or forge them. One key is reserved for the truncation marker, so an event never grows past
 * {@link LogLimits#MAX_DATA_KEYS} keys, and a record that dropped facts says so with
 * {@code truncated = <count>} instead of silently losing them.
 */
public final class LogEventData {
    /** The reserved data key that reports how many facts were dropped or shortened. */
    public static final String TRUNCATED_KEY = "truncated";

    private LogEventData() {}

    public static Map<String, String> build(LogContext context, Map<String, String> facts) {
        LogContext identity = context == null ? LogContext.NONE : context;
        Map<String, String> data = new LinkedHashMap<>(identity.data());
        if (facts == null || facts.isEmpty()) {
            return Collections.unmodifiableMap(data);
        }
        int capacity = LogLimits.MAX_DATA_KEYS - 1;
        int omitted = 0;
        for (Map.Entry<String, String> fact : facts.entrySet()) {
            String key = fact.getKey();
            if (key == null || key.isBlank() || LogLimits.isIdentityKey(key) || TRUNCATED_KEY.equals(key)) {
                continue;
            }
            if (data.size() >= capacity || data.containsKey(key)) {
                omitted++;
                continue;
            }
            data.put(key, fact.getValue() == null ? "" : fact.getValue());
        }
        if (omitted > 0) {
            data.put(TRUNCATED_KEY, Integer.toString(omitted));
        }
        return Collections.unmodifiableMap(data);
    }
}
