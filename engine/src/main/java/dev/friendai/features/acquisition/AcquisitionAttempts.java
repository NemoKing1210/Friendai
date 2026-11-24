package dev.friendai.features.acquisition;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Bounded memory of material sources an acquisition already tried and failed to reach. It is a cost signal, not a
 * ban: each failure makes that source more expensive so a plan prefers an alternative recipe member or a fresh site,
 * while a source that is the only route to the item stays plannable. Removing a source outright would turn one
 * transient route failure into an unplannable order, and a search that found nothing is not proof the block is absent.
 *
 * <p>The ledger is deliberately small and typed: a family (the recipe need it served, such as a stone-tool material
 * tag), a source (the block id or remembered site that was attempted) and the short cause the child reported. A cause
 * that names its own refusal ({@code why=…}) also feeds a blocker streak: two consecutive routes refused for the same
 * typed reason are one blocked way, however many sources offer it. Nothing here decides authority or touches the
 * world; {@link WorldFacts} carries the counts into the pure planner.
 */
public final class AcquisitionAttempts {
    /** A plan never carries more history than this; the oldest entry is dropped first. */
    public static final int MAX_ENTRIES = 16;

    /** Added to one attempt's cost per recorded failure, so another member of the same need is preferred. */
    public static final int PENALTY_PER_FAILURE = 40;

    /**
     * How many consecutive failed attempts may share one typed blocker before the acquisition stops and reports it.
     * Two are enough: a different step or source that ends the same way is the same blocked route under another name,
     * and continuing to alternate between them only spends the step budget.
     */
    public static final int MAX_SAME_BLOCKER = 2;

    private static final int MAX_CAUSE = 120;

    /** One source that failed at least once. */
    public record Entry(String family, String source, int failures, String lastCause) {
        public Entry {
            family = family == null ? "" : family;
            source = source == null ? "" : source;
            lastCause = lastCause == null ? "" : lastCause;
        }
    }

    private final LinkedHashMap<String, Entry> entries = new LinkedHashMap<>();

    /** The last typed blocker a failure reported, so consecutive attempts ending the same way are counted together. */
    private String blockerFamily = "";

    private String blocker = "";
    private int blockerAttempts;
    private final LinkedHashSet<String> blockerSources = new LinkedHashSet<>();

    /**
     * Records one failed attempt. Recording the same source again raises its count instead of adding a row, and the
     * ledger stays within {@link #MAX_ENTRIES}, dropping the oldest source when it is full.
     */
    public void recordFailure(String family, String source, String cause) {
        if (source == null || source.isBlank()) return;
        Entry previous = entries.remove(source);
        int failures = previous == null ? 1 : previous.failures() + 1;
        int total = (int) Math.min(Integer.MAX_VALUE, failures);
        String keptFamily = family == null || family.isBlank() ? (previous == null ? "" : previous.family()) : family;
        entries.put(source, new Entry(keptFamily, source, total, shorten(cause)));
        while (entries.size() > MAX_ENTRIES) {
            entries.remove(entries.keySet().iterator().next());
        }
        noteBlocker(keptFamily, source, cause);
    }

    /**
     * Counts a failed attempt against the typed blocker it reported. An untyped cause says nothing about which way is
     * blocked, so it neither counts nor breaks a streak; a different blocker or another recipe need starts over.
     */
    private void noteBlocker(String family, String source, String cause) {
        String key = blockerKey(cause);
        if (key.isEmpty()) return;
        if (key.equals(blocker) && family.equals(blockerFamily)) {
            blockerAttempts++;
        } else {
            blockerFamily = family;
            blocker = key;
            blockerAttempts = 1;
            blockerSources.clear();
        }
        if (source != null && !source.isBlank()) {
            blockerSources.add(source);
            while (blockerSources.size() > MAX_ENTRIES) {
                blockerSources.remove(blockerSources.iterator().next());
            }
        }
    }

    /**
     * The blocker repeated by consecutive failed attempts once it reached {@link #MAX_SAME_BLOCKER}, else empty. It is
     * the reason the job stopped, so it names what actually refuses the route instead of the last child's prose.
     */
    public String repeatedBlocker() {
        return blockerAttempts >= MAX_SAME_BLOCKER ? blocker : "";
    }

    /** How many consecutive failed attempts currently share {@link #repeatedBlocker()}. */
    public int repeatedBlockerAttempts() {
        return blockerAttempts;
    }

    /** The sources those attempts tried, bounded like the ledger, so the stop names every route it ruled out. */
    public List<String> repeatedBlockerSources() {
        return List.copyOf(blockerSources);
    }

    /**
     * Forgets the blocker streak after a step whose effect was verified. A route that really moved something is
     * eligible again, even if it failed before: only unchanged repetition condemns it.
     */
    public void clearBlockers() {
        blockerFamily = "";
        blocker = "";
        blockerAttempts = 0;
        blockerSources.clear();
    }

    /**
     * The route refusal a cause carries as {@code why=return-…}, with the failed leg's coordinates cut off so two
     * attempts refused at different cells count together. A transient search failure ({@code why=unreachable}) or any
     * other cause yields an empty key: only the mining session's verification of the way back says the way itself is
     * blocked, and only that is worth stopping the whole acquisition for.
     */
    static String blockerKey(String cause) {
        if (cause == null) return "";
        int at = cause.indexOf("why=");
        if (at < 0) return "";
        int start = at + "why=".length();
        if (!cause.startsWith("return-", start)) return "";
        int end = start;
        while (end < cause.length()) {
            char ch = cause.charAt(end);
            if (Character.isWhitespace(ch) || ch == ';' || ch == ')' || ch == '(') break;
            if (ch == '-' && end + 1 < cause.length()) {
                char next = cause.charAt(end + 1);
                if (Character.isDigit(next) || next == '-') break;
            }
            end++;
        }
        return cause.substring(start, end);
    }

    /** How many times this exact source failed in this acquisition. */
    public int failures(String source) {
        Entry entry = source == null ? null : entries.get(source);
        return entry == null ? 0 : entry.failures();
    }

    /** Cost added to a single attempt of this source so an untried alternative wins a tie. */
    public int penalty(String source) {
        return failures(source) * PENALTY_PER_FAILURE;
    }

    /** The per-source failure counts, for {@link WorldFacts}. */
    public Map<String, Integer> sourceFailures() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Entry entry : entries.values()) {
            if (entry.failures() > 0) counts.put(entry.source(), entry.failures());
        }
        return Collections.unmodifiableMap(counts);
    }

    /** Every recorded source, newest last; bounded by {@link #MAX_ENTRIES}. */
    public List<Entry> entries() {
        return List.copyOf(new ArrayList<>(entries.values()));
    }

    /** The sources already tried, for a diagnosis line. */
    public Set<String> sources() {
        return Set.copyOf(entries.keySet());
    }

    public void clear() {
        entries.clear();
        clearBlockers();
    }

    private static String shorten(String cause) {
        if (cause == null) return "";
        String text = cause.replace('\n', ' ').replace('\r', ' ').strip();
        return text.length() <= MAX_CAUSE ? text : text.substring(0, MAX_CAUSE);
    }
}
