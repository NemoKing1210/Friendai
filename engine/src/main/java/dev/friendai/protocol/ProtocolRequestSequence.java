package dev.friendai.protocol;

public final class ProtocolRequestSequence {
    private long lastIssued;
    private long lastIncoming;

    public synchronized long nextRequestId() {
        if (lastIssued == Long.MAX_VALUE) throw new IllegalStateException("protocol request ids are exhausted");
        return ++lastIssued;
    }

    public synchronized boolean acceptIncoming(long requestId) {
        if (requestId <= lastIncoming || requestId <= 0) return false;
        lastIncoming = requestId;
        return true;
    }
}
