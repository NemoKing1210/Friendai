package dev.friendai.client.voice;

import dev.friendai.client.ui.CompanionRequests;
import dev.friendai.features.speech.VoiceTranscript;
import dev.friendai.foundation.session.ClientSessionId;
import dev.friendai.foundation.text.UserMessage;
import dev.friendai.protocol.speech.SpeechAcknowledgement;
import dev.friendai.protocol.speech.SpeechRequest;

/**
 * The one place a recognized line leaves the client. It classifies the text, sends it and reports what happened, then
 * applies the server's correlated acknowledgement for the outcomes only the server can decide. An unsent command is
 * never shown as accepted, and a silent HUD never means "ignored".
 */
public final class VoiceSubmission {
    /** What the client knows about a recognized line after handing it to the connection. */
    public enum Outcome {
        SENT,
        EMPTY,
        TOO_LONG,
        NOT_CONNECTED
    }

    /** Which listener produced the line; the wake word keeps its listening indicator, push-to-talk has none. */
    public enum Origin {
        PUSH_TO_TALK,
        WAKE_WORD
    }

    /** The seam both voice handlers submit through, so a test can watch the text without a network. */
    @FunctionalInterface
    public interface Sink {
        Outcome submit(ClientSessionId session, String text, Origin origin);
    }

    private record Pending(long requestId, Origin origin) {}

    private static volatile Pending pending;

    private VoiceSubmission() {}

    /** Classifies and sends one recognized line; never truncates it at the wire limit. */
    public static Outcome submit(ClientSessionId session, String text, Origin origin) {
        VoiceTranscript.Readiness readiness = VoiceTranscript.classify(text);
        if (readiness == VoiceTranscript.Readiness.EMPTY) {
            pending = null;
            return Outcome.EMPTY;
        }
        if (readiness == VoiceTranscript.Readiness.TOO_LONG) {
            pending = null;
            return Outcome.TOO_LONG;
        }
        if (!CompanionRequests.speechSupported(session)) {
            pending = null;
            return Outcome.NOT_CONNECTED;
        }
        // A refused frame (invalid UTF-16 or over the wire limit) comes back as -1, never as an accepted command.
        long requestId = CompanionRequests.send(session, new SpeechRequest.Transcript(VoiceTranscript.normalize(text)));
        if (requestId <= 0) {
            pending = null;
            return Outcome.NOT_CONNECTED;
        }
        pending = new Pending(requestId, origin);
        return Outcome.SENT;
    }

    /** Shows the outcome on the HUD; a wake-word acceptance or an empty clip stays on the listening indicator. */
    public static void show(Outcome outcome, Origin origin) {
        boolean quietWake = origin == Origin.WAKE_WORD;
        switch (outcome) {
            case SENT -> {
                if (!quietWake) {
                    VoiceState.set(VoiceState.Kind.SENT, null);
                }
            }
            case EMPTY -> {
                if (!quietWake) {
                    error("friendai.voice.error.empty");
                }
            }
            case TOO_LONG -> error("friendai.voice.error.too_long");
            case NOT_CONNECTED -> error("friendai.voice.error.not_sent");
        }
    }

    /** Applies the server's answer; a frame for an older or unknown submission changes nothing. */
    public static void onAcknowledgement(long requestId, SpeechAcknowledgement.Outcome outcome) {
        Pending waiting = pending;
        if (waiting == null || waiting.requestId() != requestId) {
            return;
        }
        pending = null;
        if (captureOwnsIndicator()) {
            // A newer recording owns the HUD; its own outcome will speak for this one too.
            return;
        }
        switch (outcome) {
            case ACCEPTED -> {
                if (waiting.origin() == Origin.PUSH_TO_TALK) {
                    VoiceState.set(VoiceState.Kind.SENT, null);
                }
            }
            case TOO_LONG -> error("friendai.voice.error.too_long");
            case RATE_LIMITED -> error("friendai.voice.error.rate_limited");
            case NO_COMPANION -> error("friendai.voice.need_companion");
            case TOO_FAR -> error("friendai.companion.too_far");
        }
    }

    private static boolean captureOwnsIndicator() {
        VoiceState.Kind kind = VoiceState.current().kind();
        return kind == VoiceState.Kind.RECORDING || kind == VoiceState.Kind.TRANSCRIBING;
    }

    private static void error(String key) {
        VoiceState.set(VoiceState.Kind.ERROR, UserMessage.of(key));
    }
}
