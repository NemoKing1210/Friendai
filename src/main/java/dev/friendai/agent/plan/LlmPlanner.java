package dev.friendai.agent.plan;

import dev.friendai.brain.LlmGateway;
import dev.friendai.llm.model.LlmResponse;
import dev.friendai.llm.model.Message;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * Asks a model for a plan: one request, and one more with the validation errors if the first reply was unusable.
 * No tools are attached, so the model can only answer with the JSON object.
 */
public final class LlmPlanner implements Planner {
    private final Supplier<LlmGateway> gateway;
    private final UnaryOperator<String> redact;
    private final int timeoutSec;
    private final int maxSteps;
    private final int maxArgs;
    private final int maxTotal;

    public LlmPlanner(
            Supplier<LlmGateway> gateway, UnaryOperator<String> redact, int timeoutSec, int maxSteps, int maxArgs,
            int maxTotal) {
        this.gateway = gateway;
        this.redact = redact;
        this.timeoutSec = timeoutSec;
        this.maxSteps = maxSteps;
        this.maxArgs = maxArgs;
        this.maxTotal = maxTotal;
    }

    @Override
    public CompletableFuture<PlanProposal> propose(PlannerInput input) {
        String system = PlannerPrompt.system(input);
        String user = PlannerPrompt.user(input, redact);
        return attempt(input, system, user, 1);
    }

    private CompletableFuture<PlanProposal> attempt(PlannerInput input, String system, String user, int number) {
        CompletableFuture<LlmResponse> request;
        try {
            request = gateway.get().complete(system, List.of(Message.user(user)), List.of());
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(new PlannerException("planner request failed"));
        }
        return request
                .orTimeout(timeoutSec, TimeUnit.SECONDS)
                .handle((response, error) -> {
                    if (error != null || response == null) {
                        throw new PlannerException("planner request failed");
                    }
                    return response.text();
                })
                .thenCompose(text -> {
                    PlannerParser.Parsed parsed = PlannerParser.parse(
                            text, input.schemas(), input.allTools(), maxSteps, maxArgs, maxTotal);
                    if (parsed.ok()) {
                        return CompletableFuture.completedFuture(parsed.proposal().get());
                    }
                    if (number >= 2) {
                        return CompletableFuture.<PlanProposal>failedFuture(new PlannerException(
                                "the planner's plan was invalid: " + String.join("; ", parsed.errors())));
                    }
                    String retry = user + "\n\nYour previous reply was rejected:\n- "
                            + String.join("\n- ", parsed.errors())
                            + "\nFix every problem and answer again with only the JSON object.";
                    return attempt(input, system, retry, number + 1);
                });
    }
}
