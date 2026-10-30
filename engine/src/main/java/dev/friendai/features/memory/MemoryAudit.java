package dev.friendai.features.memory;

import dev.friendai.execution.tool.AccessLevel;
import dev.friendai.execution.tool.Caller;
import dev.friendai.foundation.text.TextBudget;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.UnaryOperator;

/**
 * The model-facing overview of a facts book: how full each book is, and on request every fact with its id,
 * category, subject, author and age. Access follows the prompt matrix: owner all, trusted world and self,
 * guest self. Text is redacted on read, because an old fact may predate redaction on write.
 */
public final class MemoryAudit {
    private MemoryAudit() {}

    public static String overview(
            MemoryBook book,
            UUID owner,
            Caller caller,
            MemoryLimits limits,
            int compactAtPercent,
            long now,
            String subjectName,
            int limit,
            Boolean detail,
            int maxChars,
            UnaryOperator<String> redactor) {
        if (owner == null) {
            throw new IllegalArgumentException("The companion has no owner to read memory for.");
        }
        if (caller == null) {
            throw new IllegalArgumentException("This speaker cannot read memory.");
        }
        String counts = counts(book, owner, caller, limits, compactAtPercent);
        Subject filter = resolveSubject(book, owner, caller, subjectName);
        boolean withDetail = detail != null ? detail : filter != null;
        if (!withDetail) {
            return counts;
        }
        List<Fact> facts = filter == null ? visible(book, owner, caller) : book.facts(filter);
        if (facts.isEmpty()) {
            return counts + "\nNo facts remembered" + (filter == null ? " yet." : " about this subject.");
        }
        int capped = Math.max(1, Math.min(100, limit));
        List<Fact> shown = facts.size() > capped ? facts.subList(0, capped) : facts;
        UnaryOperator<String> clean = redactor == null ? text -> text : redactor;
        List<String> lines = new ArrayList<>();
        for (Fact fact : shown) {
            lines.add(row(book, owner, fact, now, clean));
        }
        TextBudget.Fit fit = TextBudget.fit(lines, maxChars);
        StringBuilder text = new StringBuilder(counts).append('\n').append(TextBudget.join(fit));
        boolean truncated = fit.truncated() || shown.size() < facts.size();
        text.append("\ntruncated: ").append(truncated);
        if (truncated) {
            text.append("\ncontinuation: memory_overview subject=\"")
                    .append(filter == null ? "" : label(book, owner, filter))
                    .append("\" limit=")
                    .append(capped)
                    .append(" (raise the limit or use a narrower subject)");
        }
        return text.toString();
    }

    /** Counts for the books the caller may act on, with a full marker at the configured share. */
    public static String counts(MemoryBook book, UUID owner, Caller caller, MemoryLimits limits, int compactAtPercent) {
        List<String> parts = new ArrayList<>();
        if (caller.access() == AccessLevel.OWNER) {
            parts.add("you " + book.facts(Subject.player(owner)).size() + "/" + limits.maxFactsPerPlayer()
                    + full(book.facts(Subject.player(owner)).size(), limits.maxFactsPerPlayer(), compactAtPercent));
            parts.add("world " + book.facts(Subject.WORLD).size() + "/" + limits.maxWorldFacts()
                    + full(book.facts(Subject.WORLD).size(), limits.maxWorldFacts(), compactAtPercent));
            Map<UUID, Integer> others = perPlayer(book, owner);
            int shown = 0;
            for (Map.Entry<UUID, Integer> entry : others.entrySet()) {
                if (shown == 6) {
                    parts.add((others.size() - shown) + " more players");
                    break;
                }
                parts.add(playerName(book, entry.getKey()) + " " + entry.getValue() + "/"
                        + limits.maxFactsPerPlayer()
                        + full(entry.getValue(), limits.maxFactsPerPlayer(), compactAtPercent));
                shown++;
            }
        } else {
            if (caller.access() == AccessLevel.TRUSTED) {
                parts.add("world " + book.facts(Subject.WORLD).size() + "/" + limits.maxWorldFacts()
                        + full(book.facts(Subject.WORLD).size(), limits.maxWorldFacts(), compactAtPercent));
            }
            if (caller.uuid() != null) {
                parts.add("you " + book.facts(Subject.player(caller.uuid())).size() + "/"
                        + limits.maxFactsPerPlayer()
                        + full(
                                book.facts(Subject.player(caller.uuid())).size(),
                                limits.maxFactsPerPlayer(),
                                compactAtPercent));
            }
        }
        return "Facts: " + String.join(", ", parts) + ".";
    }

