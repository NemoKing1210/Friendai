package dev.friendai.foundation.diagnostics;

/** The bounded error part of a log record: type, message, a short stack and the original frame count. */
public record LogError(String type, String message, String stack, int frames) {
    public LogError {
        type = type == null ? "" : type;
        message = message == null ? "" : message;
        stack = stack == null ? "" : stack;
        frames = Math.max(0, frames);
    }
}
