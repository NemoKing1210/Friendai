package dev.friendai.features.social;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * A small, owner-scoped journal of facts the companion may mention. The key dedupes an updated fact in place, the
 * text is cut to a bounded length, and the oldest entry leaves when the cap is reached.
 */
public final class HappeningJournal {
    public static final int MAX_ENTRIES = 12;
    public static final int MAX_TEXT = 160;

    private final ArrayDeque<Happening> entries = new ArrayDeque<>();

    public void add(Happening happening) {
        entries.removeIf(entry -> entry.key().equals(happening.key()));
        String text = happening.text().length() > MAX_TEXT ? happening.text().substring(0, MAX_TEXT) : happening.text();
        Happening bounded =
                new Happening(happening.kind(), happening.importance(), happening.tick(), happening.key(), text);
        entries.addFirst(bounded);
        while (entries.size() > MAX_ENTRIES) {
            entries.removeLast();
        }
    }

    /** Newest first. */
    public List<Happening> recent(int limit) {
        int count = Math.max(0, Math.min(limit, entries.size()));
        List<Happening> result = new ArrayList<>(count);
        int i = 0;
        for (Happening entry : entries) {
            if (i++ >= count) {
                break;
            }
            result.add(entry);
        }
        return result;
    }

    public int size() {
        return entries.size();
    }

    public boolean contains(String key) {
        return entries.stream().anyMatch(entry -> entry.key().equals(key));
    }
}
