package dev.friedai.memory;

import dev.friedai.text.UserMessage;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/** Player-facing output of {@code /friedai memory ...} as {@link UserMessage}s. */
public final class MemoryReport {
    private MemoryReport() {}

    /** @param target null for everything, {@code "world"}, or a player name */
    public static List<UserMessage> list(MemoryBook book, UUID owner, String target) {
        if (target != null) {
            if (target.equalsIgnoreCase("world")) {
                return worldSection(book);
            }
            return book.findByName(target)
                    .map(record -> playerSection(book, record.uuid(), record.lastName()))
                    .orElse(List.of(UserMessage.of("friedai.command.memory.unknown_player", target)));
        }
        List<UserMessage> messages = new ArrayList<>();
        String ownerName = book.player(owner).map(PlayerRecord::lastName).orElse("owner");
        messages.addAll(playerSection(book, owner, ownerName));
        messages.addAll(worldSection(book));
        List<PlayerRecord> others = book.players().stream()
                .filter(record -> !record.uuid().equals(owner))
                .filter(record -> !book.facts(Subject.player(record.uuid())).isEmpty())
                .sorted(Comparator.comparing(PlayerRecord::lastName, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)))
                .toList();
        for (PlayerRecord record : others) {
            messages.addAll(playerSection(book, record.uuid(), record.lastName()));
        }
        if (messages.isEmpty()) {
            return List.of(UserMessage.of("friedai.command.memory.list.empty"));
        }
        return messages;
    }

    public static UserMessage clear(MemoryBook book, String target) {
        if (target != null && target.equalsIgnoreCase("all")) {
            return UserMessage.of("friedai.command.memory.cleared", book.clearAllFacts());
        }
        if (target != null && target.equalsIgnoreCase("world")) {
            return UserMessage.of("friedai.command.memory.cleared", book.clearFacts(Subject.WORLD));
        }
        return book.findByName(target)
                .map(record -> UserMessage.of(
                        "friedai.command.memory.cleared", book.clearFacts(Subject.player(record.uuid()))))
                .orElseGet(() -> UserMessage.of(
                        "friedai.command.memory.unknown_player", target == null ? "" : target));
    }

    public static UserMessage forget(MemoryBook book, int id) {
        return book.removeFact(id)
                ? UserMessage.of("friedai.command.memory.forgot", id)
                : UserMessage.of("friedai.command.memory.no_fact", id);
    }

    private static List<UserMessage> playerSection(MemoryBook book, UUID player, String name) {
        List<Fact> facts = book.facts(Subject.player(player));
        if (facts.isEmpty()) {
            return List.of();
        }
        List<UserMessage> messages = new ArrayList<>();
        messages.add(UserMessage.of("friedai.command.memory.list.player", name, facts.size()));
        for (Fact fact : facts) {
            messages.add(entry(fact));
        }
        return messages;
    }

    private static List<UserMessage> worldSection(MemoryBook book) {
        List<Fact> facts = book.facts(Subject.WORLD);
        if (facts.isEmpty()) {
            return List.of();
        }
        List<UserMessage> messages = new ArrayList<>();
        messages.add(UserMessage.of("friedai.command.memory.list.world", facts.size()));
        for (Fact fact : facts) {
            messages.add(entry(fact));
        }
        return messages;
    }

    private static UserMessage entry(Fact fact) {
        return UserMessage.of(
                "friedai.command.memory.list.entry", fact.id(), fact.category().name(), fact.text());
    }
}
