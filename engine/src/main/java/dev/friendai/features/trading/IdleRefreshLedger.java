package dev.friendai.features.trading;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Remembers when the idle offer refresh last went to each trader of one owner. The refresh runs whenever the
 * companion has nothing to do, so without this a trader it cannot reach, or one it inspected a moment ago, is visited
 * again every refresh interval for as long as the companion stands near it. A trader becomes due again only after
 * {@link #RETRY_TICKS}, whatever the last visit's outcome; the ledger never holds more than {@link #MAX_TRADERS}.
 */
public final class IdleRefreshLedger {
    /** Five minutes of game time: offers do not change faster than that, and a blocked path rarely clears sooner. */
    public static final long RETRY_TICKS = 6_000;

    public static final int MAX_TRADERS = 64;

    private final Map<UUID, Long> attempts = new LinkedHashMap<>();

    /** Whether the idle refresh may go to this trader now. A clock that moved backwards makes every trader due. */
    public boolean due(UUID trader, long now) {
        Long last = attempts.get(trader);
        return last == null || expired(last, now);
    }

    /** Records that the idle refresh went to this trader now, and drops entries that are due again. */
    public void attempted(UUID trader, long now) {
        attempts.remove(trader);
        attempts.put(trader, now);
        prune(now);
    }

    /** Drops every entry that is due again and keeps at most {@link #MAX_TRADERS}, oldest first out. */
    public void prune(long now) {
        attempts.values().removeIf(last -> expired(last, now));
        Iterator<UUID> oldest = attempts.keySet().iterator();
        while (attempts.size() > MAX_TRADERS && oldest.hasNext()) {
            oldest.next();
            oldest.remove();
        }
    }

    public int size() {
        return attempts.size();
    }

    private static boolean expired(long last, long now) {
        return now < last || now - last >= RETRY_TICKS;
    }
}
