package dev.friendai.features.knowledge.search;

import dev.friendai.features.navigation.pathing.Pos;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Pure goal-level search state machine; it makes decisions but never moves the companion. */
public final class SearchSession {
    public enum Phase {
        PREPARE,
        CHOOSE_FRONTIER,
        TRAVEL,
        INSPECT,
        EVALUATE,
        RETURN,
        COMPLETE,
        FAILED,
        INTERRUPTED
    }

    public enum Reason {
        CONTINUE,
        DISCOVERED,
        NO_FRONTIER,
        NOT_READY,
        BLOCKED,
        BUDGET_RESERVE,
        RETURNED,
        WRONG_DIMENSION,
        LIMIT_REACHED,
        INTERRUPTED
    }

    public enum LegOutcome {
        NONE,
        ARRIVED,
        BLOCKED
    }

    public record Limits(int radius, double travelBlocks, long ticks, int excavationBlocks) {
        public Limits {
            if (radius < 0 || !Double.isFinite(travelBlocks) || travelBlocks < 0 || ticks < 1 || excavationBlocks < 0)
                throw new IllegalArgumentException("invalid search limits");
        }
    }

    public record Input(
            long tick,
            String dimension,
            Pos position,
            boolean inventoryReady,
            boolean discovered,
            LegOutcome legOutcome,
            SearchPlanner.Frontier frontier,
            double travelBlocks,
            long workTicks,
            int excavationBlocks,
            long returnEstimateTicks) {
        public Input {
            Objects.requireNonNull(position);
            Objects.requireNonNull(legOutcome);
            if (tick < 0 || travelBlocks < 0 || workTicks < 0 || excavationBlocks < 0 || returnEstimateTicks < 0)
                throw new IllegalArgumentException("negative measured work");
        }
    }

    public record Decision(Phase phase, Optional<SearchPlanner.Frontier> frontier, Reason reason) {}

    public record Snapshot(
            UUID id,
            String dimension,
            String goal,
            Pos origin,
            Limits limits,
            Phase phase,
            long startedTick,
            long lastTick,
            double spentTravelBlocks,
            long spentTicks,
            int excavationBlocks,
            int blockedLegs) {}

    private final UUID id;
    private final String dimension, goal;
    private final Pos origin;
    private Limits limits;
    private long startedTick;
    private Phase phase = Phase.PREPARE;
    private long lastTick;
    private double spentTravelBlocks;
    private long spentTicks;
    private int excavationBlocks;
    private int blockedLegs;
    private Runnable onChange;

    public SearchSession(UUID id, String dimension, String goal, Pos origin, Limits limits, long tick) {
        this.id = Objects.requireNonNull(id);
        this.dimension = requireText(dimension);
        this.goal = requireText(goal);
        this.origin = Objects.requireNonNull(origin);
        this.limits = Objects.requireNonNull(limits);
        if (tick < 0) throw new IllegalArgumentException("tick must not be negative");
        this.startedTick = tick;
        this.lastTick = tick;
    }

    public static SearchSession restore(Snapshot snapshot) {
        SearchSession restored = new SearchSession(
                snapshot.id(),
                snapshot.dimension(),
                snapshot.goal(),
                snapshot.origin(),
                snapshot.limits(),
                snapshot.startedTick());
        restored.phase = snapshot.phase();
        restored.lastTick = snapshot.lastTick();
        restored.spentTravelBlocks = snapshot.spentTravelBlocks();
        restored.spentTicks = snapshot.spentTicks();
        restored.excavationBlocks = snapshot.excavationBlocks();
        restored.blockedLegs = snapshot.blockedLegs();
        return restored;
    }

    /** Registers a listener that runs after every state or counter change, so the owner's book is flushed. */
    public synchronized void onChange(Runnable listener) {
        onChange = listener;
    }

    private void changed() {
        if (onChange != null) onChange.run();
    }

    public synchronized Decision advance(Input input) {
        Decision decision = advanceInternal(input);
        changed();
        return decision;
    }

