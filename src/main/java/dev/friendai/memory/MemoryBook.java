package dev.friendai.memory;

import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Everything one owner's companion remembers: player records with trust, facts about players and the
 * world, and the owner's food rules. Pure Java; the store decides when to persist.
 */
public final class MemoryBook {
    private final Map<UUID, PlayerRecord> players = new LinkedHashMap<>();
    private final TreeMap<Integer, Fact> facts = new TreeMap<>();
    /** Item id to allowed: true when the owner allowed a food the companion refuses by default, false to ban one. */
    private final TreeMap<String, Boolean> foodRules = new TreeMap<>();
    /** The name the owner gave the companion; null means the configured default applies. */
    private String companionName;
    /** The personality the owner gave the companion; null means the configured default applies. */
    private String persona;
    /** The encoded goal board; null when there are no goals. */
    private String goals;
    /** The encoded free-time state; null when free time was never used. */
    private String freeTime;
    private int nextId = 1;
    private long revision;
    private boolean dirty;

    /** Changes on every mutation; the menu compares it with the last revision it sent. Not persisted. */
    public long revision() {
        return revision;
    }

    public Optional<PlayerRecord> player(UUID uuid) {
        return Optional.ofNullable(players.get(uuid));
    }

    /** The language the owner writes in, once it was learned from their own messages. */
    public Optional<String> ownerLanguage(UUID uuid) {
        return Optional.ofNullable(players.get(uuid)).map(PlayerRecord::language);
    }

    /**
     * Stores the owner's language on an existing record.
     *
     * @return whether the stored value changed
     */
    public boolean setOwnerLanguage(UUID uuid, String language) {
        PlayerRecord existing = players.get(uuid);
        if (existing == null) {
            return false;
        }
        String normalized = language == null || language.isBlank() ? null : language.strip();
        if (java.util.Objects.equals(existing.language(), normalized)) {
            return false;
        }
        players.put(uuid, new PlayerRecord(uuid, existing.lastName(), existing.trusted(), existing.lastSeen(),
                normalized));
        dirty = true;
        revision++;
        return true;
    }

    /** Case-insensitive name lookup; among records sharing the name the most recently seen wins. */
    public Optional<PlayerRecord> findByName(String name) {
        if (name == null) {
            return Optional.empty();
        }
        String needle = name.toLowerCase(Locale.ROOT);
        return players.values().stream()
                .filter(p -> p.lastName() != null && p.lastName().toLowerCase(Locale.ROOT).equals(needle))
                .max(Comparator.comparingLong(PlayerRecord::lastSeen));
    }

    /** All records, in insertion order. */
    public List<PlayerRecord> players() {
        return List.copyOf(players.values());
    }

    /** Creates a record if the player is unknown, otherwise refreshes the name. Trust and lastSeen are kept. */
    public void ensurePlayer(UUID uuid, String name) {
        PlayerRecord existing = players.get(uuid);
        if (existing == null) {
            players.put(uuid, new PlayerRecord(uuid, name, false, 0));
            dirty = true;
            revision++;
            return;
        }
        if (name != null && !name.equals(existing.lastName())) {
            players.put(uuid, new PlayerRecord(uuid, name, existing.trusted(), existing.lastSeen(), existing.language()));
            dirty = true;
            revision++;
        }
    }

    /** Records that the player spoke to the companion. Trust and the learned language are kept. */
    public void touch(UUID uuid, String name, long now) {
        PlayerRecord existing = players.get(uuid);
        boolean trusted = existing != null && existing.trusted();
        players.put(uuid, new PlayerRecord(uuid, name, trusted, now, existing == null ? null : existing.language()));
        dirty = true;
        revision++;
    }

    /** Creates or updates the record. Returns whether the trust flag actually changed. */
    public boolean setTrusted(UUID uuid, String name, boolean trusted, long now) {
        PlayerRecord existing = players.get(uuid);
        if (existing != null && existing.trusted() == trusted) {
            if (name != null && !name.equals(existing.lastName())) {
                players.put(uuid, new PlayerRecord(uuid, name, trusted, existing.lastSeen(), existing.language()));
                dirty = true;
                revision++;
            }
            return false;
        }
        long lastSeen = existing == null ? now : existing.lastSeen();
        players.put(uuid, new PlayerRecord(uuid, name, trusted, lastSeen, existing == null ? null : existing.language()));
        dirty = true;
        revision++;
        return true;
    }

    public boolean isTrusted(UUID uuid) {
        PlayerRecord record = players.get(uuid);
        return record != null && record.trusted();
    }

