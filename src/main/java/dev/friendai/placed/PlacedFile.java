package dev.friendai.placed;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import dev.friendai.features.navigation.pathing.Pos;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Versioned persistence DTO for one owner's placement ledger. Decode validates every record before the book is
 * built: a wrong version, a malformed dimension or block id, a negative tick, an oversized source, a duplicate
 * cell or a book beyond the cap is rejected, and the store backs the file up and starts empty.
 */
public final class PlacedFile {
    public static final int CURRENT_VERSION = 1;
    /** Longest tool or task name a record may carry. */
    public static final int SOURCE_MAX = 64;

    private static final Pattern ID = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");
    private static final Gson GSON =
            new GsonBuilder().setPrettyPrinting().serializeNulls().create();

    private int version;
    private List<Record> entries;

    private PlacedFile() {}

    private PlacedFile(int version, List<Record> entries) {
        this.version = version;
        this.entries = entries;
    }

    /** The file for {@code book}, oldest first. */
    public static PlacedFile encode(PlacedBook book) {
        List<Record> records = new ArrayList<>();
        for (PlacedBlock block : book.all()) {
            records.add(new Record(
                    block.dimension(),
                    block.pos().x(),
                    block.pos().y(),
                    block.pos().z(),
                    block.blockId(),
                    block.tick(),
                    block.source()));
        }
        return new PlacedFile(CURRENT_VERSION, records);
    }

    /** Decodes one owner's file; a null file yields an empty book. */
    public static PlacedBook decode(String json, PlacedLimits limits) {
        if (json == null) {
            return new PlacedBook(limits);
        }
        PlacedFile file;
        try {
            file = GSON.fromJson(json, PlacedFile.class);
        } catch (JsonParseException e) {
            throw new IllegalArgumentException("the placement file is not valid JSON", e);
        }
        if (file == null) {
            throw new IllegalArgumentException("the placement file is empty");
        }
        if (file.version != CURRENT_VERSION) {
            throw new IllegalArgumentException("unknown placement file version " + file.version);
        }
        if (file.entries == null) {
            throw new IllegalArgumentException("the placement file has no entries list");
        }
        if (file.entries.size() > limits.maxEntries()) {
            throw new IllegalArgumentException(
                    "the placement file holds " + file.entries.size() + " entries, more than " + limits.maxEntries());
        }
        PlacedBook book = new PlacedBook(limits);
        Set<String> seen = new HashSet<>();
        for (Record record : file.entries) {
            if (record == null) {
                throw new IllegalArgumentException("a placement entry is missing");
            }
            String dimension = requireId(record.dimension, "dimension");
            String block = requireId(record.block, "block");
            if (record.tick < 0) {
                throw new IllegalArgumentException("a placement tick must not be negative");
            }
            String source = record.source == null ? "direct" : record.source.strip();
            if (source.isEmpty() || source.length() > SOURCE_MAX) {
                throw new IllegalArgumentException("a placement source must be 1-" + SOURCE_MAX + " characters");
            }
            String key = dimension + "|" + record.x + "|" + record.y + "|" + record.z;
            if (!seen.add(key)) {
                throw new IllegalArgumentException("two placement entries share " + key);
            }
            book.record(new PlacedBlock(dimension, new Pos(record.x, record.y, record.z), block, record.tick, source));
        }
        book.markClean();
        return book;
    }

    public String toJson() {
        return GSON.toJson(this);
    }

    private static String requireId(String value, String what) {
        if (value == null || !ID.matcher(value).matches()) {
            throw new IllegalArgumentException("invalid placement " + what + ": " + value);
        }
        return value;
    }

    /** One stored placement; field names and order mirror the file format. */
    public static final class Record {
        private String dimension;
        private int x;
        private int y;
        private int z;
        private String block;
        private long tick;
        private String source;

        private Record() {}

        Record(String dimension, int x, int y, int z, String block, long tick, String source) {
            this.dimension = dimension;
            this.x = x;
            this.y = y;
            this.z = z;
            this.block = block;
            this.tick = tick;
            this.source = source;
        }
    }
}
