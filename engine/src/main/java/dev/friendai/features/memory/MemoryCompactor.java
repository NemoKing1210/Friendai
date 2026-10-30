package dev.friendai.features.memory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Safe, deterministic compaction of a facts book. A merge keeps the newest and the distinct text, never touches a
 * promise, and never removes a fact without a non-blank replacement. The plan is a proposal; applying is the
 * caller's explicit act.
 */
public final class MemoryCompactor {
    public static final int DEFAULT_MAX_MERGES = 5;

    private MemoryCompactor() {}

    /** One replacement: the merged-away ids and the text that must survive in their place. */
    public record Merge(List<Integer> ids, Category category, Subject subject, String text, String reason) {
        public Merge {
            ids = List.copyOf(ids);
        }
    }

    public record Plan(List<Merge> merges, int before, int after) {
        public Plan {
            merges = List.copyOf(merges);
        }

        public boolean empty() {
            return merges.isEmpty();
        }
    }

    public record Applied(
            int added,
            int removed,
            List<Integer> newIds,
            List<Merge> applied,
            List<Merge> skipped,
            int before,
            int after) {
        public Applied {
            newIds = List.copyOf(newIds);
            applied = List.copyOf(applied);
            skipped = List.copyOf(skipped);
        }
    }

    public static Plan plan(
            MemoryBook book, UUID owner, Subject filter, MemoryLimits limits, boolean ownerRequested, int maxMerges) {
        List<Fact> facts = book.allFacts().stream()
                .filter(fact -> filter == null || fact.subject().equals(filter))
                .sorted(Comparator.comparingInt(Fact::id))
                .toList();
        int before = facts.size();
        Set<Integer> claimed = new HashSet<>();
        List<Merge> merges = new ArrayList<>();
        int cap = Math.max(1, Math.min(DEFAULT_MAX_MERGES, maxMerges));

        Map<String, List<Fact>> groups = new LinkedHashMap<>();
        for (Fact fact : facts) {
            groups.computeIfAbsent(fact.subject().key() + "|" + fact.category().name(), key -> new ArrayList<>())
                    .add(fact);
        }
        for (List<Fact> group : groups.values()) {
            if (merges.size() >= cap) {
                break;
            }
            merges.addAll(exactDuplicates(group, owner, limits, ownerRequested, claimed, cap - merges.size()));
        }
        for (List<Fact> group : groups.values()) {
            if (merges.size() >= cap) {
                break;
            }
            merges.addAll(supersededNotes(group, owner, limits, ownerRequested, claimed, cap - merges.size()));
        }
        int after = before;
        for (Merge merge : merges) {
            after -= merge.ids().size() - 1;
        }
        return new Plan(merges, before, after);
    }

    /**
     * Applies a plan. Every replacement reuses the newest fact's text, so {@link MemoryBook#addFact} updates that
     * fact in place (the duplicate path) instead of hitting the full cap; only then are the other ids removed.
     * A merge that cannot be applied is skipped and reported, never half-applied.
     */
    public static Applied apply(
            MemoryBook book, Plan plan, UUID authorUuid, String authorName, long now, MemoryLimits limits) {
        int before = book.allFacts().size();
        int added = 0;
        int removed = 0;
        List<Integer> newIds = new ArrayList<>();
        List<Merge> applied = new ArrayList<>();
        List<Merge> skipped = new ArrayList<>();
        for (Merge merge : plan.merges()) {
            Fact replacement;
            try {
                replacement = book.addFact(
                        merge.subject(), merge.category(), merge.text(), authorUuid, authorName, now, limits);
            } catch (RuntimeException e) {
                skipped.add(merge);
                continue;
            }
            added++;
            newIds.add(replacement.id());
            applied.add(merge);
            for (int id : merge.ids()) {
                if (id != replacement.id() && book.removeFact(id)) {
                    removed++;
                }
            }
        }
        return new Applied(
                added,
                removed,
                newIds,
                applied,
                skipped,
                before,
                book.allFacts().size());
    }

