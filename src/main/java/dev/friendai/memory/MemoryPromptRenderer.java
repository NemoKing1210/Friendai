package dev.friendai.memory;

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
        String ownerLabel = ownerName(book, owner);
        List<String> parts = new ArrayList<>();
        switch (caller.access()) {
            case OWNER -> {
                addPlayerSection(parts, book, owner, owner, ownerLabel);
                addWorldSection(parts, book, owner);
                addIndex(parts, book, owner);
            }
            case TRUSTED -> {
                addPlayerSection(parts, book, owner, owner, ownerLabel);
                addWorldSection(parts, book, owner);
                addPlayerSection(parts, book, owner, caller.uuid(), ownerLabel);
            }
            case GUEST -> addPlayerSection(parts, book, owner, caller.uuid(), ownerLabel);
        }
        if (parts.isEmpty()) {
            return "Memory: nothing remembered yet.";
        }
        return HEADER + "\n" + String.join("\n", parts);
    }

    private static void addPlayerSection(
            List<String> parts, MemoryBook book, UUID owner, UUID player, String ownerLabel) {
        if (player == null) {
            return;
        }
        List<Fact> facts = book.facts(Subject.player(player));
        if (facts.isEmpty()) {
            return;
        }
        String label = player.equals(owner) ? ownerLabel : playerName(book, player);
        parts.add("About " + label + " (" + role(book, owner, player) + "):\n" + renderFacts(facts, owner));
    }

    private static void addWorldSection(List<String> parts, MemoryBook book, UUID owner) {
        List<Fact> facts = book.facts(Subject.WORLD);
        if (facts.isEmpty()) {
            return;
        }
        parts.add("World:\n" + renderFacts(facts, owner));
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

    private static String renderFacts(List<Fact> facts, UUID owner) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < facts.size(); i++) {
            Fact fact = facts.get(i);
            if (i > 0) {
                text.append('\n');
            }
            text.append(" [")
                    .append(fact.id())
                    .append("] ")
                    .append(fact.category().name())
                    .append(": ")
                    .append(fact.text());
            if (!Objects.equals(fact.authorUuid(), owner)
                    && fact.authorName() != null
                    && !fact.authorName().isBlank()) {
                text.append(" (from ").append(fact.authorName()).append(")");
            }
        }
        return text.toString();
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
