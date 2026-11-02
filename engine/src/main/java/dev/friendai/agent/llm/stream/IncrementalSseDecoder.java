package dev.friendai.agent.llm.stream;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Incremental server-sent-events decoder. Bytes may arrive split at any boundary, including inside a UTF-8 sequence,
 * because a line is decoded only after its terminator. The decoder owns no connection and no provider semantics:
 * OpenAI/Anthropic/Gemini payloads and the {@code [DONE]} sentinel are interpreted by the caller.
 *
 * <p>Bounds: one line is at most {@link #MAX_LINE_BYTES}, one event's joined data is at most
 * {@link #MAX_EVENT_CHARS}; exceeding either marks the decoder failed and later feeds return nothing. A blank line
 * dispatches an event; {@code finish} dispatches the last unterminated event at end of stream; {@code close}
 * cancels and drops anything buffered.
 */
public final class IncrementalSseDecoder {
    public static final int MAX_LINE_BYTES = 64 * 1024;
    public static final int MAX_EVENT_CHARS = 1_048_576;

    private final ByteArrayOutputStream line = new ByteArrayOutputStream();
    private final StringBuilder data = new StringBuilder();
    private String event = "";
    private boolean finished;
    private boolean failed;
    private boolean afterCarriageReturn;

    /** Feeds one chunk and returns every event completed by it, in order. */
    public List<StreamEvent> feed(byte[] chunk) {
        if (chunk == null || chunk.length == 0 || finished || failed) {
            return List.of();
        }
        List<StreamEvent> events = new ArrayList<>();
        for (byte value : chunk) {
            if (failed) {
                break;
            }
            if (value == '\n') {
                if (afterCarriageReturn) {
                    afterCarriageReturn = false;
                    continue;
                }
                endLine(events);
            } else if (value == '\r') {
                endLine(events);
                afterCarriageReturn = true;
            } else {
                afterCarriageReturn = false;
                line.write(value);
                if (line.size() > MAX_LINE_BYTES) {
                    fail();
                }
            }
        }
        return events;
    }

    /** Flushes a final unterminated line/event and closes the stream for feeding. */
    public List<StreamEvent> finish() {
        if (finished || failed) {
            return List.of();
        }
        List<StreamEvent> events = new ArrayList<>();
        if (line.size() > 0) {
            endLine(events);
        }
        dispatch(events);
        finished = true;
        return events;
    }

    /** Cancels: buffered data is dropped and later feeds return nothing. */
    public void close() {
        finished = true;
        line.reset();
        data.setLength(0);
        event = "";
    }

    public boolean failed() {
        return failed;
    }

    public boolean finished() {
        return finished;
    }

    private void endLine(List<StreamEvent> events) {
        String text = line.toString(StandardCharsets.UTF_8);
        line.reset();
        if (text.isEmpty()) {
            dispatch(events);
            return;
        }
        if (text.startsWith(":")) {
            return;
        }
        int colon = text.indexOf(':');
        String field = colon < 0 ? text : text.substring(0, colon);
        String value = colon < 0 ? "" : text.substring(colon + 1);
        if (value.startsWith(" ")) {
            value = value.substring(1);
        }
        if ("data".equals(field)) {
            data.append(value).append('\n');
            if (data.length() > MAX_EVENT_CHARS) {
                fail();
            }
        } else if ("event".equals(field)) {
            event = value.length() > 128 ? value.substring(0, 128) : value;
        }
        // id, retry and unknown fields carry no text for this layer.
    }

    private void dispatch(List<StreamEvent> events) {
        if (failed) {
            return;
        }
        if (data.length() > 0) {
            data.setLength(data.length() - 1);
        }
        if (data.length() > 0 || !event.isEmpty()) {
            events.add(new StreamEvent(event, data.toString()));
        }
        data.setLength(0);
        event = "";
    }

    private void fail() {
        failed = true;
        line.reset();
        data.setLength(0);
        event = "";
    }
}