    private static List<Merge> exactDuplicates(
            List<Fact> group, UUID owner, MemoryLimits limits, boolean ownerRequested, Set<Integer> claimed, int room) {
        if (room < 1 || group.isEmpty() || group.get(0).category() == Category.PROMISE) {
            return List.of();
        }
        Map<String, List<Fact>> byText = new LinkedHashMap<>();
        for (Fact fact : group) {
            byText.computeIfAbsent(normalize(fact.text()), key -> new ArrayList<>())
                    .add(fact);
        }
        List<Merge> merges = new ArrayList<>();
        for (List<Fact> same : byText.values()) {
            if (merges.size() >= room) {
                break;
            }
            List<Fact> fresh =
                    same.stream().filter(fact -> !claimed.contains(fact.id())).toList();
            if (fresh.size() < 2 || !allowed(fresh, owner, ownerRequested, limits)) {
                continue;
            }
            Fact newest = fresh.get(fresh.size() - 1);
            List<Integer> ids = fresh.stream().map(Fact::id).toList();
            claimed.addAll(ids);
            merges.add(
                    new Merge(ids, newest.category(), newest.subject(), newest.text(), "same text, kept the newest"));
        }
        return merges;
    }

    private static List<Merge> supersededNotes(
            List<Fact> group, UUID owner, MemoryLimits limits, boolean ownerRequested, Set<Integer> claimed, int room) {
        if (room < 1 || group.isEmpty() || group.get(0).category() != Category.NOTE) {
            return List.of();
        }
        List<Merge> merges = new ArrayList<>();
        for (int i = 0; i < group.size() && merges.size() < room; i++) {
            Fact older = group.get(i);
            if (claimed.contains(older.id())) {
                continue;
            }
            for (int j = i + 1; j < group.size(); j++) {
                Fact newer = group.get(j);
                if (claimed.contains(newer.id())) {
                    continue;
                }
                Set<String> olderTokens = tokens(older.text());
                Set<String> newerTokens = tokens(newer.text());
                boolean superseded = !olderTokens.isEmpty() && newerTokens.containsAll(olderTokens);
                boolean near = jaccard(olderTokens, newerTokens) >= 0.85;
                if (!superseded && !near) {
                    continue;
                }
                Fact keep = newer;
                List<Integer> ids = List.of(older.id(), newer.id());
                if (!allowed(List.of(older, newer), owner, ownerRequested, limits)) {
                    continue;
                }
                claimed.addAll(ids);
                merges.add(new Merge(
                        ids,
                        keep.category(),
                        keep.subject(),
                        keep.text(),
                        superseded ? "superseded by a newer note" : "near-duplicate note"));
                break;
            }
        }
        return merges;
    }

    /** A merge must not remove a fact the owner authored when the owner did not ask for it. */
    private static boolean allowed(List<Fact> facts, UUID owner, boolean ownerRequested, MemoryLimits limits) {
        if (!ownerRequested) {
            for (Fact fact : facts) {
                if (fact.authorUuid() != null && fact.authorUuid().equals(owner)) {
                    return false;
                }
            }
        }
        for (Fact fact : facts) {
            if (fact.text().isBlank() || fact.text().length() > limits.maxFactChars()) {
                return false;
            }
        }
        return true;
    }

    private static Set<String> tokens(String text) {
        Set<String> tokens = new LinkedHashSet<>();
        for (String token : normalize(text).split(" ")) {
            if (!token.isBlank()) {
                tokens.add(token);
            }
        }
        return tokens;
    }

    private static double jaccard(Set<String> a, Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) {
            return 0;
        }
        Set<String> union = new HashSet<>(a);
        union.addAll(b);
        Set<String> intersection = new HashSet<>(a);
        intersection.retainAll(b);
        return (double) intersection.size() / union.size();
    }

    private static String normalize(String text) {
        return (text == null ? "" : text)
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}]+", " ")
                .strip()
                .replaceAll("\\s+", " ");
    }
}
