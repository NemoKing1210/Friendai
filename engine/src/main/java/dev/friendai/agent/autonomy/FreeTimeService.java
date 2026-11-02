package dev.friendai.agent.autonomy;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.LongPredicate;
import java.util.function.UnaryOperator;

/**
 * One cached {@link FreeTime} per owner, loaded and saved through {@link FreeTimeRepository} so free time outlives
 * the companion entity and restarts. Every mutation saves. Server thread only.
 */
public final class FreeTimeService {
    /** The summary recorded when a saved shift's execution plan did not come back. */
    static final String ORPHANED_SHIFT = "the saved work plan was not restored";

    private final FreeTimeRepository repository;
    private final Map<UUID, FreeTime> states = new HashMap<>();
    private final Map<UUID, Long> lastEnd = new HashMap<>();
    /** End reports the driver has not said yet, written when a restored shift could not be resumed. */
    private final Map<UUID, String> pendingReports = new HashMap<>();

    public FreeTimeService(FreeTimeRepository repository) {
        this.repository = repository;
    }

    /** The owner's state, loaded by the repository on first use; a missing or broken save is {@link FreeTime#OFF}. */
    public FreeTime state(UUID owner) {
        return states.computeIfAbsent(owner, repository::state);
    }

    /** Turns free time on; when it is already on, only the wish changes. */
    public FreeTime start(UUID owner, FreeTime.Origin origin, String wish, long now) {
        return update(
                owner,
                s -> s.on()
                        ? copy(
                                s,
                                true,
                                s.origin(),
                                wish,
                                s.sinceMillis(),
                                s.shift(),
                                s.awaitingChoice(),
                                s.nextShiftId(),
                                s.history(),
                                s.cooldownUntil())
                        : copy(
                                s,
                                true,
                                origin,
                                wish,
                                now,
                                null,
                                false,
                                s.nextShiftId(),
                                s.history(),
                                s.cooldownUntil()));
    }

    /** Turns free time off, closing a shift in progress as failed with {@code reason}. */
    public FreeTime stop(UUID owner, String reason, long now) {
        return stop(owner, reason, now, ShiftStopWhen.OWNER_SPOKE);
    }

    /** As above, recording the stop condition the audit keeps for the closed shift. */
    public FreeTime stop(UUID owner, String reason, long now, ShiftStopWhen stopWhen) {
        lastEnd.put(owner, now);
        return update(owner, s -> {
            List<ShiftRecord> history = s.shift() == null ? s.history() : withRecord(s, false, reason, now, stopWhen);
            return copy(s, false, s.origin(), "", 0, null, false, s.nextShiftId(), history, s.cooldownUntil());
        });
    }

    /** When free time last ended in this server run; zero when it has not. */
    public long lastEndMillis(UUID owner) {
        return lastEnd.getOrDefault(owner, 0L);
    }

    /** A director turn was asked to choose an activity. */
    public FreeTime awaitChoice(UUID owner) {
        return update(
                owner,
                s -> copy(
                        s,
                        s.on(),
                        s.origin(),
                        s.wish(),
                        s.sinceMillis(),
                        s.shift(),
                        true,
                        s.nextShiftId(),
                        s.history(),
                        s.cooldownUntil()));
    }

    /** The director turn ended without a choice. */
    public FreeTime clearChoice(UUID owner) {
        return update(
                owner,
                s -> copy(
                        s,
                        s.on(),
                        s.origin(),
                        s.wish(),
                        s.sinceMillis(),
                        s.shift(),
                        false,
                        s.nextShiftId(),
                        s.history(),
                        s.cooldownUntil()));
    }

    public FreeTime beginShift(
            UUID owner,
            String goal,
            List<String> criteria,
            String why,
            NeedKind need,
            boolean reflex,
            long now,
            long deadline) {
        return update(owner, s -> {
            Shift shift = new Shift(s.nextShiftId(), goal, criteria, why, 0, now, deadline, reflex, need);
            return copy(
                    s,
                    s.on(),
                    s.origin(),
                    s.wish(),
                    s.sinceMillis(),
                    shift,
                    false,
                    s.nextShiftId() + 1,
                    s.history(),
                    s.cooldownUntil());
        });
    }

    public FreeTime linkPlan(UUID owner, long planId) {
        return update(
                owner,
                s -> s.shift() == null
                        ? s
                        : copy(
                                s,
                                s.on(),
                                s.origin(),
                                s.wish(),
                                s.sinceMillis(),
                                s.shift().withPlan(planId),
                                s.awaitingChoice(),
                                s.nextShiftId(),
                                s.history(),
                                s.cooldownUntil()));
    }

