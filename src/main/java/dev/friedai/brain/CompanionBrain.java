package dev.friedai.brain;

import dev.friedai.llm.model.LlmResponse;
import dev.friedai.llm.model.Message;
import dev.friedai.llm.model.ToolCall;
import dev.friedai.tool.AccessLevel;
import dev.friedai.tool.Caller;
import dev.friedai.tool.ToolExecutor;
import dev.friedai.tool.ToolResult;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The agent loop: events in, speech and tool calls out. At most one LLM request is in flight, and one turn
 * acts for exactly one principal. Every method must be called on the scheduler's thread; LLM callbacks hop
 * back through it.
 */
public final class CompanionBrain {
    private static final Logger LOGGER = LoggerFactory.getLogger("friedai/brain");
    /** How many queued events one guest may have waiting for a turn. */
    public static final int MAX_PENDING_PER_GUEST = 2;

    private final Supplier<Persona> persona;
    private final Supplier<BrainSettings> settings;
    private final Supplier<LlmGateway> gateway;
    private final ToolExecutor tools;
    private final PerceptionSource perception;
    private final Speaker speaker;
    private final BrainScheduler scheduler;
    private final AccessResolver access;
    private final MemorySource memorySource;
    private final ConversationMemory memory;
    private BrainObserver observer = BrainObserver.NONE;

    private final Deque<BrainEvent> queue = new ArrayDeque<>();
    private final Map<UUID, Long> lastAcceptedGuestMessage = new HashMap<>();
    private Caller turnCaller;
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
            BrainScheduler scheduler,
            AccessResolver access) {
        this(persona, settings, gateway, tools, perception, speaker, scheduler, access, MemorySource.NONE);
    }

    public CompanionBrain(
            Supplier<Persona> persona,
            Supplier<BrainSettings> settings,
            Supplier<LlmGateway> gateway,
            ToolExecutor tools,
            PerceptionSource perception,
            Speaker speaker,
            BrainScheduler scheduler,
            AccessResolver access,
            MemorySource memorySource) {
        this.persona = persona;
        this.settings = settings;
        this.gateway = gateway;
        this.tools = tools;
        this.perception = perception;
        this.speaker = speaker;
        this.scheduler = scheduler;
        this.access = access;
        this.memorySource = memorySource;
        this.turnCaller = new Caller(access.owner(), "", AccessLevel.OWNER);
        this.memory = new ConversationMemory(settings.get().historyMessages());
    }

    public void post(BrainEvent e) {
        if (shouldDrop(e)) {
            return;
        }
        queue.addLast(e);
        maybeStartTurn();
    }

    /**
     * Guests are throttled because every request costs money; the owner never is. Dropped messages go to the
     * observer and the debug log, never to chat.
     */
    private boolean shouldDrop(BrainEvent event) {
        if (!(event instanceof BrainEvent.PlayerSaid said)) {
            return false;
        }
        UUID guest = said.playerUuid();
        if (access.accessOf(guest) != AccessLevel.GUEST) {
            return false;
        }
        long now = scheduler.nowMillis();
        Long last = lastAcceptedGuestMessage.get(guest);
        if (last != null && now - last < settings.get().guestCooldownMs()) {
            observe(o -> o.onEventDropped(event, "guest cooldown"));
            return true;
        }
        long pending = queue.stream()
                .filter(queued -> principalOf(queued).equals(guest))
                .count();
        if (pending >= MAX_PENDING_PER_GUEST) {
            observe(o -> o.onEventDropped(event, "too many pending messages"));
            return true;
        }
        lastAcceptedGuestMessage.put(guest, now);
        return false;
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
        return PromptBuilder.system(
                persona.get(), perception.describe(), turnCaller, memorySource.describe(turnCaller));
    }

    private void observe(java.util.function.Consumer<BrainObserver> call) {
        try {
            call.accept(observer);
        } catch (RuntimeException e) {
            LOGGER.warn("Brain observer failed", e);
        }
    }

    /** The player a turn acts for. Task events belong to the owner. */
    private UUID principalOf(BrainEvent event) {
        return event instanceof BrainEvent.PlayerSaid said ? said.playerUuid() : access.owner();
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
        UUID principal = principalOf(queue.peekFirst());
        List<BrainEvent> batch = new ArrayList<>();
        queue.removeIf(event -> {
            if (principalOf(event).equals(principal)) {
                batch.add(event);
                return true;
            }
            return false;
        });
        String name = "";
        for (BrainEvent event : batch) {
            if (event instanceof BrainEvent.PlayerSaid said) {
                name = said.playerName();
            }
        }
        AccessLevel level = access.accessOf(principal);
        turnCaller = new Caller(principal, name == null ? "" : name, level);
        thinking = true;
        speaker.thinking(true);
        String lines = batch.stream()
                .map(event -> event.toPromptLine(level))
                .collect(Collectors.joining("\n"));
        for (BrainEvent event : batch) {
            observe(o -> o.onEvent(event));
        }
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
        String system = PromptBuilder.system(
                persona.get(), perception.describe(), turnCaller, memorySource.describe(turnCaller));
        CompletableFuture<LlmResponse> future;
        try {
            future = gateway.get().complete(system, memory.snapshot(), tools.specs(turnCaller.access()));
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
                result = tools.execute(call, turnCaller);
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
