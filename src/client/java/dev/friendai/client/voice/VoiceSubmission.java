package dev.friendai.client.voice;

import dev.friendai.client.ui.CompanionRequests;
import dev.friendai.features.speech.VoiceOutbox;
import dev.friendai.features.speech.VoiceTranscript;
import dev.friendai.foundation.session.ClientSessionId;
import dev.friendai.foundation.text.UserMessage;
import dev.friendai.protocol.speech.SpeechAcknowledgement;
import dev.friendai.protocol.speech.SpeechRequest;

/**
 * The one place a recognized line leaves the client. Every finished recording takes a place in {@link VoiceOutbox} when
 * it ends, so a slow recognizer can no longer make two commands collapse into the later one: the places leave in the
 * order they were recorded, one at a time, each waiting for the server's answer. A line the client can decide on its
 * own (blank, over the wire limit, no connection, queue full) is settled at once with a visible message; everything
 * else is reported when the correlated acknowledgement arrives. A cancelled or disconnected recording sends nothing,
 * and a sent one is never retried.
 */
public final class VoiceSubmission {
    /** What the owner is told about one recording. */
    private enum Outcome {
        SENT,
        EMPTY,
        TOO_LONG,
        NOT_CONNECTED,
        QUEUE_FULL
    }

    /** Which listener produced the line; the wake word keeps its listening indicator, push-to-talk has none. */
    public enum Origin {
        PUSH_TO_TALK,
        WAKE_WORD
    }

    /** One recording's place in the queue. Exactly one settle call closes it; a later one changes nothing. */
    @FunctionalInterface
    public interface Reservation {
        /** The recognized text, or null when the recording failed or was deliberately cancelled. */
        void settle(String text);
    }

    /** The seam both voice handlers submit through, so a test can drive the whole path without a device. */
    @FunctionalInterface
    public interface Sink {
        /** Takes a place when a recording ends; null when it cannot be queued and the owner has been told. */
        Reservation reserve(ClientSessionId session, Origin origin);
    }

    /**
     * The server admits one voice line per player per second, so a queued line waits out that window instead of being
     * refused by the gate. Measured from the previous successful send, with room for network jitter.
     */
    private static final long VOICE_GATE_MILLIS = 1_500;

    private static final VoiceOutbox OUTBOX = new VoiceOutbox();
    private static final Object LOCK = new Object();
    private static long inFlightRequest = -1;
    private static VoiceOutbox.Entry inFlightEntry;
    private static long lastSentMillis;

    private VoiceSubmission() {}

    /** Takes a place for a finished recording, reporting a full queue rather than losing the line. */
    public static Reservation reserve(ClientSessionId session, Origin origin) {
        if (session == null) {
            show(Outcome.NOT_CONNECTED, origin);
            return null;
        }
        VoiceOutbox.Entry entry;
        synchronized (LOCK) {
            var reserved = OUTBOX.reserve(session, origin.name(), System.currentTimeMillis());
            if (reserved.isEmpty()) {
                show(Outcome.QUEUE_FULL, origin);
                return null;
            }
            entry = reserved.get();
        }
        return text -> settle(entry, session, origin, text);
    }

    /** One client tick: lets the oldest completed recording leave when the connection is ready for it. */
    public static void tick() {
        long now = System.currentTimeMillis();
        if (now - lastSentMillis < VOICE_GATE_MILLIS) {
            // The server would refuse this burst; the head keeps its place and leaves when the window is open.
            return;
        }
        while (true) {
            VoiceOutbox.Step step;
            synchronized (LOCK) {
                step = OUTBOX.step(now);
            }
            if (step instanceof VoiceOutbox.Step.Send send) {
                if (!send(send.entry())) {
                    return; // the head now waits for the server's answer
                }
            } else if (step instanceof VoiceOutbox.Step.Abandoned abandoned) {
                abandoned.entries().forEach(VoiceSubmission::report);
            } else {
                return;
            }
        }
    }

    /** Applies the server's answer to the head; a frame for anything else changes nothing. */
    public static void onAcknowledgement(long requestId, SpeechAcknowledgement.Outcome outcome) {
        VoiceOutbox.Entry answered;
        synchronized (LOCK) {
            if (inFlightRequest != requestId || inFlightEntry == null) {
                return;
            }
            answered = inFlightEntry;
            inFlightRequest = -1;
            inFlightEntry = null;
            OUTBOX.release(answered);
        }
        showAcknowledged(outcome, Origin.valueOf(answered.tag()));
        tick();
    }

