package dev.friendai.client.ui;

import dev.friendai.debug.CompanionSnapshot;
import dev.friendai.debug.LogBuffer;
import dev.friendai.memory.MemoryView;
import java.util.Optional;

/** What the companion menu knows: the latest snapshot, the accumulated log and the last fetched prompt. */
public final class CompanionClientState {
    private static final long STALE_AFTER_MS = 3000;
    private static final LogBuffer LOG = new LogBuffer(500);

    private static volatile CompanionSnapshot snapshot;
    private static volatile long receivedAt;
    private static volatile String prompt = "";
    private static volatile MemoryView memoryView;

    private CompanionClientState() {}

    public static void onSnapshot(CompanionSnapshot received) {
        LOG.append(received.newLog());
        snapshot = received;
        receivedAt = System.currentTimeMillis();
    }

    public static void onPrompt(String received) {
        prompt = received;
    }

    public static void onMemoryView(MemoryView received) {
        memoryView = received;
    }

    public static Optional<CompanionSnapshot> snapshot() {
        return Optional.ofNullable(snapshot);
    }

    public static String prompt() {
        return prompt;
    }

    public static Optional<MemoryView> memoryView() {
        return Optional.ofNullable(memoryView);
    }

    public static LogBuffer log() {
        return LOG;
    }

    /** True when nothing has arrived recently, e.g. the server stopped answering. */
    public static boolean isStale() {
        return snapshot == null || System.currentTimeMillis() - receivedAt > STALE_AFTER_MS;
    }

    public static void reset() {
        snapshot = null;
        prompt = "";
        memoryView = null;
        LOG.clear();
    }
}
