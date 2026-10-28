package dev.friendai.server.execution;

import java.util.IdentityHashMap;
import java.util.Map;

/** Tracks scoped execution holds for one companion. */
public final class HoldRegistry {
    private final Map<Token, HoldReason> active = new IdentityHashMap<>();

    public ExecutionHold acquire(HoldReason reason) {
        if (reason == null) throw new IllegalArgumentException("hold reason is required");
        Token token = new Token();
        active.put(token, reason);
        return token;
    }

    public boolean held() {
        return !active.isEmpty();
    }

    public int activeCount() {
        return active.size();
    }

    public void closeAll() {
        for (Token token : active.keySet().toArray(Token[]::new)) token.close();
    }

    private final class Token implements ExecutionHold {
        private boolean closed;

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            active.remove(this);
        }
    }
}
