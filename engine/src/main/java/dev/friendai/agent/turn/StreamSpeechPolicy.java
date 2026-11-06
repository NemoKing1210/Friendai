package dev.friendai.agent.turn;

import java.util.regex.Pattern;

/**
 * Decides whether part of a streamed reply may be spoken before the whole response has arrived.
 *
 * <p>The full-response {@link ReplyGuard} result stays authoritative: the caller keeps the raw reply buffered and
 * speaks only what this policy releases, and it releases a fragment only while the caller's {@code parity} flag
 * confirms that the guard's whole-text decision for that fragment equals the fragment itself.
 *
 * <p>Three whole-text effects make an early decision unsound, so such a fragment is held instead of spoken. The
 * repeat check compares the whole reply, which one fragment cannot represent. The guard's sanitizer patterns use
 * {@code \s}, which also matches a line break, so a fragment that ends at a line break or inside a digit or
 * coordinate run may still be sanitized differently once the text that follows it is known. Fenced code and markdown
 * are stripped over the whole text, so an unterminated fence may still turn out to be code, and a colon nearly always
 * introduces the rest of the sentence.
 *
 * <p>A held fragment is prepended to the next one, so no word is dropped and a number split across two fragments is
 * rejoined without a space. Once a fragment has been released, and once the caller reports the end of the stream,
 * nothing is released again; text the caller re-offers because it grew its own buffer is recognized and spoken once.
 *
 * <p>Pure: no Minecraft types, no IO, and it never throws.
 */
public final class StreamSpeechPolicy {
    private static final String FENCE = "```";
    private static final Pattern ENDS_INSIDE_NUMBER = Pattern.compile(".*\\d[ ,;+\\-]*\\z", Pattern.DOTALL);
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    /** What the caller may do with the text it offered. */
    public sealed interface Decision permits Speak, Hold, StopIncomplete {}

    /**
     * Speak this text now. {@code last} marks the final utterance of the reply; an empty {@code text} with
     * {@code last} set is the end marker for a reply whose text was already released.
     */
    public record Speak(String text, boolean last) implements Decision {
        public Speak {
            text = text == null ? "" : text;
        }
    }

    /** Nothing is safe to release yet; the text stays buffered and is prepended to the next fragment. */
    public record Hold() implements Decision {}

    /**
     * Early speech cannot be reconciled with the authoritative guard: release nothing more this turn and mark the
     * delivered prefix incomplete, so the full-response guard decides the rest.
     */
    public record StopIncomplete() implements Decision {}

    private final StringBuilder pending = new StringBuilder();
    private final StringBuilder spoken = new StringBuilder();
    private boolean pendingUnverified;
    private boolean closed;

    private StreamSpeechPolicy() {}

    /** One policy per streamed reply. */
    public static StreamSpeechPolicy create() {
        return new StreamSpeechPolicy();
    }

    /**
     * Offers a completed sentence fragment that is not the end of the reply.
     *
     * @param fragment the new text, in reply order; text the caller already offered may be re-offered when its buffer
     *                 grew, and it is never appended or spoken twice
     * @param parity   true when the guard's whole-text decision for this fragment equals the fragment itself
     */
    public Decision offer(String fragment, boolean parity) {
        if (closed) {
            return new Hold();
        }
        merge(fragment, parity);
        if (!parity) {
            return new Hold();
        }
        if (pendingUnverified) {
            return stop();
        }
        String raw = pending.toString();
        if (raw.isBlank() || !safeMidStream(raw)) {
            return new Hold();
        }
        String text = fresh(raw);
        if (text.isBlank()) {
            return new Hold();
        }
        return speak(text, false);
    }

    /**
     * Offers the text left when the stream ends, which may be empty. The released text is marked last exactly once;
     * afterwards the policy holds everything, so a repeated call cannot release the same text again.
     *
     * @param fragment the remaining text, in reply order
     * @param parity   true when the guard's whole-text decision for this fragment equals the fragment itself
     */
    public Decision finish(String fragment, boolean parity) {
        if (closed) {
            return new Hold();
        }
        merge(fragment, parity);
        if (!parity || pendingUnverified) {
            return stop();
        }
        String raw = pending.toString();
        if (raw.isBlank()) {
            closed = true;
            return new Speak("", true);
        }
        if (!safeFinal(raw)) {
            return stop();
        }
        String text = fresh(raw);
        if (text.isBlank()) {
            closed = true;
            return new Speak("", true);
        }
        return speak(text, true);
    }

