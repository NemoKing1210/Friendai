package dev.friendai.server.agent;

import com.google.gson.JsonObject;
import dev.friendai.agent.turn.BrainEvent;
import dev.friendai.agent.turn.SkillTurnSession;
import dev.friendai.execution.tool.Caller;
import dev.friendai.execution.tool.ToolResult;
import dev.friendai.features.memory.GoalOutcomeFact;
import dev.friendai.foundation.teaching.TeachingContext;
import dev.friendai.infrastructure.config.ServerConfig;
import dev.friendai.server.instructions.SkillService;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/** The owner-facing instruction-skill surface of one server: reads, teaching and the outcome a turn reports. */
final class OwnerSkillTools {
    private final SkillService skills;
    private final Supplier<ServerConfig> config;
    private final Function<UUID, SkillTurnSession> turnSessions;
    private final Function<UUID, TeachingContext> teachingContexts;
    private final BiConsumer<UUID, BrainEvent> poster;
    private final GoalOutcomeRecorder outcomes;
    private final UnaryOperator<String> redactor;

    OwnerSkillTools(
            SkillService skills,
            Supplier<ServerConfig> config,
            Function<UUID, SkillTurnSession> turnSessions,
            Function<UUID, TeachingContext> teachingContexts,
            BiConsumer<UUID, BrainEvent> poster,
            GoalOutcomeRecorder outcomes,
            UnaryOperator<String> redactor) {
        this.skills = skills;
        this.config = config;
        this.turnSessions = turnSessions;
        this.teachingContexts = teachingContexts;
        this.poster = poster;
        this.outcomes = outcomes;
        this.redactor = redactor;
    }

    /** The skill session of the owner's turn, or the empty one before a brain is attached. */
    SkillTurnSession turn(UUID owner) {
        return turnSessions.apply(owner);
    }

    TeachingContext teachingContext(UUID owner) {
        return teachingContexts.apply(owner);
    }

    /**
     * Reports how a taught run ended. An enabled server tells the brain; every server records the one memory line
     * a goal outcome earns.
     */
    void outcome(UUID owner, String id, TeachingContext.OutcomeKind kind, String summary) {
        outcome(owner, id, kind, summary, "");
    }

    void outcome(UUID owner, String id, TeachingContext.OutcomeKind kind, String summary, String rawReason) {
        String reason = rawReason == null || rawReason.isBlank()
                ? ""
                : GoalOutcomeFact.boundedReason(redactor.apply(rawReason));
        if (config.get().skills.enabled) {
            String eventSummary = reason.isEmpty() ? summary : summary + "; reason: " + reason;
            poster.accept(owner, new BrainEvent.SkillOutcome(id, kind, eventSummary));
        }
        outcomes.remember(owner, id, kind, summary, reason);
    }

    ToolResult request(UUID owner, String id) {
        return turn(owner).request(id);
    }

    ToolResult list(UUID owner, int page) {
        return skills.list(owner, page);
    }

    ToolResult help() {
        return skills.help();
    }

    ToolResult teach(UUID owner, Caller caller, JsonObject args) {
        return skills.teach(owner, caller, args);
    }
}