    /** Trusted players, sorted by name. */
    public List<PlayerRecord> trusted() {
        return players.values().stream()
                .filter(PlayerRecord::trusted)
                .sorted(Comparator.comparing(PlayerRecord::lastName, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    /** The name the owner gave the companion, or empty when the configured default still applies. */
    public Optional<String> companionName() {
        return Optional.ofNullable(companionName);
    }

    /**
     * Stores the owner's name for the companion. Returns whether it changed. The name is trimmed and checked by
     * {@link CompanionName#parse}; an unusable name throws with a message written for the model.
     */
    public boolean setCompanionName(String name) {
        String normalized = CompanionName.parse(name)
                .orElseThrow(() -> new IllegalArgumentException("Invalid companion name. " + CompanionName.PROBLEM));
        if (normalized.equals(companionName)) {
            return false;
        }
        companionName = normalized;
        dirty = true;
        revision++;
        return true;
    }

    /** The personality the owner gave the companion, or empty when the configured default still applies. */
    public Optional<String> persona() {
        return Optional.ofNullable(persona);
    }

    /**
     * Stores the owner's personality text. Returns whether it changed. The text is cleaned by
     * {@link PersonaText#parse}; an unusable text throws and leaves the old value.
     */
    public boolean setPersona(String text) {
        String normalized = PersonaText.parse(text)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Invalid persona. Use 1-" + PersonaText.MAX_LENGTH
                                + " characters, without formatting codes."));
        if (normalized.equals(persona)) {
            return false;
        }
        persona = normalized;
        dirty = true;
        revision++;
        return true;
    }

    /** The encoded goal board, or empty when the companion has no goals. */
    public Optional<String> goals() {
        return Optional.ofNullable(goals);
    }

    /** Stores the encoded goal board (null or blank clears it). Returns whether it changed. */
    public boolean setGoals(String encoded) {
        String value = encoded == null || encoded.isBlank() ? null : encoded;
        if (java.util.Objects.equals(value, goals)) {
            return false;
        }
        goals = value;
        dirty = true;
        revision++;
        return true;
    }

    /** The encoded free-time state, or empty when free time was never used. */
    public Optional<String> freeTime() {
        return Optional.ofNullable(freeTime);
    }

    /** Stores the encoded free-time state (null or blank clears it). Returns whether it changed. */
    public boolean setFreeTime(String encoded) {
        String value = encoded == null || encoded.isBlank() ? null : encoded;
        if (java.util.Objects.equals(value, freeTime)) {
            return false;
        }
        freeTime = value;
        dirty = true;
        revision++;
        return true;
    }

    /** Returns to the configured default. True when a personality was set. */
    public boolean clearPersona() {
        if (persona == null) {
            return false;
        }
        persona = null;
        dirty = true;
        revision++;
        return true;
    }

    /** The owner's rule for one food: true if allowed, false if forbidden; empty when the default applies. */
    public Optional<Boolean> foodRule(String itemId) {
        return Optional.ofNullable(foodRules.get(itemId));
    }

    /** Every food rule, sorted by item id. */
    public Map<String, Boolean> foodRules() {
        return Collections.unmodifiableMap(new TreeMap<>(foodRules));
    }

    /** Sets or replaces the rule for one food. Returns whether the stored value changed. */
    public boolean setFoodRule(String itemId, boolean allowed) {
        checkItemId(itemId);
        Boolean previous = foodRules.put(itemId, allowed);
        if (previous != null && previous == allowed) {
            return false;
        }
        dirty = true;
        revision++;
        return true;
    }

    /** Removes the rule for one food, so the default applies again. Returns whether a rule was removed. */
    public boolean clearFoodRule(String itemId) {
        checkItemId(itemId);
        if (foodRules.remove(itemId) == null) {
            return false;
        }
        dirty = true;
        revision++;
        return true;
    }

    private static void checkItemId(String itemId) {
        if (itemId == null || itemId.isBlank()) {
            throw new IllegalArgumentException("A food rule needs an item id.");
        }
    }

    public Optional<Fact> fact(int id) {
        return Optional.ofNullable(facts.get(id));
    }

    /** Facts about one subject, ascending id. */
    public List<Fact> facts(Subject subject) {
        return facts.values().stream().filter(f -> f.subject().equals(subject)).toList();
    }

    /** Every fact, ascending id. */
    public List<Fact> allFacts() {
        return List.copyOf(facts.values());
    }

    /**
     * Adds a fact, or replaces the same subject's fact with the same text (case-insensitive) in place.
     * Throws {@link IllegalArgumentException} with a message written for the model.
     */
    public Fact addFact(
            Subject subject,
            Category category,
            String text,
            UUID authorUuid,
            String authorName,
            long now,
            MemoryLimits limits) {
        String normalized = normalize(text);
        checkText(normalized, limits);
        Optional<Fact> duplicate = facts.values().stream()
                .filter(f -> f.subject().equals(subject) && f.text().equalsIgnoreCase(normalized))
                .findFirst();
        if (duplicate.isPresent()) {
            Fact old = duplicate.get();
            Fact updated = new Fact(old.id(), subject, category, normalized, authorUuid, authorName, now);
            facts.put(old.id(), updated);
            dirty = true;
            revision++;
            return updated;
        }
        int max = subject instanceof Subject.World ? limits.maxWorldFacts() : limits.maxFactsPerPlayer();
        if (facts(subject).size() >= max) {
            throw new MemoryLimitException(
                    MemoryLimitException.Kind.FULL,
                    "Memory about " + label(subject) + " is full (" + max + " facts). Forget something first.",
                    List.of(subject, max));
        }
        Fact fact = new Fact(nextId++, subject, category, normalized, authorUuid, authorName, now);
        facts.put(fact.id(), fact);
        dirty = true;
        revision++;
        return fact;
    }

    /**
     * Replaces a fact's category, text, author and time, keeping its id and subject. Editing a fact to its
     * own current text is allowed; another fact of the same subject with that text is a duplicate.
     */
    public Fact editFact(
            int id,
            Category category,
            String text,
            UUID authorUuid,
            String authorName,
            long now,
            MemoryLimits limits) {
        Fact existing = facts.get(id);
        if (existing == null) {
            throw new MemoryLimitException(
                    MemoryLimitException.Kind.NO_FACT, "There is no fact [" + id + "].", List.of(id));
        }
        String normalized = normalize(text);
        checkText(normalized, limits);
        Optional<Fact> duplicate = facts.values().stream()
                .filter(f -> f.id() != id
                        && f.subject().equals(existing.subject())
                        && f.text().equalsIgnoreCase(normalized))
                .findFirst();
        if (duplicate.isPresent()) {
            int otherId = duplicate.get().id();
            throw new MemoryLimitException(
                    MemoryLimitException.Kind.DUPLICATE,
                    "This is already remembered as [" + otherId + "].",
                    List.of(otherId));
        }
        Fact updated =
                new Fact(id, existing.subject(), category, normalized, authorUuid, authorName, now);
        facts.put(id, updated);
        dirty = true;
        revision++;
        return updated;
    }

    private static String normalize(String text) {
        return text == null ? "" : text.strip().replaceAll("\\s+", " ");
    }

    private static void checkText(String normalized, MemoryLimits limits) {
        if (normalized.isBlank()) {
            throw new MemoryLimitException(MemoryLimitException.Kind.EMPTY, "Fact text is empty.", List.of());
        }
        if (normalized.length() > limits.maxFactChars()) {
            throw new MemoryLimitException(
                    MemoryLimitException.Kind.TOO_LONG,
                    "Fact is too long (" + normalized.length() + " chars, max " + limits.maxFactChars()
                            + "). Shorten it.",
                    List.of(normalized.length(), limits.maxFactChars()));
        }
    }

    public boolean removeFact(int id) {
        Fact removed = facts.remove(id);
        if (removed == null) {
            return false;
        }
        forgetLanguageIfMirrored(removed);
        dirty = true;
        revision++;
        return true;
    }

    /** Removes every fact about the subject. Returns how many were removed. */
    public int clearFacts(Subject subject) {
        List<Fact> matching = facts(subject);
        matching.forEach(f -> facts.remove(f.id()));
        if (!matching.isEmpty()) {
            if (subject instanceof Subject.Player player) {
                forgetLanguage(player.uuid());
            }
            dirty = true;
            revision++;
        }
        return matching.size();
    }

    /** Removes every fact. The next id keeps growing, so old references never point at a new fact. */
    public int clearAllFacts() {
        int removed = facts.size();
        facts.clear();
        if (removed > 0) {
            players.replaceAll((uuid, record) -> record.language() == null
                    ? record
                    : new PlayerRecord(uuid, record.lastName(), record.trusted(), record.lastSeen(), null));
            dirty = true;
            revision++;
        }
        return removed;
    }

    /** Forgetting the fact that mirrors the learned language forgets the language itself. */
    private void forgetLanguageIfMirrored(Fact fact) {
        if (fact.subject() instanceof Subject.Player player && AutoProfile.isLanguageFact(fact.text())) {
            forgetLanguage(player.uuid());
        }
    }

    private void forgetLanguage(UUID uuid) {
        PlayerRecord existing = players.get(uuid);
        if (existing == null || existing.language() == null) {
            return;
        }
        players.put(uuid, new PlayerRecord(
                uuid, existing.lastName(), existing.trusted(), existing.lastSeen(), null));
    }

    private String label(Subject subject) {
        if (subject instanceof Subject.World) {
            return "the world";
        }
        UUID uuid = ((Subject.Player) subject).uuid();
        return player(uuid)
                .map(PlayerRecord::lastName)
                .filter(name -> name != null && !name.isBlank())
                .orElse("unknown player");
    }

    public boolean isDirty() {
        return dirty;
    }

    public void markClean() {
        dirty = false;
    }

    public MemoryFile toFile() {
        MemoryFile file = new MemoryFile();
        file.nextId = nextId;
        file.companionName = companionName;
        file.persona = persona;
        file.goals = goals;
        file.freeTime = freeTime;
        for (Fact fact : facts.values()) {
            MemoryFile.FactEntry entry = new MemoryFile.FactEntry();
            entry.id = fact.id();
            entry.subject = fact.subject().key();
            entry.category = fact.category().name();
            entry.text = fact.text();
            entry.authorUuid = fact.authorUuid() == null ? null : fact.authorUuid().toString();
            entry.authorName = fact.authorName();
            entry.createdAt = fact.createdAt();
            file.facts.add(entry);
        }
        for (PlayerRecord record : players.values()) {
            MemoryFile.PlayerEntry entry = new MemoryFile.PlayerEntry();
            entry.uuid = record.uuid().toString();
            entry.lastName = record.lastName();
            entry.trusted = record.trusted();
            entry.lastSeen = record.lastSeen();
            entry.language = record.language();
            file.players.add(entry);
        }
        foodRules.forEach((item, allowed) -> {
            MemoryFile.FoodRuleEntry entry = new MemoryFile.FoodRuleEntry();
            entry.item = item;
            entry.allowed = allowed;
            file.foodRules.add(entry);
        });
        return file;
    }

    /** Loads a file, skipping entries whose UUID is missing or unparsable. The result is clean. */
    public static MemoryBook fromFile(MemoryFile file) {
        MemoryBook book = new MemoryBook();
        if (file != null) {
            CompanionName.parse(file.companionName).ifPresent(name -> book.companionName = name);
            PersonaText.parse(file.persona).ifPresent(text -> book.persona = text);
            if (file.goals != null && !file.goals.isBlank()) {
                book.goals = file.goals;
            }
            if (file.freeTime != null && !file.freeTime.isBlank()) {
                book.freeTime = file.freeTime;
            }
        }
        if (file != null && file.players != null) {
            for (MemoryFile.PlayerEntry entry : file.players) {
                if (entry == null || entry.uuid == null) {
                    continue;
                }
                UUID uuid;
                try {
                    uuid = UUID.fromString(entry.uuid);
                } catch (IllegalArgumentException e) {
                    continue;
                }
                book.players.put(uuid, new PlayerRecord(uuid, entry.lastName, entry.trusted, entry.lastSeen,
                        entry.language));
            }
        }
        if (file != null && file.facts != null) {
            int maxId = 0;
            for (MemoryFile.FactEntry entry : file.facts) {
                if (entry == null || entry.id < 1) {
                    continue;
                }
                Subject subject = Subject.parse(entry.subject).orElse(null);
                Category category = Category.parse(entry.category).orElse(null);
                if (subject == null || category == null || entry.text == null || entry.text.isBlank()) {
                    continue;
                }
                UUID author = null;
                if (entry.authorUuid != null) {
                    try {
                        author = UUID.fromString(entry.authorUuid);
                    } catch (IllegalArgumentException e) {
                        // keep the fact, it just loses the link to its author
                    }
                }
                book.facts.put(
                        entry.id,
                        new Fact(entry.id, subject, category, entry.text, author, entry.authorName, entry.createdAt));
                maxId = Math.max(maxId, entry.id);
            }
            book.nextId = Math.max(file.nextId, maxId + 1);
        }
        if (file != null && file.foodRules != null) {
            for (MemoryFile.FoodRuleEntry entry : file.foodRules) {
                if (entry == null || entry.item == null || entry.item.isBlank()) {
                    continue;
                }
                book.foodRules.put(entry.item, entry.allowed);
            }
        }
        book.dirty = false;
        return book;
    }
}
