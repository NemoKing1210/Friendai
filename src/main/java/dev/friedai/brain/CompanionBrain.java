package dev.friedai.brain;

import dev.friedai.llm.model.LlmResponse;
import dev.friedai.llm.model.Message;
import dev.friedai.llm.model.ToolCall;
import dev.friedai.tool.ToolExecutor;
import dev.friedai.tool.ToolResult;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The agent loop: events in, speech and tool calls out. At most one LLM request is in flight.
 * Every method must be called on the scheduler's thread; LLM callbacks hop back through it.
 */
public final class CompanionBrain {
    private static final Logger LOGGER = LoggerFactory.getLogger("friedai/brain");

    private final Supplier<Persona> persona;
    private final Supplier<BrainSettings> settings;
    private final Supplier<LlmGateway> gateway;
    private final ToolExecutor tools;
    private final PerceptionSource perception;
    private final Speaker speaker;
    private final BrainScheduler scheduler;
    private final ConversationMemory memory;
    private BrainObserver observer = BrainObserver.NONE;

    private final Deque<BrainEvent> queue = new ArrayDeque<>();
    private boolean thinking;
    private boolean cooldownScheduled;
    private boolean hasFinishedTurn;
    private long lastTurnEnd;
    private int generation;
    private int step;

    public CompanionBrain(
            Supplier<Persona> persona,
            Supplier<BrainSettings> settings,
            Supplier<LlmGateway> gateway,
            ToolExecutor tools,
            PerceptionSource perception,
            Speaker speaker,
            BrainScheduler scheduler) {
        this.persona = persona;
        this.settings = settings;
        this.gateway = gateway;
        this.tools = tools;
        this.perception = perception;
        this.speaker = speaker;
        this.scheduler = scheduler;
        this.memory = new ConversationMemory(settings.get().historyMessages());
    }

    public void post(BrainEvent e) {
        queue.addLast(e);
        maybeStartTurn();
    }

    /** Drops the in-flight request (its response will be ignored) and all queued events. */
    public void cancel() {
        generation++;
        queue.clear();
        if (thinking) {
            thinking = false;
            speaker.thinking(false);
        }
    }

    public boolean isThinking() {
        return thinking;
    }

    public void setObserver(BrainObserver observer) {
        this.observer = java.util.Objects.requireNonNull(observer);
    }

    /** The system prompt the next request would carry. */
    public String currentSystemPrompt() {
        return PromptBuilder.system(persona.get(), perception.describe());
    }

    private void observe(java.util.function.Consumer<BrainObserver> call) {
        try {
            call.accept(observer);
        } catch (RuntimeException e) {
            LOGGER.warn("Brain observer failed", e);
        }
    }

    private void maybeStartTurn() {
        if (thinking || queue.isEmpty()) {
            return;
        }
        long wait = hasFinishedTurn ? settings.get().cooldownMs() - (scheduler.nowMillis() - lastTurnEnd) : 0;
        if (wait > 0) {
            if (!cooldownScheduled) {
                cooldownScheduled = true;
                scheduler.schedule(() -> {
                    cooldownScheduled = false;
                    maybeStartTurn();
                }, wait);
            }
            return;
        }
        thinking = true;
        speaker.thinking(true);
        String lines = queue.stream().map(BrainEvent::toPromptLine).collect(Collectors.joining("\n"));
        for (BrainEvent event : queue) {
            observe(o -> o.onEvent(event));
        }
        queue.clear();
        memory.add(Message.user(lines));
        step = 0;
        requestStep(generation);
    }

    private void requestStep(int gen) {
        if (gen != generation) {
            return;
        }
        if (step >= settings.get().maxToolSteps()) {
            endTurn();
            return;
        }
        step++;
        String system = PromptBuilder.system(persona.get(), perception.describe());
        CompletableFuture<LlmResponse> future;
        try {
            future = gateway.get().complete(system, memory.snapshot(), tools.specs());
        } catch (RuntimeException e) {
            future = CompletableFuture.failedFuture(e);
        }
        future.whenComplete((response, error) -> scheduler.execute(() -> onResponse(gen, response, error)));
    }

    private void onResponse(int gen, LlmResponse response, Throwable error) {
        if (gen != generation) {
            return;
        }
        if (error != null) {
            LOGGER.warn("LLM request failed: {}", error.toString());
            observe(o -> o.onError(error));
            speaker.error(ErrorMessages.forUser(error));
            endTurn();
            return;
        }
        if (!response.text().isBlank()) {
            observe(o -> o.onAssistantText(response.text()));
            speaker.say(response.text());
        }
        List<ToolCall> calls = response.toolCalls();
        if (!response.text().isBlank() || !calls.isEmpty()) {
            memory.add(Message.assistant(response.text(), calls));
        }
        if (calls.isEmpty()) {
            endTurn();
            return;
        }
        for (ToolCall call : calls) {
            ToolResult result;
            observe(o -> o.onToolCall(call));
            try {
                result = tools.execute(call);
            } catch (RuntimeException e) {
                LOGGER.warn("Tool {} threw", call.name(), e);
                result = ToolResult.error("Tool " + call.name() + " failed: " + e.getMessage());
            }
            ToolResult finalResult = result;
            observe(o -> o.onToolResult(call, finalResult));
            memory.add(Message.toolResult(call.id(), call.name(), result.content()));
        }
        requestStep(gen);
    }

    private void endTurn() {
        thinking = false;
        hasFinishedTurn = true;
        lastTurnEnd = scheduler.nowMillis();
        speaker.thinking(false);
        maybeStartTurn();
    }
}