    private Decision advanceInternal(Input input) {
        if (!dimension.equals(input.dimension())) return terminal(Phase.FAILED, Reason.WRONG_DIMENSION);
        lastTick = Math.max(lastTick, input.tick());
        spentTicks = Math.max(spentTicks, input.tick() - startedTick) + input.workTicks();
        spentTravelBlocks += input.travelBlocks();
        excavationBlocks += input.excavationBlocks();
        if (input.discovered()) {
            phase = Phase.RETURN;
            return decision(Reason.DISCOVERED, null);
        }
        if (spentTicks >= limits.ticks()
                || spentTravelBlocks >= limits.travelBlocks()
                || excavationBlocks >= limits.excavationBlocks()) return terminal(Phase.RETURN, Reason.LIMIT_REACHED);
        switch (phase) {
            case PREPARE -> {
                if (!input.inventoryReady()) return terminal(Phase.FAILED, Reason.NOT_READY);
                phase = Phase.CHOOSE_FRONTIER;
            }
            case CHOOSE_FRONTIER -> {
                if (input.frontier() == null)
                    return terminal(atOrigin(input.position()) ? Phase.FAILED : Phase.RETURN, Reason.NO_FRONTIER);
                if (distance(input.frontier().position(), origin) > limits.radius()) {
                    phase = Phase.CHOOSE_FRONTIER;
                    return decision(Reason.LIMIT_REACHED, null);
                }
                long outboundAndReturn = input.frontier().estimatedTravelTicks() + input.returnEstimateTicks();
                if (spentTicks + outboundAndReturn >= limits.ticks()
                        || spentTravelBlocks + distance(input.frontier().position(), origin) * 2
                                > limits.travelBlocks()) {
                    phase = Phase.RETURN;
                    return decision(Reason.BUDGET_RESERVE, null);
                }
                phase = Phase.TRAVEL;
                return decision(Reason.CONTINUE, input.frontier());
            }
            case TRAVEL -> {
                if (input.legOutcome() == LegOutcome.ARRIVED) phase = Phase.INSPECT;
                else if (input.legOutcome() == LegOutcome.BLOCKED) {
                    blockedLegs++;
                    phase = Phase.CHOOSE_FRONTIER;
                    return decision(Reason.BLOCKED, null);
                }
            }
            case INSPECT -> phase = Phase.EVALUATE;
            case EVALUATE -> phase = Phase.CHOOSE_FRONTIER;
            case RETURN -> {
                if (atOrigin(input.position())) return terminal(Phase.COMPLETE, Reason.RETURNED);
            }
            case COMPLETE, FAILED, INTERRUPTED -> {
                return decision(Reason.CONTINUE, null);
            }
        }
        return decision(Reason.CONTINUE, null);
    }

    public synchronized void interrupt() {
        phase = Phase.INTERRUPTED;
        changed();
    }

    /** Accounts for one task tick of travel and excavation without forcing a planner phase transition. */
    public synchronized boolean accountWork(long tick, Pos position, double travelBlocks, int blocksExcavated) {
        Objects.requireNonNull(position);
        if (tick < 0 || !Double.isFinite(travelBlocks) || travelBlocks < 0 || blocksExcavated < 0)
            throw new IllegalArgumentException("invalid measured work");
        lastTick = Math.max(lastTick, tick);
        spentTicks = Math.max(spentTicks, Math.max(0, tick - startedTick));
        spentTravelBlocks += travelBlocks;
        excavationBlocks += blocksExcavated;
        if (spentTicks >= limits.ticks()
                || spentTravelBlocks >= limits.travelBlocks()
                || excavationBlocks >= limits.excavationBlocks()) {
            if (phase != Phase.COMPLETE && phase != Phase.FAILED && phase != Phase.INTERRUPTED) phase = Phase.RETURN;
        }
        changed();
        return phase == Phase.RETURN;
    }

    /** Applies a live policy reduction while preserving every unit of already-spent work. */
    public synchronized void clampLimits(Limits currentPolicy) {
        Objects.requireNonNull(currentPolicy);
        limits = new Limits(
                Math.min(limits.radius(), currentPolicy.radius()),
                Math.min(limits.travelBlocks(), currentPolicy.travelBlocks()),
                Math.min(limits.ticks(), currentPolicy.ticks()),
                Math.min(limits.excavationBlocks(), currentPolicy.excavationBlocks()));
        changed();
    }

    public synchronized void resumeForNewRequest(long now) {
        if (phase != Phase.INTERRUPTED) return;
        if (now < 0) throw new IllegalArgumentException("tick must not be negative");
        phase = Phase.CHOOSE_FRONTIER;
        lastTick = now;
        startedTick = Math.max(0, now - spentTicks);
        changed();
    }

    /**
     * Resumes after a world reload: spent counters stay untouched, a return trip keeps walking home, and every
     * other live phase re-plans from the current position. A finished or failed session stays finished.
     */
    public synchronized void resumeAfterReload(long now) {
        if (now < 0) throw new IllegalArgumentException("tick must not be negative");
        lastTick = Math.max(lastTick, now);
        startedTick = Math.max(0, now - spentTicks);
        if (phase == Phase.COMPLETE || phase == Phase.FAILED) return;
        if (phase != Phase.RETURN) phase = Phase.CHOOSE_FRONTIER;
        changed();
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(
                id,
                dimension,
                goal,
                origin,
                limits,
                phase,
                startedTick,
                lastTick,
                spentTravelBlocks,
                spentTicks,
                excavationBlocks,
                blockedLegs);
    }

    public synchronized boolean terminal() {
        return phase == Phase.COMPLETE || phase == Phase.FAILED || phase == Phase.INTERRUPTED;
    }

    private boolean atOrigin(Pos p) {
        return distance(p, origin) <= 2.0;
    }

    private static double distance(Pos a, Pos b) {
        double dx = (double) a.x() - b.x(), dy = (double) a.y() - b.y(), dz = (double) a.z() - b.z();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private Decision terminal(Phase next, Reason reason) {
        phase = next;
        return decision(reason, null);
    }

    private Decision decision(Reason reason, SearchPlanner.Frontier frontier) {
        return new Decision(phase, Optional.ofNullable(frontier), reason);
    }

    private static String requireText(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("value must not be blank");
        return value;
    }
}