    /** Ends the current shift and records how it went; the work finished unless a caller says otherwise. */
    public FreeTime finishShift(UUID owner, boolean success, String summary, long now) {
        return finishShift(owner, success, summary, now, ShiftStopWhen.DONE);
    }

    /** As above, recording which stop condition ended the shift. */
    public FreeTime finishShift(UUID owner, boolean success, String summary, long now, ShiftStopWhen stopWhen) {
        return update(
                owner,
                s -> s.shift() == null
                        ? s
                        : copy(
                                s,
                                s.on(),
                                s.origin(),
                                s.wish(),
                                s.sinceMillis(),
                                null,
                                s.awaitingChoice(),
                                s.nextShiftId(),
                                withRecord(s, success, summary, now, stopWhen),
                                s.cooldownUntil()));
    }

    public FreeTime cooldown(UUID owner, NeedKind need, long untilMillis) {
        return update(owner, s -> {
            Map<NeedKind, Long> cooldowns = new EnumMap<>(NeedKind.class);
            cooldowns.putAll(s.cooldownUntil());
            cooldowns.put(need, untilMillis);
            return copy(
                    s,
                    s.on(),
                    s.origin(),
                    s.wish(),
                    s.sinceMillis(),
                    s.shift(),
                    s.awaitingChoice(),
                    s.nextShiftId(),
                    s.history(),
                    cooldowns);
        });
    }

    public FreeTime addLesson(UUID owner, String lesson) {
        return update(owner, s -> {
            List<String> lessons = new ArrayList<>(s.lessons());
            lessons.add(lesson);
            return new FreeTime(
                    s.on(),
                    s.origin(),
                    s.wish(),
                    s.sinceMillis(),
                    s.shift(),
                    s.awaitingChoice(),
                    s.nextShiftId(),
                    s.history(),
                    lessons,
                    s.cooldownUntil(),
                    s.away());
        });
    }

    /** Applies a change and saves it through the repository. */
    public FreeTime update(UUID owner, UnaryOperator<FreeTime> change) {
        FreeTime next = change.apply(state(owner));
        states.put(owner, next);
        repository.save(owner, next);
        return next;
    }

    /** Reconciles the saved shift only after its execution queue has finished restoring. */
    public FreeTime reconcileRestoredPlan(UUID owner, LongPredicate restoredPlanExists) {
        return reconcileRestoredPlan(owner, restoredPlanExists, System.currentTimeMillis());
    }

    public FreeTime reconcileRestoredPlan(UUID owner, LongPredicate restoredPlanExists, long nowMillis) {
        FreeTime current = state(owner);
        if (current.shift() == null
                || current.shift().planId() != 0
                        && restoredPlanExists.test(current.shift().planId())) return current;
        Shift orphan = current.shift();
        String summary = ORPHANED_SHIFT;
        FreeTime ended = finishShift(owner, false, summary, nowMillis, ShiftStopWhen.WORK_CONFLICTS);
        // The driver says this once the companion is loaded, so a restart still reports what it gave up on.
        pendingReports.put(owner, FreeTimeText.endReport(orphan, false, summary, ShiftStopWhen.WORK_CONFLICTS));
        return ended;
    }

    /** The report for a shift ended during restore, taken once so it is said at most once. */
    public Optional<String> takePendingReport(UUID owner) {
        return Optional.ofNullable(pendingReports.remove(owner));
    }

    private static List<ShiftRecord> withRecord(
            FreeTime s, boolean success, String summary, long now, ShiftStopWhen stopWhen) {
        List<ShiftRecord> history = new ArrayList<>();
        Shift shift = s.shift();
        history.add(new ShiftRecord(shift.id(), shift.goal(), shift.need(), success, summary, now, stopWhen));
        history.addAll(s.history());
        return history;
    }

    private static FreeTime copy(
            FreeTime s,
            boolean on,
            FreeTime.Origin origin,
            String wish,
            long since,
            Shift shift,
            boolean awaiting,
            long nextShift,
            List<ShiftRecord> history,
            Map<NeedKind, Long> cooldowns) {
        return new FreeTime(
                on, origin, wish, since, shift, awaiting, nextShift, history, s.lessons(), cooldowns, s.away());
    }
}
