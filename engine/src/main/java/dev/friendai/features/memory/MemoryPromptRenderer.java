package dev.friendai.features.memory;

import dev.friendai.execution.tool.Caller;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Renders the memory block of the system prompt for one caller. What a caller sees is what the model may
 * reveal, so a guest only ever sees facts about themselves.
 */
public final class MemoryPromptRenderer {
    private static final String HEADER = "Memory (ids in brackets; forget takes the id):";

    private MemoryPromptRenderer() {}

    public static String render(MemoryBook book, UUID owner, Caller caller) {
        return render(book, owner, caller, MemoryLimits.DEFAULTS, 100, text -> text);
    }

    /**
     * Renders the memory block with a fullness line, so the model sees a full book before an exception tells it.
     * Facts are redacted on read: an old fact may predate redaction on write.
     */
    public static String render(
            MemoryBook book,
            UUID owner,
            Caller caller,
            MemoryLimits limits,
            int compactAtPercent,
            java.util.function.UnaryOperator<String> redactor) {
        String ownerLabel = ownerName(book, owner);
        List<String> parts = new ArrayList<>();
        switch (caller.access()) {
            case OWNER -> {
                addPlayerSection(parts, book, owner, owner, ownerLabel, redactor);
                addWorldSection(parts, book, owner, redactor);
                addIndex(parts, book, owner);
            }
            case TRUSTED -> {
                addPlayerSection(parts, book, owner, owner, ownerLabel, redactor);
                addWorldSection(parts, book, owner, redactor);
                addPlayerSection(parts, book, owner, caller.uuid(), ownerLabel, redactor);
            }
            case GUEST -> addPlayerSection(parts, book, owner, caller.uuid(), ownerLabel, redactor);
        }
        if (parts.isEmpty()) {
            return "Memory: nothing remembered yet.";
        }
        String counts = MemoryAudit.counts(book, owner, caller, limits, compactAtPercent);
        return HEADER + "\n" + counts + "\n" + String.join("\n", parts);
    }

    /**
     * Renders only the facts a retrieval selected, with the same header and fullness line as the full block, so a
     * selected block reads like a memory block while never carrying a fact this caller may not see or one the
     * current message did not select. The heading names the backend honestly: it matches words, not meaning.
     *
     * @param facts the selected facts, in the order they must be rendered; empty means "nothing to render"
     */
    public static String renderSelection(
            MemoryBook book,
            UUID owner,
            Caller caller,
            MemoryLimits limits,
            int compactAtPercent,
            List<Fact> facts,
            java.util.function.UnaryOperator<String> redactor) {
        return renderSelection(book, owner, caller, limits, compactAtPercent, facts, Integer.MAX_VALUE, redactor);
    }

    /**
     * Renders the selected facts under a whole-row character budget. A selection names facts about several subjects
     * out of order, so each row says which subject it is about; labels, ids and the author suffix count toward
     * {@code maxChars}. The lowest-ranked rows are dropped whole — a row is never cut — and the best-ranked row is
     * kept even when it alone is longer than the budget, because a block with no rows cannot answer the message.
     *
     * @param maxChars the row budget, or a non-positive value for no budget
     */
    public static String renderSelection(
            MemoryBook book,
            UUID owner,
            Caller caller,
            MemoryLimits limits,
            int compactAtPercent,
            List<Fact> facts,
            int maxChars,
            java.util.function.UnaryOperator<String> redactor) {
        if (book == null || owner == null || caller == null || facts == null || facts.isEmpty()) {
            return "";
        }
        java.util.function.UnaryOperator<String> clean = redactor == null ? text -> text : redactor;
        int budget = maxChars > 0 ? maxChars : Integer.MAX_VALUE;
        String body = "";
        for (int count = facts.size(); count > 0; count--) {
            body = renderSelected(facts.subList(0, count), book, owner, clean);
            if (body.length() <= budget || count == 1) {
                break;
            }
        }
        if (body.isBlank()) {
            return "";
        }
        MemoryLimits effective = limits == null ? MemoryLimits.DEFAULTS : limits;
        return HEADER + "\n"
                + MemoryAudit.counts(book, owner, caller, effective, compactAtPercent)
                + "\nClosest remembered facts to what was just said (word match, not meaning):\n"
                + body;
    }

