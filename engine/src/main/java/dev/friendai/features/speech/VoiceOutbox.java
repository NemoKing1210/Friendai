package dev.friendai.features.speech;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The order in which completed recordings are sent. A recording takes a place when it ends, and a place keeps that
 * order even when recognition finishes out of order, so two commands spoken one after the other are never reordered or
 * silently dropped because a newer recording started. Only the oldest place leaves, and only after the server answered
 * the previous one, which keeps the server's one-per-second voice gate from refusing a burst.
 *
 * <p>Pure: it owns no clock and no game type. The caller passes the time in milliseconds, drives the state machine with
 * {@link #step} and tells it what happened with {@link #release}, {@link #cancel} and {@link #dropSession}.
 */
public final class VoiceOutbox {
    /** How many completed recordings may wait: one is being sent, the others wait in recording order. */
    public static final int MAX_PENDING = 3;

    /** Recognition may take this long before its place is given up; matches the recognition HTTP budget. */
    public static final long RECOGNITION_TIMEOUT_MILLIS = 60_000;

    /** Longer than the server's one-per-second voice gate, so an older server is passed instead of stalling. */
    public static final long ADMISSION_TIMEOUT_MILLIS = 3_000;

    public enum Status {
        /** The recording ended; recognition has not reported yet, and nothing behind it may leave. */
        RECOGNIZING,
        /** The text is known and this place may leave as soon as it is the oldest. */
        READY,
        /** This place is the oldest and was handed to the caller to send. */
        SENDING
    }

    /** Why a place left the outbox without being sent. */
    public enum Abandon {
        /** Recognition never reported within its budget. */
        RECOGNITION_TIMEOUT,
        /** The server did not answer in time; the line is not retried, so it can never run twice. */
        ADMISSION_TIMEOUT,
        /** The connection this recording belongs to is no longer the live one. */
        SESSION_CHANGED
    }

    /** One completed recording and its place in the order. The reference is the caller's token for that place. */
    public static final class Entry {
        private final long id;
        private final Object session;
        private final String tag;
        private final long reservedAt;
        private Status status = Status.RECOGNIZING;
        private String text;
        private long admissionDeadline;

        private Entry(long id, Object session, String tag, long reservedAt) {
            this.id = id;
            this.session = session;
            this.tag = tag;
            this.reservedAt = reservedAt;
        }

        public long id() {
            return id;
        }

        public Object session() {
            return session;
        }

        /** The caller's own label for the input channel; the outbox never interprets it. */
        public String tag() {
            return tag;
        }

        public Status status() {
            return status;
        }

        /** The recognized text, or null until recognition reports and after a cancelled place is gone. */
        public String text() {
            return text;
        }

        @Override
        public String toString() {
            return "Entry[" + id + ", " + status + ", " + tag + "]";
        }
    }

    /** A place that left without being sent, with the reason. */
    public record Abandoned(Entry entry, Abandon reason) {}

    /** What the caller should do on this step. */
    public sealed interface Step {
        /** Nothing to do; the next timeout is due at {@code deadlineMillis} (or never, when it is {@link Long#MAX_VALUE}). */
        record Idle(long deadlineMillis) implements Step {}

        /** Send this place's text as one speech request, then call {@link #release} when it is settled. */
        record Send(Entry entry) implements Step {}

        /** These places are gone; the caller reports the ones the owner can act on and steps again. */
        record Abandoned(List<VoiceOutbox.Abandoned> entries) implements Step {}
    }

    private final List<Entry> entries = new ArrayList<>();
    private long nextId;
    private Entry sending;

    /**
     * Takes a place for a recording that just ended. Empty when {@link #MAX_PENDING} recordings already wait, so the
     * caller can refuse the new line with a visible message instead of losing it.
     */
    public Optional<Entry> reserve(Object session, String tag, long nowMillis) {
        if (entries.size() >= MAX_PENDING) {
            return Optional.empty();
        }
        Entry entry = new Entry(nextId++, session, tag, nowMillis);
        entries.add(entry);
        return Optional.of(entry);
    }

    /** Recognition reported this text; false when the place is already gone (cancelled or dropped). */
    public boolean settle(Entry entry, String text) {
        if (!entries.contains(entry) || entry.status != Status.RECOGNIZING) {
            return false;
        }
        entry.status = Status.READY;
        entry.text = text;
        return true;
    }

    /** The recording failed or was deliberately cancelled: its place leaves without sending anything. */
    public void cancel(Entry entry) {
        release(entry);
    }

    /** The place was sent and settled by the server, or refused, or failed to send: it leaves and is never retried. */
    public void release(Entry entry) {
        entries.remove(entry);
        if (sending == entry) {
            sending = null;
        }
    }

    /** Drops every place recorded on another connection, for a disconnect. */
    public List<Abandoned> dropSession(Object session) {
        List<Abandoned> dropped = new ArrayList<>();
        for (Entry entry : List.copyOf(entries)) {
            if (!entry.session.equals(session)) {
                release(entry);
                dropped.add(new Abandoned(entry, Abandon.SESSION_CHANGED));
            }
        }
        return dropped;
    }

    /** Drops everything, for a disconnect or a settings change that invalidates the recognizer. */
    public List<Abandoned> dropAll() {
        List<Abandoned> dropped = new ArrayList<>();
        for (Entry entry : entries) {
            dropped.add(new Abandoned(entry, Abandon.SESSION_CHANGED));
        }
        entries.clear();
        sending = null;
        return dropped;
    }

    /**
     * Advances the order by one step: give up places past their budget, then hand out the oldest completed place. Call
     * it again after a {@link Step.Abandoned}.
     */
    public Step step(long nowMillis) {
        List<Abandoned> expired = new ArrayList<>();
        for (Entry entry : List.copyOf(entries)) {
            if (entry.status == Status.RECOGNIZING && nowMillis - entry.reservedAt >= RECOGNITION_TIMEOUT_MILLIS) {
                release(entry);
                expired.add(new Abandoned(entry, Abandon.RECOGNITION_TIMEOUT));
            } else if (entry == sending && nowMillis >= entry.admissionDeadline) {
                release(entry);
                expired.add(new Abandoned(entry, Abandon.ADMISSION_TIMEOUT));
            }
        }
        if (!expired.isEmpty()) {
            return new Step.Abandoned(List.copyOf(expired));
        }
        if (sending != null) {
            return new Step.Idle(sending.admissionDeadline);
        }
        if (entries.isEmpty()) {
            return new Step.Idle(Long.MAX_VALUE);
        }
        Entry head = entries.get(0);
        return switch (head.status) {
            case RECOGNIZING -> new Step.Idle(head.reservedAt + RECOGNITION_TIMEOUT_MILLIS);
            case SENDING -> new Step.Idle(head.admissionDeadline);
            case READY -> {
                head.status = Status.SENDING;
                head.admissionDeadline = nowMillis + ADMISSION_TIMEOUT_MILLIS;
                sending = head;
                yield new Step.Send(head);
            }
        };
    }

    /** Number of places waiting, including the one being sent. */
    public int size() {
        return entries.size();
    }

    /** The place handed to the caller and not yet released. */
    public Optional<Entry> sending() {
        return Optional.ofNullable(sending);
    }
}
