package dev.friendai.features.memory;

import java.util.List;
import java.util.Map;

/** Immutable version-1 persisted memory snapshot. */
public record MemoryRecord(
        int formatVersion,
        long revision,
        int nextId,
        List<PlayerEntry> players,
        List<FactEntry> facts,
        Map<String, Boolean> foodRules) {
    public static final int FORMAT_VERSION = 1;

    public MemoryRecord {
        players = List.copyOf(players == null ? List.of() : players);
        facts = List.copyOf(facts == null ? List.of() : facts);
        foodRules = Map.copyOf(foodRules == null ? Map.of() : foodRules);
    }

    public static MemoryRecord capture(MemoryBook book) {
        MemoryFile file = book.toFile();
        List<PlayerEntry> players = file.players.stream()
                .map(p -> new PlayerEntry(p.uuid, p.lastName, p.trusted, p.lastSeen, p.language))
                .toList();
        List<FactEntry> facts = file.facts.stream()
                .map(f -> new FactEntry(f.id, f.subject, f.category, f.text, f.authorUuid, f.authorName, f.createdAt))
                .toList();
        Map<String, Boolean> rules = file.foodRules.stream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(rule -> rule.item, rule -> rule.allowed));
        return new MemoryRecord(FORMAT_VERSION, book.revision(), file.nextId, players, facts, rules);
    }

    public MemoryFile toFile() {
        MemoryFile file = new MemoryFile();
        file.nextId = Math.max(1, nextId);
        for (PlayerEntry entry : players) {
            MemoryFile.PlayerEntry player = new MemoryFile.PlayerEntry();
            player.uuid = entry.uuid();
            player.lastName = entry.lastName();
            player.trusted = entry.trusted();
            player.lastSeen = entry.lastSeen();
            player.language = entry.language();
            file.players.add(player);
        }
        for (FactEntry entry : facts) {
            MemoryFile.FactEntry fact = new MemoryFile.FactEntry();
            fact.id = entry.id();
            fact.subject = entry.subject();
            fact.category = entry.category();
            fact.text = entry.text();
            fact.authorUuid = entry.authorUuid();
            fact.authorName = entry.authorName();
            fact.createdAt = entry.createdAt();
            file.facts.add(fact);
        }
        foodRules.forEach((item, allowed) -> {
            MemoryFile.FoodRuleEntry rule = new MemoryFile.FoodRuleEntry();
            rule.item = item;
            rule.allowed = allowed;
            file.foodRules.add(rule);
        });
        return file;
    }

    public record PlayerEntry(String uuid, String lastName, boolean trusted, long lastSeen, String language) {}

    public record FactEntry(
            int id,
            String subject,
            String category,
            String text,
            String authorUuid,
            String authorName,
            long createdAt) {}
}
