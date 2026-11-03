package dev.friendai.agent.llm;

import java.util.function.Consumer;

/**
 * The telemetry scope of one logical gateway invocation: a stable request id, the turn and generation that started
 * it, and a sink for that request's attempts. A callback can never change retries, timeouts, parsing or the provider
 * result: exceptions from it are swallowed here.
 */
public record RequestTelemetry(long requestId, long turn, int generation, Consumer<RequestAttempt> observer)
        implements Consumer<RequestAttempt> {
    public static final RequestTelemetry NONE = new RequestTelemetry(0L, 0L, 0, attempt -> {});

    public RequestTelemetry {
        observer = observer == null ? attempt -> {} : observer;
    }

    @Override
    public void accept(RequestAttempt attempt) {
        if (attempt == null) return;
        try {
            observer.accept(attempt);
        } catch (RuntimeException ignored) {
            // A diagnostic callback must never alter the request it observes.
        }
    }
}
