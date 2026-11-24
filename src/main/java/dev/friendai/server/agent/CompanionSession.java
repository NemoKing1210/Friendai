package dev.friendai.server.agent;

import dev.friendai.agent.plan.Planner;
import dev.friendai.agent.turn.CompanionBrain;
import dev.friendai.agent.turn.LlmGateway;
import dev.friendai.agent.turn.SkillTurnSession;
import dev.friendai.agent.turn.Speaker;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.execution.tool.Caller;
import dev.friendai.features.memory.MemoryBook;
import dev.friendai.foundation.teaching.TeachingContext;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
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
    /** Runtime-only replan budget for plans that have no goal to carry one. */
    private final dev.friendai.agent.plan.ReplanBudget planReplans = new dev.friendai.agent.plan.ReplanBudget();

    private final ArrayDeque<String> verifiedFacts = new ArrayDeque<>();
    private CompanionEntity companion;
    private CompanionBrain brain;
    private LlmGateway gatewayOverride;
    private UnaryOperator<Speaker> speakerWrapper = UnaryOperator.identity();
    private Planner plannerOverride;
    private long lastGoalTick;
    private int nudgeStreak;
    private String logSession = "";
    private long createdGoalTurn;
    private long createdGoalId;

    public CompanionSession(UUID owner, MemoryBook memory) {
        this.owner = Objects.requireNonNull(owner);
        this.tradingAuthority = new TradingTurnAuthority(owner);
        this.profileObserver = new OwnerProfileObserver(owner, Objects.requireNonNull(memory));
    }

    public UUID owner() {
        return owner;
    }

    void noteCreatedGoal(long turn, long goalId) {
        createdGoalTurn = turn;
        createdGoalId = goalId;
    }

    long goalCreatedInTurn(long turn) {
        return turn > 0 && createdGoalTurn == turn ? createdGoalId : 0L;
    }

    /** The id this session's durable records carry; empty until the brain attaches. */
    public String logSession() {
        return logSession;
    }

    void logSession(String id) {
        this.logSession = id == null ? "" : id;
    }

    public boolean isThinking() {
        return brain != null && brain.isThinking();
    }

    public boolean hasPendingWork() {
        return brain != null && brain.hasPendingWork();
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

    dev.friendai.agent.plan.ReplanBudget planReplans() {
        return planReplans;
    }

    /** The last verified outcome facts, newest first: the planner must not re-prove them. */
    List<String> verifiedFacts() {
        return List.copyOf(verifiedFacts);
    }

    /**
     * The conversation evidence a planner may ground a reference in, asked for one principal and one line. Both
     * blocks come from the session that already builds them for the reply prompt, so the planner never sees more
     * than the model would.
     */
    @FunctionalInterface
    public interface DialogueEvidence {
        DialogueEvidence NONE = (caller, text) -> Dialogue.of(List.of(), "");

        Dialogue forCaller(Caller caller, String playerText);
    }

    /** The evidence builder this session was created with; replaced when a companion binds its memory source. */
    private DialogueEvidence dialogueEvidence = DialogueEvidence.NONE;

    void dialogueEvidence(DialogueEvidence evidence) {
        this.dialogueEvidence = evidence == null ? DialogueEvidence.NONE : evidence;
    }

    public Dialogue dialogueFor(Caller caller, String playerText) {
        return dialogueEvidence.forCaller(caller, playerText);
    }

    /**
     * What one planner call may use to resolve a reference.
     *
     * @param dialogue delivered lines with this requester, oldest first, each prefixed by its speaker
     * @param remembered the durable-memory block for this requester
     * @param memoryGrounds whether the retrieval found remembered facts that match what was just said; the block
     *     itself always carries the full fallback, so only a match proves memory speaks to this line
     */
    public record Dialogue(List<String> dialogue, String remembered, boolean memoryGrounds) {
        public Dialogue {
            dialogue = dialogue == null ? List.of() : List.copyOf(dialogue);
            remembered = remembered == null ? "" : remembered;
        }

        public static Dialogue of(List<String> dialogue, String remembered) {
            return new Dialogue(dialogue, remembered, false);
        }

        public static Dialogue of(List<String> dialogue, String remembered, boolean memoryGrounds) {
            return new Dialogue(dialogue, remembered, memoryGrounds);
        }
    }

    void noteVerified(java.util.Collection<String> facts) {
        for (String fact : facts) {
            verifiedFacts.addFirst(fact);
        }
        while (verifiedFacts.size() > 8) {
            verifiedFacts.removeLast();
        }
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
        createdGoalTurn = 0;
        createdGoalId = 0;
    }

    void invalidateBrain() {
        CompanionBrain detached = brain;
        brain = null;
        companion = null;
        createdGoalTurn = 0;
        createdGoalId = 0;
        if (detached != null) detached.cancel();
    }
}
