package dev.friendai.features.social;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The part of the director's session state that must outlive a restart: the subjects already raised and the taste
 * learned from whether the owner answered.
 *
 * <p>Both halves are the exact shapes the live ring and taste use, so restoring them costs no conversion: the raised
 * list is oldest first and capped at {@link TopicRing#CAPACITY}, the taste map holds positive weights only (the answer
 * streaks are session-only) and is capped at {@link TopicTaste#MAX_TOPICS}. Decoding is strict and bounded: unknown
 * fields are ignored, and anything oversized, repeated or malformed yields an empty result so the caller starts clean
 * instead of trusting half a record.
 */
public record SocialMemorySave(List<String> raised, Map<String, Integer> taste) {
    /** Raised subjects kept, taken from the ring so the two bounds cannot drift apart. */
    public static final int MAX_RAISED = TopicRing.CAPACITY;
    /** Taste weights kept, taken from the taste model. */
    public static final int MAX_TASTE = TopicTaste.MAX_TOPICS;
    /** Longest subject or taste key accepted, the same bound the opener bank writes. */
    public static final int MAX_TOPIC_CHARS = TopicBank.MAX_LENGTH;
    /** A weight is a small count of answers, never an arbitrary number read from disk. */
    public static final int MAX_WEIGHT = 1_000;
    /** Decoded text larger than this is rejected before parsing; well above the two per-owner caps. */
    static final int MAX_INPUT = 32 * 1024;

    /** Nothing remembered yet, and what a missing, corrupt or newer record becomes. */
    public static final SocialMemorySave EMPTY = new SocialMemorySave(List.of(), Map.of());

    public SocialMemorySave {
        raised = List.copyOf(raised);
        taste = Map.copyOf(taste);
    }

    /** The JSON payload carried by the owner record; keys are sorted so an unchanged state encodes identically. */
    public String encode() {
        JsonObject root = new JsonObject();
        JsonArray topics = new JsonArray();
        for (String topic : raised) {
            topics.add(topic);
        }
        root.add("raised", topics);
        JsonObject weights = new JsonObject();
        taste.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> weights.addProperty(entry.getKey(), entry.getValue()));
        root.add("taste", weights);
        return root.toString();
    }

    /** The saved state, or empty when the text is missing, oversized, over a bound or broken. */
    public static Optional<SocialMemorySave> decode(String saved) {
        if (saved == null || saved.length() > MAX_INPUT) {
            return Optional.empty();
        }
        try {
            JsonObject root = JsonParser.parseString(saved).getAsJsonObject();
            List<String> raised = decodeRaised(root.getAsJsonArray("raised"));
            if (raised == null) {
                return Optional.empty();
            }
            Map<String, Integer> taste = decodeTaste(root.getAsJsonObject("taste"));
            if (taste == null) {
                return Optional.empty();
            }
            return Optional.of(new SocialMemorySave(raised, taste));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    private static List<String> decodeRaised(JsonArray topics) {
        if (topics == null || topics.size() > MAX_RAISED) {
            return null;
        }
        List<String> raised = new ArrayList<>(topics.size());
        for (JsonElement element : topics) {
            if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
                return null;
            }
            String topic = element.getAsString();
            if (!valid(topic) || raised.contains(topic)) {
                return null;
            }
            raised.add(topic);
        }
        return raised;
    }

    private static Map<String, Integer> decodeTaste(JsonObject weights) {
        if (weights == null || weights.size() > MAX_TASTE) {
            return null;
        }
        Map<String, Integer> taste = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : weights.entrySet()) {
            String topic = entry.getKey();
            int weight = entry.getValue().getAsInt();
            if (!valid(topic) || weight <= 0 || weight > MAX_WEIGHT) {
                return null;
            }
            taste.put(topic, weight);
        }
        return taste;
    }

    private static boolean valid(String topic) {
        return !topic.isBlank() && topic.length() <= MAX_TOPIC_CHARS;
    }
}
