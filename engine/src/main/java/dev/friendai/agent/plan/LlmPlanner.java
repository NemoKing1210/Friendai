package dev.friendai.agent.plan;

import dev.friendai.agent.turn.LlmGateway;
import dev.friendai.agent.llm.model.LlmResponse;
import dev.friendai.agent.llm.model.Message;
import dev.friendai.agent.llm.model.StopReason;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/** Drafts bounded plans with evidence-based repairs. No tools execute on this worker thread. */
public final class LlmPlanner implements Planner {
    public record Event(int attempt, String reply, PlanFailure failure, String disposition) {}

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
        return attempt(
                input,
                redact.apply(PlannerPrompt.system(input)),
                List.of(Message.user(PlannerPrompt.user(input, redact))),
                1,
                1);
    }

    private CompletableFuture<PlanProposal> attempt(
            PlannerInput input, String system, List<Message> messages, int number, int room) {
        CompletableFuture<LlmResponse> request;
        try {
            request = gateway.get().completeJson(system, messages, room);
        } catch (RuntimeException e) {
            return requestFailed(e, number);
        }
        if (request == null) return requestFailed(new IllegalStateException(), number);
        return request.orTimeout(timeoutSec, TimeUnit.SECONDS)
                .handle((response, error) -> {
                    if (error != null) throw requestException(error, number);
                    if (response == null) throw requestException(new IllegalStateException(), number);
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
                    boolean retry = number < attempts;
                    emit(new Event(number, cap(clean, 8192), failure, retry ? "retry" : "given up"));
                    if (!retry) return CompletableFuture.failedFuture(new PlannerException(failure));
                    String repair = "Attempt " + number + " rejected: " + failure.code() + ": " + failure.detail()
                            + "\nRepair the rejected reply and return only the complete JSON object.";
                    if (failure.code() == PlanFailure.Code.TOO_LARGE)
                        repair += " Produce a shorter plan within all queue limits.";
                    if (failure.code() == PlanFailure.Code.TRUNCATED)
                        repair += " More output room is available; keep the plan concise and finish the object.";
                    List<Message> next = new ArrayList<>(messages);
                    next.add(Message.assistant(cap(clean, 8192), List.of()));
                    next.add(Message.user(redact.apply(repair)));
                    return attempt(
                            input,
                            system,
                            List.copyOf(next),
                            number + 1,
                            failure.code() == PlanFailure.Code.TRUNCATED ? Math.min(8, room * 2) : room);
                });
    }

    private CompletableFuture<PlanProposal> requestFailed(Throwable error, int number) {
        return CompletableFuture.failedFuture(requestException(error, number));
    }

    private PlannerException requestException(Throwable error, int number) {
        var failure = new PlanFailure(
                PlanFailure.Code.REQUEST_FAILED,
                PlanFailure.unwrap(error).getClass().getSimpleName(),
                number);
        emit(new Event(number, "", failure, "given up"));
        return new PlannerException(failure);
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
