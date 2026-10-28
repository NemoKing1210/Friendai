package dev.friendai.trading;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.StringReader;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Immutable version-1 persistence data. Runtime actor identity and active references are excluded. */
public record TradeFile(
        int version,
        long revision,
        List<TraderRecord> traders,
        List<TradeProgress> history,
        Set<UUID> pinned,
        List<BarterProgress> barterHistory) {
    public static final int VERSION = 1;
    public static final int MAX_TRADERS = 256;
    public static final int MAX_OFFERS_PER_TRADER = 64;
    public static final int MAX_TOTAL_OFFERS = 2048;
    public static final int MAX_HISTORY = 256;
    public static final int MAX_FILE_BYTES = 8 * 1024 * 1024;
    public static final int MAX_JSON_DEPTH = 64;
    public static final int MAX_JSON_NODES = 1_000_000;
    private static final Gson GSON = new GsonBuilder().create();

    public TradeFile {
        if (version != VERSION
                || revision < 0
                || traders == null
                || traders.size() > MAX_TRADERS
                || history == null
                || history.size() > MAX_HISTORY
                || pinned == null
                || pinned.size() > MAX_TRADERS) {
            throw new IllegalArgumentException("invalid or oversized trade file");
        }
        if (barterHistory == null) barterHistory = List.of(); // Format-1 files predating barter history remain valid.
        if (barterHistory.size() > MAX_HISTORY) throw new IllegalArgumentException("barter history limit exceeded");
        // Validate all aggregate counts before copying the caller's collections.
        int offerCount = 0;
        var ids = new HashSet<UUID>();
        for (var trader : traders) {
            if (trader == null || !ids.add(trader.id())) throw new IllegalArgumentException("duplicate trader UUID");
            offerCount += trader.offers().size();
            if (offerCount > MAX_TOTAL_OFFERS) throw new IllegalArgumentException("total offer limit exceeded");
        }
        if (!ids.containsAll(pinned)) throw new IllegalArgumentException("pin refers to unknown trader");
        var orders = new HashSet<UUID>();
        for (var progress : history) {
            if (progress == null || !orders.add(progress.orderId()))
                throw new IllegalArgumentException("duplicate order UUID");
        }
        traders = List.copyOf(traders);
        history = List.copyOf(history);
        pinned = Set.copyOf(pinned);
        barterHistory = List.copyOf(barterHistory);
    }

    public TradeFile(
            int version, long revision, List<TraderRecord> traders, List<TradeProgress> history, Set<UUID> pinned) {
        this(version, revision, traders, history, pinned, List.of());
    }

    /** Serializes directly to a bounded UTF-8 buffer, without first allocating an unbounded String. */
    public static byte[] write(TradeFile file) {
        if (file == null) throw new IllegalArgumentException("trade file must not be null");
        var output = new BoundedOutputStream(MAX_FILE_BYTES);
        try (JsonWriter writer = new JsonWriter(new OutputStreamWriter(
                output,
                StandardCharsets.UTF_8
                        .newEncoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)))) {
            writer.setIndent("  ");
            writer.setSerializeNulls(true);
            GSON.getAdapter(TradeFile.class).write(writer, file);
        } catch (EncodedSizeExceeded e) {
            throw e;
        } catch (IOException e) {
            throw new IllegalStateException("could not encode trade file", e);
        }
        return output.toByteArray();
    }

    /** Strictly decodes a bounded file, rejecting deep JSON before constructing domain collections. */
    public static TradeFile read(byte[] bytes) {
        if (bytes == null || bytes.length > MAX_FILE_BYTES) {
            throw new IllegalArgumentException("trade file exceeds 8 MiB");
        }
        final String json;
        try {
            json = StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException e) {
            throw new IllegalArgumentException("trade file is not valid UTF-8", e);
        }
        int version = readRootVersion(json);
        if (version != VERSION) throw new UnsupportedVersionException(version);

        JsonObject root = readRoot(json);

        if (!root.has("traders")) throw new IllegalArgumentException("trade file has no trader records");
        if (!root.has("history")) root.add("history", new JsonArray());
        if (!root.has("pinned")) root.add("pinned", new JsonArray());
        if (!root.has("barterHistory")) root.add("barterHistory", new JsonArray());
        TradeFile file = GSON.fromJson(root, TradeFile.class);
        if (file == null) throw new IllegalArgumentException("empty trade file");
        return file;
    }

    /** Finds a root version linearly, without recursively parsing or allocating preceding future data. */
    private static int readRootVersion(String json) {
        int index = skipWhitespace(json, 0);
        if (index >= json.length() || json.charAt(index++) != '{') {
            throw new IllegalArgumentException("trade file root must be an object");
        }
        while (index < json.length()) {
            index = skipWhitespace(json, index);
            if (index < json.length() && json.charAt(index) == '}') break;
            if (index >= json.length() || json.charAt(index) != '"') {
                throw new IllegalArgumentException("invalid trade file root key");
            }
            int stringEnd = stringEnd(json, index);
            String key = readString(json.substring(index, stringEnd));
            index = skipWhitespace(json, stringEnd);
            if (index >= json.length() || json.charAt(index++) != ':') {
                throw new IllegalArgumentException("invalid trade file root field");
            }
            index = skipWhitespace(json, index);
            if (key.equals("version")) return readVersionNumber(json, index);
            index = skipValue(json, index);
            index = skipWhitespace(json, index);
            if (index < json.length() && json.charAt(index) == ',') {
                index++;
            } else if (index < json.length() && json.charAt(index) == '}') {
                break;
            } else {
                throw new IllegalArgumentException("invalid trade file root separator");
            }
        }
        throw new IllegalArgumentException("trade file has no numeric version");
    }

    private static int readVersionNumber(String json, int index) {
        int end = index;
        while (end < json.length()) {
            char current = json.charAt(end);
            if (current == ','
                    || current == '}'
                    || current == ' '
                    || current == '\t'
                    || current == '\r'
                    || current == '\n') break;
            end++;
        }
        if (end == index) throw new IllegalArgumentException("trade file version must be numeric");
        try {
            return new BigDecimal(json.substring(index, end)).intValueExact();
        } catch (ArithmeticException | NumberFormatException e) {
            throw new IllegalArgumentException("invalid trade file version", e);
        }
    }

    private static String readString(String token) {
        try (var reader = new JsonReader(new StringReader(token))) {
            reader.setStrictness(Strictness.STRICT);
            String value = requireWellFormed(reader.nextString());
            if (reader.peek() != JsonToken.END_DOCUMENT) throw new IllegalArgumentException("invalid trade file key");
            return value;
        } catch (IOException e) {
            throw new IllegalArgumentException("invalid trade file root key", e);
        }
    }

    private static int skipWhitespace(String text, int index) {
        while (index < text.length()) {
            char value = text.charAt(index);
            if (value != ' ' && value != '\t' && value != '\r' && value != '\n') break;
            index++;
        }
        return index;
    }

    private static int stringEnd(String text, int start) {
        boolean escaped = false;
        for (int index = start + 1; index < text.length(); index++) {
            char value = text.charAt(index);
            if (escaped) {
                escaped = false;
            } else if (value == '\\') {
                escaped = true;
            } else if (value == '"') {
                return index + 1;
            }
        }
        throw new IllegalArgumentException("unterminated trade file string");
    }

    private static int skipValue(String text, int start) {
        if (start >= text.length()) throw new IllegalArgumentException("missing trade file value");
        char first = text.charAt(start);
        if (first == '"') return stringEnd(text, start);
        if (first == '{' || first == '[') {
            int depth = 0;
            boolean inString = false;
            boolean escaped = false;
            for (int index = start; index < text.length(); index++) {
                char current = text.charAt(index);
                if (inString) {
                    if (escaped) escaped = false;
                    else if (current == '\\') escaped = true;
                    else if (current == '"') inString = false;
                    continue;
                }
                if (current == '"') inString = true;
                else if (current == '{' || current == '[') depth++;
                else if (current == '}' || current == ']') {
                    depth--;
                    if (depth == 0) return index + 1;
                    if (depth < 0) throw new IllegalArgumentException("invalid nested trade file value");
                }
            }
            throw new IllegalArgumentException("unterminated nested trade file value");
        }
        int end = start;
        while (end < text.length()) {
            char current = text.charAt(end);
            if (current == ','
                    || current == '}'
                    || current == ' '
                    || current == '\t'
                    || current == '\r'
                    || current == '\n') break;
            end++;
        }
        if (end == start) throw new IllegalArgumentException("invalid trade file value");
        return end;
    }

    private enum Shape {
        OTHER,
        ROOT_OBJECT,
        TRADERS,
        TRADER_OBJECT,
        OFFERS,
        OFFER_OBJECT,
        INPUTS,
        HISTORY,
        PROGRESS_OBJECT,
        RECEIVED,
        PINNED,
        SPENT_OBJECT
    }

    private static final class ParseState {
        private int totalOffers;
        private int nodes;

        private void consumeNode() {
            if (nodes >= MAX_JSON_NODES) throw new IllegalArgumentException("trade file JSON node limit exceeded");
            nodes++;
        }
    }

    private static JsonObject readRoot(String json) {
        try (var reader = new JsonReader(new StringReader(json))) {
            reader.setStrictness(Strictness.STRICT);
            JsonElement root = readJson(reader, 0, Shape.ROOT_OBJECT, new ParseState());
            if (!root.isJsonObject() || reader.peek() != JsonToken.END_DOCUMENT) {
                throw new IllegalArgumentException("trade file root must be one JSON object");
            }
            return root.getAsJsonObject();
        } catch (IOException | StackOverflowError e) {
            throw new IllegalArgumentException("invalid trade file JSON", e);
        }
    }

    private static JsonElement readJson(JsonReader reader, int depth, Shape shape, ParseState state)
            throws IOException {
        state.consumeNode();
        return switch (reader.peek()) {
            case BEGIN_OBJECT -> {
                if (depth >= MAX_JSON_DEPTH) throw new IllegalArgumentException("trade file nesting limit");
                reader.beginObject();
                var object = new JsonObject();
                var keys = new HashSet<String>();
                while (reader.hasNext()) {
                    if (object.size() >= maxObjectMembers(shape)) {
                        throw new IllegalArgumentException("trade file object member limit exceeded");
                    }
                    state.consumeNode();
                    String key = requireWellFormed(reader.nextName());
                    if (!keys.add(key)) throw new IllegalArgumentException("duplicate trade file object key");
                    object.add(key, readJson(reader, depth + 1, childShape(shape, key), state));
                }
                reader.endObject();
                yield object;
            }
            case BEGIN_ARRAY -> {
                if (depth >= MAX_JSON_DEPTH) throw new IllegalArgumentException("trade file nesting limit");
                reader.beginArray();
                var array = new JsonArray();
                int max = maxArrayLength(shape);
                while (reader.hasNext()) {
                    if (array.size() >= max) throw new IllegalArgumentException("trade file array limit exceeded");
                    if (shape == Shape.OFFERS && state.totalOffers >= MAX_TOTAL_OFFERS) {
                        throw new IllegalArgumentException("total offer limit exceeded");
                    }
                    array.add(readJson(reader, depth + 1, arrayElementShape(shape), state));
                    if (shape == Shape.OFFERS) state.totalOffers++;
                }
                reader.endArray();
                yield array;
            }
            case STRING -> new JsonPrimitive(requireWellFormed(reader.nextString()));
            case NUMBER -> new JsonPrimitive(new BigDecimal(reader.nextString()));
            case BOOLEAN -> new JsonPrimitive(reader.nextBoolean());
            case NULL -> {
                reader.nextNull();
                yield JsonNull.INSTANCE;
            }
            default -> throw new IllegalArgumentException("invalid trade file token");
        };
    }

    private static Shape childShape(Shape parent, String key) {
        return switch (parent) {
            case ROOT_OBJECT ->
                switch (key) {
                    case "traders" -> Shape.TRADERS;
                    case "history" -> Shape.HISTORY;
                    case "pinned" -> Shape.PINNED;
                    default -> Shape.OTHER;
                };
            case TRADER_OBJECT -> key.equals("offers") ? Shape.OFFERS : Shape.OTHER;
            case OFFER_OBJECT -> key.equals("baseInputs") || key.equals("effectiveInputs") ? Shape.INPUTS : Shape.OTHER;
            case PROGRESS_OBJECT ->
                switch (key) {
                    case "received" -> Shape.RECEIVED;
                    case "spent" -> Shape.SPENT_OBJECT;
                    default -> Shape.OTHER;
                };
            default -> Shape.OTHER;
        };
    }

    private static Shape arrayElementShape(Shape shape) {
        return switch (shape) {
            case TRADERS -> Shape.TRADER_OBJECT;
            case OFFERS -> Shape.OFFER_OBJECT;
            case HISTORY -> Shape.PROGRESS_OBJECT;
            default -> Shape.OTHER;
        };
    }

    private static int maxArrayLength(Shape shape) {
        return switch (shape) {
            case TRADERS -> MAX_TRADERS;
            case HISTORY -> MAX_HISTORY;
            case PINNED -> MAX_TRADERS;
            case OFFERS -> MAX_OFFERS_PER_TRADER;
            case INPUTS -> 2;
            case RECEIVED -> TradeProgress.MAX_RECEIPTS;
            default -> 8_192;
        };
    }

    private static int maxObjectMembers(Shape shape) {
        return shape == Shape.SPENT_OBJECT ? TradeProgress.MAX_INPUT_KINDS : 4_096;
    }

    private static String requireWellFormed(String value) {
        for (int i = 0; i < value.length(); i++) {
            char current = value.charAt(i);
            if (Character.isHighSurrogate(current)) {
                if (i + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(i + 1))) {
                    throw new IllegalArgumentException("unpaired surrogate in trade file");
                }
                i++;
            } else if (Character.isLowSurrogate(current)) {
                throw new IllegalArgumentException("unpaired surrogate in trade file");
            }
        }
        return value;
    }

    /** Identifies a well-formed file from a newer mod before corruption recovery can copy it. */
    public static final class UnsupportedVersionException extends IllegalArgumentException {
        private final int version;

        public UnsupportedVersionException(int version) {
            super("unsupported trade file version " + version);
            this.version = version;
        }

        public int version() {
            return version;
        }
    }

    /** Raised before the bounded writer stores a byte beyond the on-disk cap. */
    public static final class EncodedSizeExceeded extends RuntimeException {
        private EncodedSizeExceeded() {
            super("encoded trade file exceeds 8 MiB");
        }
    }

    private static final class BoundedOutputStream extends OutputStream {
        private final int maxBytes;
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();

        private BoundedOutputStream(int maxBytes) {
            this.maxBytes = maxBytes;
        }

        @Override
        public void write(int value) {
            if (buffer.size() >= maxBytes) throw new EncodedSizeExceeded();
            buffer.write(value);
        }

        @Override
        public void write(byte[] bytes, int offset, int length) {
            if (length > maxBytes - buffer.size()) throw new EncodedSizeExceeded();
            buffer.write(bytes, offset, length);
        }

        private byte[] toByteArray() {
            return buffer.toByteArray();
        }
    }
}
