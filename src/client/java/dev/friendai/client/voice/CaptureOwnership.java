package dev.friendai.client.voice;

/**
 * Which capture a microphone stop belongs to. A capture is identified by a token handed out at start; only the token
 * that is current may stop the line, so a late stop from an older operation cannot cut a newer recording short.
 */
final class CaptureOwnership {
    private long next;
    private long current;

    long begin() {
        current = ++next;
        return current;
    }

    boolean owns(long token) {
        return token != 0 && token == current;
    }

    /** Ends the capture only when the token still owns it. */
    void end(long token) {
        if (owns(token)) {
            current = 0;
        }
    }

    long current() {
        return current;
    }
}
