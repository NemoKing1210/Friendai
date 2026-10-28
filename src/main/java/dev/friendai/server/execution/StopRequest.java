package dev.friendai.server.execution;

/** Describes why the current task is being stopped. */
public record StopRequest(String reason) {
    public StopRequest {
        if (reason == null || reason.isBlank()) throw new IllegalArgumentException("stop reason is required");
    }
}
