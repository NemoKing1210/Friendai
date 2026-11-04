package dev.friendai.agent.llm.stream;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Turns a byte stream into Server-Sent Events frames. Bytes are accumulated and split on line breaks before decoding, so
 * a multi-byte character split across two HTTP chunks is not corrupted; a frame is dispatched on the blank line that
 * ends it, with its {@code event} name (default {@code message}) and its {@code data} lines joined by newlines.
 * Comments and the {@code id}/{@code retry} fields are ignored.
 */
public final class SseFrames {
    /** One complete event: its name and its data, exactly as the provider sent them. */
    public record Frame(String event, String data) {
        public Frame {
            event = event == null || event.isBlank() ? "message" : event;
            data = data == null ? "" : data;
        }
    }

    private byte[] buffer = new byte[1024];
    private int length;
    private String event;
    private final StringBuilder data = new StringBuilder();
    private boolean hasData;

    /** Adds a chunk of bytes and returns the frames it completed. */
    public List<Frame> feed(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return List.of();
        }
        append(bytes);
        return drain(false);
    }

    /** Ends the stream: a last line without its blank line still counts as a frame. */
    public List<Frame> finish() {
        List<Frame> frames = drain(true);
        return frames;
    }

    private List<Frame> drain(boolean end) {
        List<Frame> frames = new ArrayList<>();
        int start = 0;
        for (int index = 0; index < length; index++) {
            if (buffer[index] != '\n') {
                continue;
            }
            int to = index;
            if (to > start && buffer[to - 1] == '\r') {
                to--;
            }
            accept(new String(buffer, start, to - start, StandardCharsets.UTF_8), frames);
            start = index + 1;
        }
        if (start > 0) {
            buffer = Arrays.copyOfRange(buffer, start, length);
            length = buffer.length;
        }
        if (end && length > 0) {
            int to = length;
            if (to > 0 && buffer[to - 1] == '\r') {
                to--;
            }
            accept(new String(buffer, 0, to, StandardCharsets.UTF_8), frames);
            length = 0;
        }
        if (end) {
            dispatch(frames);
        }
        return frames;
    }

    private void accept(String line, List<Frame> frames) {
        if (line.isEmpty()) {
            dispatch(frames);
            return;
        }
        if (line.startsWith(":")) {
            return; // heartbeat or comment
        }
        int colon = line.indexOf(':');
        String field = colon < 0 ? line : line.substring(0, colon);
        String value = colon < 0 ? "" : line.substring(colon + 1);
        if (value.startsWith(" ")) {
            value = value.substring(1);
        }
        switch (field) {
            case "event" -> event = value;
            case "data" -> {
                if (hasData) {
                    data.append('\n');
                }
                data.append(value);
                hasData = true;
            }
            default -> {
                // id/retry and unknown fields carry nothing this client needs.
            }
        }
    }

    private void dispatch(List<Frame> frames) {
        if (!hasData && event == null) {
            return;
        }
        frames.add(new Frame(event, data.toString()));
        event = null;
        data.setLength(0);
        hasData = false;
    }

    private void append(byte[] bytes) {
        if (length + bytes.length > buffer.length) {
            buffer = Arrays.copyOf(buffer, Math.max(buffer.length * 2, length + bytes.length));
        }
        System.arraycopy(bytes, 0, buffer, length, bytes.length);
        length += bytes.length;
    }
}