    private void merge(String fragment, boolean parity) {
        String raw = fragment == null ? "" : fragment;
        if (raw.isBlank()) {
            return;
        }
        String held = pending.toString().strip();
        String trimmed = raw.strip();
        if (!held.isEmpty() && trimmed.startsWith(held)) {
            // the caller re-offered the buffered text: replace it instead of appending it twice
            pending.setLength(0);
            pending.append(raw);
            pendingUnverified = !parity;
            return;
        }
        appendFragment(pending, raw);
        if (!parity) {
            pendingUnverified = true;
        }
    }

    private static void appendFragment(StringBuilder out, String fragment) {
        if (out.length() > 0
                && !Character.isWhitespace(out.charAt(out.length() - 1))
                && !Character.isWhitespace(fragment.charAt(0))
                && !continuesNumberRun(out)) {
            out.append(' ');
        }
        out.append(fragment);
    }

    /** True when the buffer ends inside a number run, so the next fragment continues it without a separator. */
    private static boolean continuesNumberRun(CharSequence text) {
        if (ENDS_INSIDE_NUMBER.matcher(text).matches()) {
            return true;
        }
        char last = text.charAt(text.length() - 1);
        return last == '=' || last == '-';
    }

    /** The text still owed to the player, with every released prefix removed. */
    private String fresh(String raw) {
        String trimmed = raw.strip();
        String said = spoken.toString().strip();
        if (said.isEmpty() || !trimmed.startsWith(said)) {
            return trimmed;
        }
        return trimmed.substring(said.length()).strip();
    }

    private Decision speak(String candidate, boolean last) {
        String text = WHITESPACE.matcher(candidate.strip()).replaceAll(" ");
        pending.setLength(0);
        pendingUnverified = false;
        if (last) {
            closed = true;
        }
        spoken.append(text).append(' ');
        return new Speak(text, last);
    }

    private Decision stop() {
        closed = true;
        pending.setLength(0);
        pendingUnverified = false;
        return new StopIncomplete();
    }

    /** Mid-stream a fragment must end a sentence, so neither a line break nor a colon may follow it. */
    private static boolean safeMidStream(String text) {
        if (text.isBlank() || endsAtLineBreak(text) || endsInsideNumber(text) || hasOpenFence(text)) {
            return false;
        }
        String trimmed = text.strip();
        char end = trimmed.charAt(trimmed.length() - 1);
        return end == '.' || end == '!' || end == '?' || end == '。' || end == '！' || end == '？';
    }

    /** At the end of the stream only text that would still be sanitized differently, or read as code, is refused. */
    private static boolean safeFinal(String text) {
        return !text.isBlank() && !endsInsideNumber(text) && !hasOpenFence(text);
    }

    /**
     * True when the last non-whitespace character is preceded by a line break, i.e. the fragment ends at the boundary
     * of a line that the reply may continue.
     */
    private static boolean endsAtLineBreak(String text) {
        for (int i = text.length() - 1; i >= 0; i--) {
            char c = text.charAt(i);
            if (c == '\n' || c == '\r') {
                return true;
            }
            if (!Character.isWhitespace(c)) {
                return false;
            }
        }
        return false;
    }

    private static boolean endsInsideNumber(String text) {
        return ENDS_INSIDE_NUMBER.matcher(text).matches();
    }

    /** An odd fence count means the fragment opened a fenced block that the reply has not closed yet. */
    private static boolean hasOpenFence(String text) {
        int fences = 0;
        for (int i = text.indexOf(FENCE); i >= 0; i = text.indexOf(FENCE, i + FENCE.length())) {
            fences++;
        }
        return fences % 2 == 1;
    }
}
