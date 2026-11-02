package dev.friendai.agent.llm.stream;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Ordered, session-scoped speech delivery for streamed replies. A stream belongs to one client session; a sentence is
 * spoken only once and only after every earlier sentence of that stream. Out-of-order sentences are buffered up to a
 * bound; a stale session, a gap beyond the bound and a duplicate are rejected. Invalidation (stop, new message,
 * disconnect) drops every buffered sentence and marks open streams incomplete rather than replaying them.
 */
public final class SpeechStreamBuffer {
    public static final int MAX_STREAMS = 16;
    public static final int MAX_PENDING = 32;
    public static final int MAX_TEXT = 1_000;

    public enum Status {
        DELIVERED,
        BUFFERED,
        DUPLICATE,
        STALE_SESSION,
        UNKNOWN_STREAM
    }

    public record Sentence(long streamId, long sessionId, long sequence, String text, boolean last) {
        public Sentence {
            if (streamId < 1 || sessionId < 1 || sequence < 0) throw new IllegalArgumentException("identity");
            text = text == null ? "" : text;
            if (text.length() > MAX_TEXT) throw new IllegalArgumentException("text");
        }
    }

    public record Result(Status status, List<Sentence> ready, boolean incomplete) {
        public Result {
            ready = List.copyOf(ready);
        }
    }

    private static final class Stream {
        long next = 0;
        final Map<Long, Sentence> pending = new HashMap<>();
        boolean complete;
        long lastSequence = -1;
    }

    private final Map<Long, Stream> streams = new HashMap<>();
    private long sessionId = -1;

    /** Starts a new client session; any buffered stream from the previous one is gone. */
    public void beginSession(long sessionId) {
        this.sessionId = sessionId;
        streams.clear();
    }

    public long sessionId() {
        return sessionId;
    }

    /** Rejects every later sentence from the old session and drops buffered text. */
    public void invalidate() {
        sessionId = -1;
        streams.clear();
    }

    /** Accepts one sentence, releasing as many ordered sentences as the buffer now allows. */
    public Result offer(Sentence sentence) {
        if (sentence == null) return new Result(Status.DUPLICATE, List.of(), false);
        if (sentence.sessionId() != sessionId) {
            return new Result(Status.STALE_SESSION, List.of(), false);
        }
        Stream stream = streams.get(sentence.streamId());
        if (stream == null) {
            if (streams.size() >= MAX_STREAMS) {
                return new Result(Status.UNKNOWN_STREAM, List.of(), false);
            }
            stream = new Stream();
            streams.put(sentence.streamId(), stream);
        }
        if (sentence.sequence() <= stream.lastSequence) {
            return new Result(Status.DUPLICATE, List.of(), streamIncomplete(stream));
        }
        if (sentence.sequence() > stream.next + MAX_PENDING) {
            return new Result(Status.UNKNOWN_STREAM, List.of(), streamIncomplete(stream));
        }
        stream.pending.putIfAbsent(sentence.sequence(), sentence);
        List<Sentence> ready = new ArrayList<>();
        while (true) {
            Sentence next = stream.pending.remove(stream.next);
            if (next == null) break;
            ready.add(next);
            stream.lastSequence = stream.next;
            stream.next++;
            if (next.last()) stream.complete = true;
        }
        if (stream.complete) stream.pending.clear();
        return new Result(ready.isEmpty() ? Status.BUFFERED : Status.DELIVERED, ready, streamIncomplete(stream));
    }

    /** True when a stream was opened but its final sentence has not been delivered. */
    public boolean incomplete(long streamId) {
        Stream stream = streams.get(streamId);
        return stream != null && streamIncomplete(stream);
    }

    public boolean known(long streamId) {
        return streams.containsKey(streamId);
    }

    public Optional<Long> nextSequence(long streamId) {
        Stream stream = streams.get(streamId);
        return stream == null ? Optional.empty() : Optional.of(stream.next);
    }

    public int buffered(long streamId) {
        Stream stream = streams.get(streamId);
        return stream == null ? 0 : stream.pending.size();
    }

    private static boolean streamIncomplete(Stream stream) {
        return !stream.complete;
    }
}
