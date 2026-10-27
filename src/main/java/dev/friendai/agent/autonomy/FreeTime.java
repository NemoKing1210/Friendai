package dev.friendai.agent.autonomy;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * One owner's free-time state. Immutable; {@link FreeTimeService} replaces it on every change.
 *
 * @param origin why free time is on; meaningless while it is off
 * @param wish the owner's optional hint, like "focus on food"
 * @param shift the shift in progress, or null
 * @param awaitingChoice a director turn was asked to choose an activity and has not done so yet
 * @param history finished shifts, newest first
 * @param lessons what failed shifts taught, oldest first
 * @param cooldownUntil needs that recently failed, with the time they may come back
 * @param away lines saved for an owner who was not there to hear them, oldest first
 */
public record FreeTime(
        boolean on,
        Origin origin,
        String wish,
        long sinceMillis,
        Shift shift,
        boolean awaitingChoice,
        long nextShiftId,
        List<ShiftRecord> history,
        List<String> lessons,
        Map<NeedKind, Long> cooldownUntil,
        List<String> away) {

    public static final int MAX_HISTORY = 5;
    public static final int MAX_LESSONS = 5;
    public static final int MAX_LESSON = 160;
    public static final int MAX_WISH = 160;
    public static final int MAX_AWAY = 8;

    public static final FreeTime OFF =
            new FreeTime(false, Origin.COMMAND, "", 0, null, false, 1, List.of(), List.of(), Map.of(), List.of());

    /** Why free time began. */
    public enum Origin {
        COMMAND,
        IDLE
    }

    public FreeTime {
        origin = origin == null ? Origin.COMMAND : origin;
        wish = cut(wish == null ? "" : wish.strip(), MAX_WISH);
        nextShiftId = Math.max(1, nextShiftId);
        history = history == null ? List.of() : List.copyOf(history.subList(0, Math.min(history.size(), MAX_HISTORY)));
        lessons = lessons == null ? List.of() : lessons.stream()
                .skip(Math.max(0, lessons.size() - MAX_LESSONS))
                .map(lesson -> cut(lesson, MAX_LESSON))
                .toList();
        cooldownUntil = cooldownUntil == null || cooldownUntil.isEmpty()
                ? Map.of()
                : Map.copyOf(new EnumMap<>(cooldownUntil));
        away = away == null ? List.of() : List.copyOf(away.subList(Math.max(0, away.size() - MAX_AWAY), away.size()));
    }

    static String cut(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max);
    }
}
