package dev.friendai.features.memory.transcript;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.friendai.foundation.persistence.UnsupportedStoreVersionException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Versioned persistence DTO for one owner's transcript. Decoding is tolerant one entry at a time: a malformed,
 * duplicated or unknown record is dropped, while a wrong file version rejects the whole file as unsupported (the store keeps
 * it untouched and read-only). The newest entries win when the file exceeds the count cap.
 */
public final class TranscriptFile {
    public static final int CURRENT_VERSION = 1;

    private static final Gson GSON =
            new GsonBuilder().setPrettyPrinting().serializeNulls().create();

    private int version = CURRENT_VERSION;
    public long nextId = 1;
    public List<Record> entries = new ArrayList<>();

    private TranscriptFile() {}

    /** The file for {@code book}, oldest first. */
    public static TranscriptFile encode(TranscriptBook book) {
        TranscriptFile file = new TranscriptFile();
        file.nextId = book.nextId();
        for (TranscriptEntry entry : book.snapshot()) {
            Record record = new Record();
            record.id = entry.id();
            record.atMillis = entry.atMillis();
            record.playerUuid = entry.playerUuid().toString();
            record.playerName = entry.playerName();
            record.speaker = entry.speaker().name();
            record.text = entry.text();
            file.entries.add(record);
        }
        return file;
    }

    public String toJson() {
        return GSON.toJson(this);
    }

    /** Decodes one file, keeping every well-formed entry and repairing the next id. */
    public static TranscriptBook decode(String json, TranscriptLimits limits) {
        TranscriptFile file;
        try {
            file = GSON.fromJson(json, TranscriptFile.class);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("malformed transcript file", e);
        }
        if (file == null) {
            throw new IllegalArgumentException("empty transcript file");
        }
        if (file.version < 1) {
            throw new IllegalArgumentException("invalid transcript version " + file.version);
        }
        if (file.version > CURRENT_VERSION) {
            throw new UnsupportedStoreVersionException();
        }
        TranscriptBook book = new TranscriptBook(limits);
        Set<Long> seen = new HashSet<>();
        long maxId = 0;
        if (file.entries != null) {
            for (Record record : file.entries) {
                TranscriptEntry entry = entry(record, limits, seen);
                if (entry == null) {
                    continue;
                }
                book.restore(entry);
                maxId = Math.max(maxId, entry.id());
            }
        }
        book.restoreNextId(Math.max(file.nextId, maxId + 1));
        book.trimToLimits();
        return book;
    }

    private static TranscriptEntry entry(Record record, TranscriptLimits limits, Set<Long> seen) {
        if (record == null || record.id < 1 || !seen.add(record.id)) {
            return null;
        }
        UUID uuid;
        try {
            uuid = UUID.fromString(record.playerUuid == null ? "" : record.playerUuid);
        } catch (IllegalArgumentException e) {
            return null;
        }
        Speaker speaker;
        try {
            speaker = Speaker.valueOf(record.speaker == null ? "" : record.speaker);
        } catch (IllegalArgumentException e) {
            return null;
        }
        String text = record.text == null ? "" : record.text.strip();
        if (text.isEmpty()) {
            return null;
        }
        if (text.length() > limits.maxTextChars()) {
            text = text.substring(0, Math.max(0, limits.maxTextChars() - 1)) + "…";
        }
        return new TranscriptEntry(
                record.id,
                record.atMillis,
                uuid,
                record.playerName == null ? "" : record.playerName.strip(),
                speaker,
                text);
    }

    /** One stored line; field names and order mirror the file format. */
    public static final class Record {
        public long id;
        public long atMillis;
        public String playerUuid;
        public String playerName;
        public String speaker;
        public String text;
    }
}
