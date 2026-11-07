package dev.friendai.agent.plan;

import dev.friendai.agent.llm.model.LlmException;
import dev.friendai.agent.llm.model.LlmResponse;
import dev.friendai.agent.llm.model.Message;
import dev.friendai.agent.llm.model.StopReason;
import dev.friendai.agent.turn.LlmGateway;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/** Drafts bounded plans in one provider round trip. No tools execute on this worker thread. */
public final class LlmPlanner implements Planner {
    public record Event(int attempt, String reply, PlanFailure failure, String disposition) {}

    /**
     * One second send for a transient provider fault, repeated once. A plan is drafted once per turn and the model
     * can always carry on without it, so a second full round of attempts is not worth the wait.
     */
    private static final long TRANSPORT_RETRY_MILLIS = 250L;

    private final Supplier<LlmGateway> gateway;
    private final UnaryOperator<String> redact;
    private final int timeoutSec, maxSteps, maxArgs, maxTotal, attempts;
    private final Consumer<Event> observer;

    public LlmPlanner(
            Supplier<LlmGateway> gateway,
            UnaryOperator<String> redact,
            int timeoutSec,
            int maxSteps,
            int maxArgs,
            int maxTotal) {
        this(gateway, redact, timeoutSec, maxSteps, maxArgs, maxTotal, 2, event -> {});
    }

    public LlmPlanner(
            Supplier<LlmGateway> gateway,
            UnaryOperator<String> redact,
            int timeoutSec,
            int maxSteps,
            int maxArgs,
            int maxTotal,
            int attempts,
            Consumer<Event> observer) {
        this.gateway = gateway;
        this.redact = redact;
        this.timeoutSec = timeoutSec;
        this.maxSteps = maxSteps;
        this.maxArgs = maxArgs;
        this.maxTotal = maxTotal;
        this.attempts = Math.clamp(attempts, 1, 4);
        this.observer = observer;
    }

    @Override
    public CompletableFuture<PlanProposal> propose(PlannerInput input) {
        return propose(input, () -> false);
    }

    @Override
    public CompletableFuture<PlanProposal> propose(PlannerInput input, BooleanSupplier cancelled) {
        return attempt(
                input,
                redact.apply(PlannerPrompt.system(input)),
                List.of(Message.user(PlannerPrompt.user(input, redact))),
                1,
                1,
                false,
                cancelled);
    }

    private CompletableFuture<PlanProposal> attempt(
            PlannerInput input,
            String system,
            List<Message> messages,
            int number,
            int room,
            boolean transportAlreadyRetried,
            BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean()) return cancelledAt(number);
        CompletableFuture<LlmResponse> request;
        try {
            request = gateway.get().completeJson(system, messages, room);
        } catch (RuntimeException e) {
            return transportFailed(e, input, system, messages, number, room, transportAlreadyRetried, cancelled);
        }
        if (request == null) {
            return transportFailed(
                    new IllegalStateException(),
                    input,
                    system,
                    messages,
                    number,
                    room,
                    transportAlreadyRetried,
                    cancelled);
        }
        return request.orTimeout(timeoutSec, TimeUnit.SECONDS)
                .handle((response, error) -> {
                    if (error != null) {
                        // A timed-out HTTP call is abandoned, not waited on: the retry must not race a live request.
                        if (PlanFailure.unwrap(error) instanceof TimeoutException) request.cancel(true);
                        throw new CompletionException(error);
                    }
                    if (response == null) throw new CompletionException(new IllegalStateException());
                    return response;
                })
                .thenCompose(response -> {
                    PlanFailure failure;
                    PlannerParser.Parsed parsed = null;
                    if (response.stopReason() == StopReason.MAX_TOKENS)
                        failure = new PlanFailure(
                                PlanFailure.Code.TRUNCATED, "reply was cut at the output token limit", number);
                    else {
                        parsed = PlannerParser.parse(
                                response.text(), input.schemas(), input.allTools(), maxSteps, maxArgs, maxTotal);
                        failure = parsed.ok() ? null : parsed.failure().atAttempt(number);
                    }
                    String clean = redact.apply(response.text());
                    if (failure == null) {
                        emit(new Event(number, cap(clean, 8192), null, "validated"));
                        return CompletableFuture.completedFuture(
                                parsed.proposal().orElseThrow());
                    }
                    failure = new PlanFailure(failure.code(), cap(redact.apply(failure.detail()), 2000), number);
                    // A rejected reply is the answer. Repairing it costs a second full provider round trip, and the
                    // model can carry on without a plan; only a transient transport fault is re-sent.
                    emit(new Event(number, cap(clean, 8192), failure, "given up"));
                    return CompletableFuture.failedFuture(new PlannerException(failure));
                })
                .exceptionallyCompose(error -> {
                    Throwable cause = PlanFailure.unwrap(error);
                    // A rejected reply has already spent its attempts; only a transport fault may be re-sent.
                    if (cause instanceof PlannerException planner) {
                        return CompletableFuture.failedFuture(planner);
                    }
                    return transportFailed(
                            cause, input, system, messages, number, room, transportAlreadyRetried, cancelled);
                });
    }

    /**
     * A provider fault is retried once when the kind says a second send can work, the plan has attempts left and
     * nobody has stopped the turn; otherwise it is the answer. The retry repeats the same request, so a transient
     * fault never costs a model a repair turn.
     */
    private CompletableFuture<PlanProposal> transportFailed(
            Throwable error,
            PlannerInput input,
            String system,
            List<Message> messages,
            int number,
            int room,
            boolean transportAlreadyRetried,
            BooleanSupplier cancelled) {
        Throwable cause = PlanFailure.unwrap(error);
        var failure = new PlanFailure(
                PlanFailure.Code.REQUEST_FAILED, cause.getClass().getSimpleName(), number);
        boolean transientFault = cause instanceof LlmException llm && llm.kind().retryable();
        if (!transientFault || transportAlreadyRetried || number >= attempts) {
            emit(new Event(number, "", failure, "given up"));
            return CompletableFuture.failedFuture(new PlannerException(failure));
        }
        if (cancelled.getAsBoolean()) return cancelledAt(number);
        emit(new Event(number, "", failure, "transport-retry"));
        return delayed(Duration.ofMillis(TRANSPORT_RETRY_MILLIS * number))
                .thenCompose(ignored -> attempt(input, system, messages, number + 1, room, true, cancelled));
    }

    private CompletableFuture<PlanProposal> cancelledAt(int number) {
        var failure = new PlanFailure(PlanFailure.Code.CANCELLED, "cancelled", number);
        emit(new Event(number, "", failure, "given up"));
        return CompletableFuture.failedFuture(new PlannerException(failure));
    }

    /** The pause between sends; a timer, never a sleep on the calling thread. */
    private static CompletableFuture<Void> delayed(Duration delay) {
        return CompletableFuture.runAsync(
                () -> {}, CompletableFuture.delayedExecutor(delay.toMillis(), TimeUnit.MILLISECONDS));
    }

    private void emit(Event event) {
        // Diagnostics must never change whether a valid plan can be used.
        try {
            observer.accept(event);
        } catch (RuntimeException ignored) {
        }
    }

    private static String cap(String text, int limit) {
        return text.length() > limit ? text.substring(0, limit) + "…" : text;
    }
}
