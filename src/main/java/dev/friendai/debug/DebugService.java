package dev.friendai.debug;

import dev.friendai.execution.history.TaskHistory;
import dev.friendai.execution.log.TaskLogCorrelation;
import dev.friendai.foundation.diagnostics.DebugLog;
import dev.friendai.foundation.diagnostics.LogPipeline;
import dev.friendai.foundation.diagnostics.LogRecord;
import dev.friendai.foundation.diagnostics.LogSettings;
import dev.friendai.foundation.diagnostics.LogSink;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/** Per-owner debug state: the brain log, task history and menu subscriptions, plus the record pipeline. */
public final class DebugService implements LogSink {
    public static final int LOG_CAPACITY = 500;
    public static final int LOG_TEXT_CHARS = 2000;
    public static final int HISTORY_CAPACITY = 20;

    private final UnaryOperator<String> redactor;
    private final LongSupplier clock;
    private final LogPipeline pipeline;
    private final Map<UUID, DebugLog> logs = new HashMap<>();
    private final Map<UUID, TaskHistory> histories = new HashMap<>();
    private final Map<UUID, Long> subscribers = new HashMap<>();
    private final Set<UUID> movementViewers = new HashSet<>();
    private final Map<UUID, TaskLogCorrelation> correlations = new HashMap<>();

    public DebugService(UnaryOperator<String> redactor, LongSupplier clock) {
        this(redactor, clock, () -> LogSettings.off(), null);
    }

    public DebugService(
            UnaryOperator<String> redactor, LongSupplier clock, Supplier<LogSettings> settings, LogSink fileSink) {
        this.redactor = redactor;
        this.clock = clock;
        this.pipeline = new LogPipeline(
                this,
                settings == null ? () -> LogSettings.off() : settings,
                redactor,
                fileSink == null ? new LogSink[0] : new LogSink[] {fileSink});
    }

    /** The one producer entry point: the ring always, files per the current settings. */
    public LogPipeline pipeline() {
        return pipeline;
    }

    public void update(LogSettings settings) {
        pipeline.update(settings);
    }

    /** Keeps the menu buffer to records that have a dialogue role and an owner. */
    @Override
    public void accept(LogRecord record) {
        if (record == null || record.owner() == null || record.kind() == null) return;
        logOf(record.owner()).add(record);
    }

    public synchronized DebugLog logOf(UUID owner) {
        return logs.computeIfAbsent(owner, o -> new DebugLog(LOG_CAPACITY, LOG_TEXT_CHARS, redactor, clock));
    }

    public UnaryOperator<String> redactor() {
        return redactor;
    }

    /**
     * The bounded parent resolver for one owner's deferred lifecycle facts. It is transient bookkeeping, not an event
     * store, and lives beside the owner's other debug state.
     */
    public synchronized TaskLogCorrelation correlation(UUID owner) {
        return correlations.computeIfAbsent(owner, key -> new TaskLogCorrelation());
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
