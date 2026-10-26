package dev.friendai.debug;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.LongSupplier;
import java.util.function.UnaryOperator;

/** Per-owner debug state: the brain log, task history and menu subscriptions. */
public final class DebugService {
    public static final int LOG_CAPACITY = 500;
    public static final int LOG_TEXT_CHARS = 2000;
    public static final int HISTORY_CAPACITY = 20;

    private final UnaryOperator<String> redactor;
    private final LongSupplier clock;
    private final Map<UUID, DebugLog> logs = new HashMap<>();
    private final Map<UUID, TaskHistory> histories = new HashMap<>();
    private final Map<UUID, Long> subscribers = new HashMap<>();
    private final Set<UUID> movementViewers = new HashSet<>();

    public DebugService(UnaryOperator<String> redactor, LongSupplier clock) {
        this.redactor = redactor;
        this.clock = clock;
    }

    public synchronized DebugLog logOf(UUID owner) {
        return logs.computeIfAbsent(owner, o -> new DebugLog(LOG_CAPACITY, LOG_TEXT_CHARS, redactor, clock));
    }

    public synchronized TaskHistory historyOf(UUID owner) {
        return histories.computeIfAbsent(owner, o -> new TaskHistory(HISTORY_CAPACITY));
    }

    public synchronized void subscribe(UUID owner) {
        subscribers.put(owner, 0L);
    }

    public synchronized void unsubscribe(UUID owner) {
        subscribers.remove(owner);
    }

    public synchronized Set<UUID> subscribers() {
        return new HashSet<>(subscribers.keySet());
    }

    /** Turns the movement overlay on or off for a player. */
    public synchronized void setMovementDebug(UUID owner, boolean on) {
        if (on) {
            movementViewers.add(owner);
        } else {
            movementViewers.remove(owner);
        }
    }

    public synchronized boolean movementDebug(UUID owner) {
        return movementViewers.contains(owner);
    }

    public synchronized Set<UUID> movementViewers() {
        return new HashSet<>(movementViewers);
    }

    public synchronized long lastSent(UUID owner) {
        return subscribers.getOrDefault(owner, 0L);
    }

    public synchronized void markSent(UUID owner, long logId) {
        if (subscribers.containsKey(owner)) {
            subscribers.put(owner, logId);
        }
    }
}
