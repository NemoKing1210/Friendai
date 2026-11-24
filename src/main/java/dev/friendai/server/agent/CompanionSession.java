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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.UnaryOperator;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/** Mutable agent state bound to one owner; entity changes invalidate the brain before rebinding. */
public final class CompanionSession {
    private final UUID owner;
    private final TradingTurnAuthority tradingAuthority;
    private final OwnerProfileObserver profileObserver;
    private final Set<Long> replacedPlans = new HashSet<>();
    /** Runtime-only replan budget for plans that have no goal to carry one. */
    private final dev.friendai.agent.plan.ReplanBudget planReplans = new dev.friendai.agent.plan.ReplanBudget();

    /**
     * Verified outcome receipts, newest first, each with the goal and plan it proves. An unowned receipt is not kept:
     * a fact nobody can attribute is not evidence for anybody's plan.
     */
    private final ArrayDeque<dev.friendai.agent.verify.VerifiedEvidence> verifiedFacts = new ArrayDeque<>();

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

    /** The receipts that prove something for one goal or one plan, newest first; empty for an unowned objective. */
    List<String> verifiedFacts(long goalId, long planId, long now) {
        if (verifiedFacts.isEmpty()) return List.of();
        return dev.friendai.agent.verify.VerifiedEvidence.forObjective(
                List.copyOf(verifiedFacts), goalId, planId, now, liveStock());
    }

    /**
     * What the companion carries now, so a receipt for stock it no longer holds is not offered as proof. The counts
     * are read once per planner call; a detached session cannot see inventory and so drops every stock receipt.
     */
    private dev.friendai.agent.verify.VerifiedEvidence.LiveStock liveStock() {
        CompanionEntity entity = companion;
        if (entity == null) return itemId -> -1;
        var inventory = entity.inventory();
        Map<String, Integer> counts = new HashMap<>();
        List<ItemStack> stacks = new ArrayList<>(inventory.getContainerSize());
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty()) continue;
            stacks.add(stack);
            counts.merge(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(), stack.getCount(), Integer::sum);
        }
        return itemId -> {
            if (!itemId.startsWith("#")) return counts.getOrDefault(itemId, 0);
            Identifier id;
            try {
                id = Identifier.parse(itemId.substring(1));
            } catch (RuntimeException notAnId) {
                return -1;
            }
            TagKey<Item> tag = TagKey.create(Registries.ITEM, id);
            int count = 0;
            for (ItemStack stack : stacks) {
                if (stack.is(tag)) count += stack.getCount();
            }
            return count;
        };
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

    void noteVerified(dev.friendai.agent.verify.VerifiedEvidence receipt) {
        if (receipt == null || (receipt.goalId() <= 0 && receipt.planId() <= 0)) return;
        verifiedFacts.addFirst(receipt);
        while (verifiedFacts.size() > dev.friendai.agent.verify.VerifiedEvidence.MAX_ITEMS) {
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
