package dev.friendai.features.memory;

import dev.friendai.features.social.BondState;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Immutable persisted memory snapshot, on-disk format 2: format 1 plus the owner bonds.
 *
 * <p>The bond entry for one owner is a {@link BondState}, whose component names ({@code points}, {@code lastDay},
 * {@code awardedToday}, {@code reasons}, {@code peaks}) are the persisted field names. A format 1 file has no bond
 * map and reads as format 2 with no bonds, which is a migration read: the next save writes format 2. Any other
 * foreign version is kept as read so the store still refuses it exactly as before.
 */
public record MemoryRecord(
        int formatVersion,
        long revision,
        int nextId,
        List<PlayerEntry> players,
        List<FactEntry> facts,
        Map<String, Boolean> foodRules,
        Map<String, BondState> bonds) {
    public static final int FORMAT_VERSION = 2;

    /** The one format that carries no bonds; reading it upgrades the snapshot instead of rewriting the file. */
    private static final int BONDLESS_FORMAT = 1;

    public MemoryRecord {
        formatVersion = migrate(formatVersion);
        players = List.copyOf(players == null ? List.of() : players);
        facts = List.copyOf(facts == null ? List.of() : facts);
        foodRules = Map.copyOf(foodRules == null ? Map.of() : foodRules);
        bonds = copyBonds(bonds);
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
        Map<String, BondState> bonds = new LinkedHashMap<>();
        book.bonds().forEach((owner, state) -> bonds.put(owner.toString(), state));
        return new MemoryRecord(FORMAT_VERSION, book.revision(), file.nextId, players, facts, rules, bonds);
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

    /**
     * A format 1 file predates the bond map, so it is read as the current format with no bonds. Every other version
     * stays as read: a newer file must still be refused rather than silently truncated.
     */
    private static int migrate(int version) {
        return version == BONDLESS_FORMAT ? FORMAT_VERSION : version;
    }

    /** Copies the bonds without null entries, keeping the owner order so a save stays diffable. */
    private static Map<String, BondState> copyBonds(Map<String, BondState> bonds) {
        if (bonds == null || bonds.isEmpty()) {
            return Map.of();
        }
        Map<String, BondState> copied = new LinkedHashMap<>();
        bonds.forEach((owner, state) -> {
            if (owner != null && !owner.isBlank() && state != null) {
                copied.put(owner, state);
            }
        });
        return Collections.unmodifiableMap(copied);
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
