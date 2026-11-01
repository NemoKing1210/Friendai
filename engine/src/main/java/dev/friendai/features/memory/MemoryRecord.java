package dev.friendai.features.memory;

import dev.friendai.features.social.BondState;
import dev.friendai.features.social.Happening;
import dev.friendai.features.social.HappeningJournal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Immutable persisted memory snapshot, on-disk format 3: format 2 plus the owner happenings journal.
 *
 * <p>The bond entry for one owner is a {@link BondState}, whose component names ({@code points}, {@code lastDay},
 * {@code awardedToday}, {@code reasons}, {@code peaks}) are the persisted field names. A format 1 file has no bond
 * map and reads as the current format with nothing lost: the fields it carries keep their values and the newer maps
 * start empty, which is a migration read. Any newer version is kept as read so the store still refuses it.
 */
public record MemoryRecord(
        int formatVersion,
        long revision,
        int nextId,
        List<PlayerEntry> players,
        List<FactEntry> facts,
        Map<String, Boolean> foodRules,
        Map<String, BondState> bonds,
        Map<String, List<Happening>> happenings) {
    public static final int FORMAT_VERSION = 3;

    /** The oldest format this build can read; everything between it and the current version is migrated on read. */
    private static final int OLDEST_FORMAT = 1;

    /** The pre-journal shape, so a reader that only knows the bonds keeps compiling. */
    public MemoryRecord(
            int formatVersion,
            long revision,
            int nextId,
            List<PlayerEntry> players,
            List<FactEntry> facts,
            Map<String, Boolean> foodRules,
            Map<String, BondState> bonds) {
        this(formatVersion, revision, nextId, players, facts, foodRules, bonds, Map.of());
    }

    public MemoryRecord {
        formatVersion = migrate(formatVersion);
        players = List.copyOf(players == null ? List.of() : players);
        facts = List.copyOf(facts == null ? List.of() : facts);
        foodRules = Map.copyOf(foodRules == null ? Map.of() : foodRules);
        bonds = copyBonds(bonds);
        happenings = copyHappenings(happenings);
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
        Map<String, List<Happening>> happenings = new LinkedHashMap<>();
        book.journals()
                .forEach((owner, journal) ->
                        happenings.put(owner.toString(), journal.recent(HappeningJournal.MAX_ENTRIES)));
        return new MemoryRecord(FORMAT_VERSION, book.revision(), file.nextId, players, facts, rules, bonds, happenings);
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
     * A file from before the bond map or the journal is read as the current format: its fields keep their values and
     * the newer maps start empty. A newer file stays as read, so the store still refuses it rather than truncating it.
     */
    private static int migrate(int version) {
        return version >= OLDEST_FORMAT && version <= FORMAT_VERSION ? FORMAT_VERSION : version;
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

    /** Copies the journals without null entries, keeping the owner order and the bounded entry list. */
    private static Map<String, List<Happening>> copyHappenings(Map<String, List<Happening>> happenings) {
        if (happenings == null || happenings.isEmpty()) {
            return Map.of();
        }
        Map<String, List<Happening>> copied = new LinkedHashMap<>();
        happenings.forEach((owner, entries) -> {
            if (owner == null || owner.isBlank() || entries == null) {
                return;
            }
            List<Happening> bounded = entries.stream()
                    .filter(entry -> entry != null
                            && entry.text() != null
                            && !entry.key().isBlank())
                    .limit(HappeningJournal.MAX_ENTRIES)
                    .toList();
            if (!bounded.isEmpty()) {
                copied.put(owner, bounded);
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
