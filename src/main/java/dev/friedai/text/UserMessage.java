package dev.friedai.text;

import java.util.List;

/** A player-facing message: a translation key plus arguments (String, number or nested UserMessage). */
public record UserMessage(String key, List<Object> args) {
    public static UserMessage of(String key, Object... args) {
        return new UserMessage(key, List.of(args));
    }

    /** For logs and tests only. */
    @Override
    public String toString() {
        return key + args;
    }
}
