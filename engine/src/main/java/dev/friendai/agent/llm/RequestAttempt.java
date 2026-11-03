package dev.friendai.agent.llm;

/**
 * One measured HTTP attempt of a logical request. It carries only safe metadata: an attempt number, a stage, a status
 * code, an error kind token, durations and a payload size — never a body, a header or a URL.
 */
public record RequestAttempt(
        int attempt, String stage, int status, String errorKind, long durationMs, long backoffMs, long payloadBytes) {
    public static final String START = "start";
    public static final String FINISH = "finish";
    public static final String RETRY = "retry";
    public static final String FIRST_DELTA = "first_delta";

    public RequestAttempt {
        stage = stage == null || stage.isBlank() ? START : stage;
        errorKind = errorKind == null ? "" : errorKind;
        durationMs = Math.max(-1L, durationMs);
        backoffMs = Math.max(0L, backoffMs);
        payloadBytes = Math.max(0L, payloadBytes);
    }

    public boolean retry() {
        return RETRY.equals(stage);
    }
}
