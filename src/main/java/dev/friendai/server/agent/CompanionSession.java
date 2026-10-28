package dev.friendai.server.agent;

import dev.friendai.agent.plan.Planner;
import dev.friendai.agent.turn.CompanionBrain;
import dev.friendai.agent.turn.LlmGateway;
import dev.friendai.agent.turn.SkillTurnSession;
import dev.friendai.agent.turn.Speaker;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.foundation.teaching.TeachingContext;
import dev.friendai.memory.MemoryBook;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.UnaryOperator;

/** Mutable agent state bound to one owner; entity changes invalidate the brain before rebinding. */
public final class CompanionSession {
    private final UUID owner;
    private final TradingTurnAuthority tradingAuthority;
    private final OwnerProfileObserver profileObserver;
    private final Set<Long> replacedPlans = new HashSet<>();
    private CompanionEntity companion;
    private CompanionBrain brain;
    private LlmGateway gatewayOverride;
    private UnaryOperator<Speaker> speakerWrapper = UnaryOperator.identity();
    private Planner plannerOverride;
    private long lastGoalTick;
    private int nudgeStreak;

    public CompanionSession(UUID owner, MemoryBook memory) {
        this.owner = Objects.requireNonNull(owner);
        this.tradingAuthority = new TradingTurnAuthority(owner);
        this.profileObserver = new OwnerProfileObserver(owner, Objects.requireNonNull(memory));
    }

    public UUID owner() {
        return owner;
    }

    public boolean isThinking() {
        return brain != null && brain.isThinking();
    }

    public SkillTurnSession skillSession() {
        return brain == null ? SkillTurnSession.NONE : brain.skillSession();
    }

    public TeachingContext teachingContext() {
        return brain == null ? null : brain.teachingContext();
    }

    public TradingTurnAuthority tradingAuthority() {
        return tradingAuthority;
    }

    CompanionEntity companion() {
        return companion;
    }

    CompanionBrain brain() {
        return brain;
    }

    LlmGateway gatewayOverride() {
        return gatewayOverride;
    }

    UnaryOperator<Speaker> speakerWrapper() {
        return speakerWrapper;
    }

    Planner plannerOverride() {
        return plannerOverride;
    }

    OwnerProfileObserver profileObserver() {
        return profileObserver;
    }

    Set<Long> replacedPlans() {
        return replacedPlans;
    }

    long lastGoalTick() {
        return lastGoalTick;
    }

    void lastGoalTick(long tick) {
        lastGoalTick = tick;
    }

    int nudgeStreak() {
        return nudgeStreak;
    }

    void incrementNudgeStreak() {
        nudgeStreak++;
    }

    void resetNudgeStreak() {
        nudgeStreak = 0;
    }

    void gatewayOverride(LlmGateway gateway) {
        gatewayOverride = gateway;
    }

    void speakerWrapper(UnaryOperator<Speaker> wrapper) {
        speakerWrapper = Objects.requireNonNull(wrapper);
    }

    void plannerOverride(Planner planner) {
        plannerOverride = planner;
    }

    void attach(CompanionEntity companion, CompanionBrain brain) {
        this.companion = Objects.requireNonNull(companion);
        this.brain = Objects.requireNonNull(brain);
    }

    void invalidateBrain() {
        CompanionBrain detached = brain;
        brain = null;
        companion = null;
        if (detached != null) detached.cancel();
    }
}
