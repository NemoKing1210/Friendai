package dev.friendai.task.trading;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Server-thread tracker for item entities emitted by the exact vanilla barter-response call. */
public final class BarterDropTracker {
    public static final int MAX_ATTEMPTS = 256;
    public static final int MAX_OUTPUTS_PER_ATTEMPT = 64;
    public static final long MAX_ATTEMPT_TICKS = 12_000;

    private record Attempt(UUID piglin, long startedAt, List<UUID> outputs, Set<UUID> seen) {}

    private final Map<UUID, Attempt> attempts = new HashMap<>();
    private final Map<UUID, UUID> piglinAttempts = new HashMap<>();
    private final Set<UUID> overflowed = new HashSet<>();

    /** Starts attribution before the gold interaction. Only one attempt may watch a piglin at once. */
    public void begin(UUID attempt, UUID piglin, long tick) {
        if (attempt == null || piglin == null || tick < 0 || attempts.containsKey(attempt)) {
            throw new IllegalArgumentException("barter attempt, piglin and nonnegative tick are required");
        }
        if (piglinAttempts.containsKey(piglin))
            throw new IllegalStateException("piglin already has an active barter attempt");
        if (attempts.size() >= MAX_ATTEMPTS) throw new IllegalStateException("too many active barter attempts");
        attempts.put(attempt, new Attempt(piglin, tick, new ArrayList<>(), new HashSet<>()));
        piglinAttempts.put(piglin, attempt);
    }

    public List<UUID> outputs(UUID attempt) {
        Attempt value = attempts.get(attempt);
        return value == null ? List.of() : List.copyOf(value.outputs());
    }

    public boolean hasAttemptFor(UUID piglin) {
        return piglinAttempts.containsKey(piglin);
    }

    public java.util.Optional<UUID> attemptFor(UUID piglin) {
        return java.util.Optional.ofNullable(piglinAttempts.get(piglin));
    }

    /** Called only from the exact vanilla barter response item-spawn call intercepted by the mixin. */
    public boolean observeVanillaOutput(UUID attempt, UUID piglin, UUID entity, long tick) {
        UUID activeId = piglin == null ? null : piglinAttempts.get(piglin);
        Attempt active = activeId == null ? null : attempts.get(activeId);
        if (active == null
                || !activeId.equals(attempt)
                || entity == null
                || tick < active.startedAt()
                || tick - active.startedAt() > MAX_ATTEMPT_TICKS
                || !active.seen().add(entity)) return false;
        if (active.outputs().size() >= MAX_OUTPUTS_PER_ATTEMPT) {
            overflowed.add(attempt);
            return false;
        }
        active.outputs().add(entity);
        return true;
    }

    public boolean overflowed(UUID attempt) {
        return overflowed.contains(attempt);
    }

    public boolean ownsAttempt(UUID attempt, UUID piglin) {
        return attempt != null && piglin != null && attempt.equals(piglinAttempts.get(piglin));
    }

    public void finish(UUID attempt) {
        Attempt removed = attempts.remove(attempt);
        overflowed.remove(attempt);
        if (removed != null) piglinAttempts.remove(removed.piglin(), attempt);
    }

    /** Retires abandoned attribution windows after the bounded pending-output recovery period. */
    public void expire(long tick) {
        if (tick < 0) return;
        var expired = attempts.entrySet().stream()
                .filter(entry -> tick >= entry.getValue().startedAt()
                        && tick - entry.getValue().startedAt() > MAX_ATTEMPT_TICKS)
                .map(Map.Entry::getKey)
                .toList();
        expired.forEach(this::finish);
    }
}
