package dev.friedai.memory;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Everything one owner's companion remembers about players: names, when each was last seen, and trust.
 * Pure Java; the store decides when to persist.
 */
public final class MemoryBook {
    private final Map<UUID, PlayerRecord> players = new LinkedHashMap<>();
    private boolean dirty;

    public Optional<PlayerRecord> player(UUID uuid) {
        return Optional.ofNullable(players.get(uuid));
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

    /** Records that the player spoke to the companion. Trust is kept. */
    public void touch(UUID uuid, String name, long now) {
        PlayerRecord existing = players.get(uuid);
        boolean trusted = existing != null && existing.trusted();
        players.put(uuid, new PlayerRecord(uuid, name, trusted, now));
        dirty = true;
    }

    /** Creates or updates the record. Returns whether the trust flag actually changed. */
    public boolean setTrusted(UUID uuid, String name, boolean trusted, long now) {
        PlayerRecord existing = players.get(uuid);
        if (existing != null && existing.trusted() == trusted) {
            if (name != null && !name.equals(existing.lastName())) {
                players.put(uuid, new PlayerRecord(uuid, name, trusted, existing.lastSeen()));
                dirty = true;
            }
            return false;
        }
        long lastSeen = existing == null ? now : existing.lastSeen();
        players.put(uuid, new PlayerRecord(uuid, name, trusted, lastSeen));
        dirty = true;
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

    public boolean isDirty() {
        return dirty;
    }

    public void markClean() {
        dirty = false;
    }

    public MemoryFile toFile() {
        MemoryFile file = new MemoryFile();
        for (PlayerRecord record : players.values()) {
            MemoryFile.PlayerEntry entry = new MemoryFile.PlayerEntry();
            entry.uuid = record.uuid().toString();
            entry.lastName = record.lastName();
            entry.trusted = record.trusted();
            entry.lastSeen = record.lastSeen();
            file.players.add(entry);
        }
        return file;
    }

    /** Loads a file, skipping entries whose UUID is missing or unparsable. The result is clean. */
    public static MemoryBook fromFile(MemoryFile file) {
        MemoryBook book = new MemoryBook();
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
                book.players.put(uuid, new PlayerRecord(uuid, entry.lastName, entry.trusted, entry.lastSeen));
            }
        }
        book.dirty = false;
        return book;
    }
}