    private static void addPlayerSection(
            List<String> parts,
            MemoryBook book,
            UUID owner,
            UUID player,
            String ownerLabel,
            java.util.function.UnaryOperator<String> redactor) {
        if (player == null) {
            return;
        }
        List<Fact> facts = book.facts(Subject.player(player));
        if (facts.isEmpty()) {
            return;
        }
        String label = player.equals(owner) ? ownerLabel : playerName(book, player);
        parts.add("About " + label + " (" + role(book, owner, player) + "):\n" + renderFacts(facts, owner, redactor));
    }

    private static void addWorldSection(
            List<String> parts, MemoryBook book, UUID owner, java.util.function.UnaryOperator<String> redactor) {
        List<Fact> facts = book.facts(Subject.WORLD);
        if (facts.isEmpty()) {
            return;
        }
        parts.add("World:\n" + renderFacts(facts, owner, redactor));
    }

    private static void addIndex(List<String> parts, MemoryBook book, UUID owner) {
        Map<UUID, Integer> counts = new LinkedHashMap<>();
        for (Fact fact : book.allFacts()) {
            if (fact.subject() instanceof Subject.Player player
                    && !player.uuid().equals(owner)) {
                counts.merge(player.uuid(), 1, Integer::sum);
            }
        }
        if (counts.isEmpty()) {
            return;
        }
        List<String> entries = new ArrayList<>();
        List<UUID> sorted = new ArrayList<>(counts.keySet());
        sorted.sort(Comparator.comparing(uuid -> playerName(book, uuid), String.CASE_INSENSITIVE_ORDER));
        for (UUID uuid : sorted) {
            int count = counts.get(uuid);
            entries.add(playerName(book, uuid) + " (" + count + (count == 1 ? " fact" : " facts") + ")");
        }
        parts.add("Also known: " + String.join(", ", entries) + "; use recall.");
    }

    private static String renderFacts(List<Fact> facts, UUID owner, java.util.function.UnaryOperator<String> redactor) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < facts.size(); i++) {
            if (i > 0) {
                text.append('\n');
            }
            text.append(row(facts.get(i), owner, null, redactor));
        }
        return text.toString();
    }

    /**
     * Selected rows carry their subject because a selection mixes facts about the owner, other players and the world
     * in rank order; the author suffix is a different fact and stays separate.
     */
    private static String renderSelected(
            List<Fact> facts, MemoryBook book, UUID owner, java.util.function.UnaryOperator<String> redactor) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < facts.size(); i++) {
            Fact fact = facts.get(i);
            if (i > 0) {
                text.append('\n');
            }
            text.append(row(fact, owner, subjectLabel(book, owner, fact.subject()), redactor));
        }
        return text.toString();
    }

    private static String row(
            Fact fact, UUID owner, String subject, java.util.function.UnaryOperator<String> redactor) {
        StringBuilder text = new StringBuilder(" [")
                .append(fact.id())
                .append("] ")
                .append(fact.category().name());
        if (subject != null) {
            text.append(" (about ").append(subject).append(')');
        }
        text.append(": ").append(redactor.apply(fact.text()));
        if (!Objects.equals(fact.authorUuid(), owner)
                && fact.authorName() != null
                && !fact.authorName().isBlank()) {
            text.append(" (from ").append(fact.authorName()).append(")");
        }
        return text.toString();
    }

    private static String subjectLabel(MemoryBook book, UUID owner, Subject subject) {
        if (subject instanceof Subject.Player player) {
            return player.uuid().equals(owner) ? ownerName(book, owner) : playerName(book, player.uuid());
        }
        return "World";
    }

    private static String ownerName(MemoryBook book, UUID owner) {
        String name = book.player(owner).map(PlayerRecord::lastName).orElse(null);
        return name == null || name.isBlank() ? "your owner" : name;
    }

    private static String playerName(MemoryBook book, UUID player) {
        String name = book.player(player).map(PlayerRecord::lastName).orElse(null);
        return name == null || name.isBlank() ? "unknown player" : name;
    }

    private static String role(MemoryBook book, UUID owner, UUID player) {
        if (player.equals(owner)) {
            return "owner";
        }
        return book.isTrusted(player) ? "trusted" : "guest";
    }
}