    /** Resolves a model-facing subject name and enforces the caller's read rights; null means no filter. */
    public static Subject resolveSubject(MemoryBook book, UUID owner, Caller caller, String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String name = raw.strip();
        Subject resolved;
        if (name.equalsIgnoreCase("world")) {
            resolved = Subject.WORLD;
        } else if (name.equalsIgnoreCase("me")) {
            if (caller.uuid() == null) {
                throw new IllegalArgumentException("This speaker has no identity to read facts for.");
            }
            resolved = Subject.player(caller.uuid());
        } else if (name.equalsIgnoreCase("owner")) {
            resolved = Subject.player(owner);
        } else {
            resolved = book.findByName(name)
                    .map(record -> (Subject) Subject.player(record.uuid()))
                    .orElseThrow(() -> new IllegalArgumentException("I know no player called " + name + "."));
        }
        if (!allowed(owner, caller, resolved)) {
            throw new IllegalArgumentException("You can only read facts about yourself.");
        }
        return resolved;
    }

    private static boolean allowed(UUID owner, Caller caller, Subject subject) {
        if (caller.access() == AccessLevel.OWNER) {
            return true;
        }
        if (subject instanceof Subject.World) {
            return caller.access() == AccessLevel.TRUSTED;
        }
        return caller.uuid() != null && Objects.equals(((Subject.Player) subject).uuid(), caller.uuid());
    }

    private static List<Fact> visible(MemoryBook book, UUID owner, Caller caller) {
        List<Fact> facts = new ArrayList<>();
        if (caller.access() == AccessLevel.OWNER) {
            return book.allFacts();
        }
        if (caller.access() == AccessLevel.TRUSTED) {
            facts.addAll(book.facts(Subject.WORLD));
        }
        if (caller.uuid() != null) {
            facts.addAll(book.facts(Subject.player(caller.uuid())));
        }
        facts.sort(Comparator.comparingInt(Fact::id));
        return facts;
    }

    private static String row(MemoryBook book, UUID owner, Fact fact, long now, UnaryOperator<String> redactor) {
        StringBuilder line = new StringBuilder(" [")
                .append(fact.id())
                .append("] ")
                .append(fact.category().name())
                .append(" (")
                .append(label(book, owner, fact.subject()))
                .append("): ")
                .append(redactor.apply(fact.text()));
        String author = authorLabel(book, owner, fact);
        if (!author.isEmpty()) {
            line.append(" — ").append(author);
        }
        line.append(", ").append(age(now - fact.createdAt()));
        return line.toString();
    }

    private static String authorLabel(MemoryBook book, UUID owner, Fact fact) {
        if (fact.authorUuid() == null) {
            return "";
        }
        if (fact.authorUuid().equals(owner)) {
            return "owner";
        }
        String name = fact.authorName();
        return name == null || name.isBlank() ? "" : name;
    }

    private static String label(MemoryBook book, UUID owner, Subject subject) {
        if (subject instanceof Subject.World) {
            return "world";
        }
        UUID player = ((Subject.Player) subject).uuid();
        return player.equals(owner) ? "owner" : playerName(book, player);
    }

    private static Map<UUID, Integer> perPlayer(MemoryBook book, UUID owner) {
        Map<UUID, Integer> counts = new LinkedHashMap<>();
        for (Fact fact : book.allFacts()) {
            if (fact.subject() instanceof Subject.Player player
                    && !player.uuid().equals(owner)) {
                counts.merge(player.uuid(), 1, Integer::sum);
            }
        }
        return counts;
    }

    private static String full(int count, int max, int compactAtPercent) {
        if (max <= 0) {
            return "";
        }
        int threshold = Math.max(1, Math.min(max, (int) Math.ceil(max * compactAtPercent / 100.0)));
        return count >= threshold ? " (full)" : "";
    }

    private static String playerName(MemoryBook book, UUID player) {
        return book.player(player)
                .map(PlayerRecord::lastName)
                .filter(name -> name != null && !name.isBlank())
                .orElse("unknown player");
    }

    public static String age(long millis) {
        long minutes = Math.max(0, millis) / 60_000;
        if (minutes < 60) {
            return minutes + "m";
        }
        long hours = minutes / 60;
        if (hours < 24) {
            return hours + "h";
        }
        return (hours / 24) + "d";
    }
}