    /** Drops every place recorded on a connection that has ended; its text is stale and must not be sent. */
    public static void onDisconnect(ClientSessionId ended) {
        if (ended == null) {
            return;
        }
        synchronized (LOCK) {
            OUTBOX.dropSession(ended);
            if (inFlightEntry != null && ended.equals(inFlightEntry.session())) {
                inFlightRequest = -1;
                inFlightEntry = null;
            }
            // The server forgets the voice gate for a closed connection, so the next one starts with a clean window.
            lastSentMillis = 0;
        }
    }

    /** Drops everything this client queued, for a settings change or a shutdown. */
    public static void reset() {
        synchronized (LOCK) {
            OUTBOX.dropAll();
            inFlightRequest = -1;
            inFlightEntry = null;
            lastSentMillis = 0;
        }
    }

    private static void settle(VoiceOutbox.Entry entry, ClientSessionId session, Origin origin, String text) {
        if (text == null) {
            // A failed or cancelled recording owes the owner nothing here; the handler already reported it.
            drop(entry);
            return;
        }
        if (!VoiceRuntime.isCurrentSession(session) || entry.status() == VoiceOutbox.Status.SENDING) {
            drop(entry);
            return;
        }
        VoiceTranscript.Readiness readiness = VoiceTranscript.classify(text);
        if (readiness != VoiceTranscript.Readiness.READY) {
            drop(entry);
            show(readiness == VoiceTranscript.Readiness.EMPTY ? Outcome.EMPTY : Outcome.TOO_LONG, origin);
            return;
        }
        synchronized (LOCK) {
            OUTBOX.settle(entry, VoiceTranscript.normalize(text));
        }
        tick();
    }

    private static void drop(VoiceOutbox.Entry entry) {
        synchronized (LOCK) {
            OUTBOX.cancel(entry);
        }
    }

    /** Sends one place; false when it now waits for the server's answer. */
    private static boolean send(VoiceOutbox.Entry entry) {
        ClientSessionId session = (ClientSessionId) entry.session();
        Origin origin = Origin.valueOf(entry.tag());
        if (!VoiceRuntime.isCurrentSession(session) || !CompanionRequests.speechSupported(session)) {
            release(entry, Outcome.NOT_CONNECTED, origin);
            return true;
        }
        long requestId = CompanionRequests.send(session, new SpeechRequest.Transcript(entry.text()));
        if (requestId <= 0) {
            release(entry, Outcome.NOT_CONNECTED, origin);
            return true;
        }
        synchronized (LOCK) {
            inFlightRequest = requestId;
            inFlightEntry = entry;
            lastSentMillis = System.currentTimeMillis();
        }
        show(Outcome.SENT, origin);
        return false;
    }

    private static void release(VoiceOutbox.Entry entry, Outcome outcome, Origin origin) {
        synchronized (LOCK) {
            OUTBOX.release(entry);
        }
        show(outcome, origin);
    }

    /** A place left the queue on its own: only a stuck recognizer is worth telling the owner about. */
    private static void report(VoiceOutbox.Abandoned abandoned) {
        synchronized (LOCK) {
            if (inFlightEntry == abandoned.entry()) {
                inFlightRequest = -1;
                inFlightEntry = null;
            }
        }
        if (abandoned.reason() == VoiceOutbox.Abandon.RECOGNITION_TIMEOUT) {
            showError("friendai.voice.error.failed");
        }
    }

    private static void showAcknowledged(SpeechAcknowledgement.Outcome outcome, Origin origin) {
        switch (outcome) {
            case ACCEPTED -> show(Outcome.SENT, origin);
            case TOO_LONG -> show(Outcome.TOO_LONG, origin);
            case RATE_LIMITED -> showError("friendai.voice.error.rate_limited");
            case NO_COMPANION -> showError("friendai.voice.need_companion");
            case TOO_FAR -> showError("friendai.companion.too_far");
        }
    }

    private static void show(Outcome outcome, Origin origin) {
        switch (outcome) {
            case SENT -> {
                if (origin != Origin.WAKE_WORD) {
                    set(VoiceState.Kind.SENT, null);
                }
            }
            case EMPTY -> {
                if (origin != Origin.WAKE_WORD) {
                    showError("friendai.voice.error.empty");
                }
            }
            case TOO_LONG -> showError("friendai.voice.error.too_long");
            case NOT_CONNECTED -> showError("friendai.voice.error.not_sent");
            case QUEUE_FULL -> showError("friendai.voice.error.queue_full");
        }
    }

    private static void showError(String key) {
        set(VoiceState.Kind.ERROR, UserMessage.of(key));
    }

    /** A newer recording or transcription owns the indicator; an older line must not replace it. */
    private static void set(VoiceState.Kind kind, UserMessage message) {
        VoiceState.Kind shown = VoiceState.current().kind();
        if (shown == VoiceState.Kind.RECORDING || shown == VoiceState.Kind.TRANSCRIBING) {
            return;
        }
        VoiceState.set(kind, message);
    }
}
