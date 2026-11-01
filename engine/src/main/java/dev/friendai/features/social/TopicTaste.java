package dev.friendai.features.social;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * What the owner seems to like talking about, learned only from whether they answered.
 *
 * <p>Two answers to a subject raise its weight by one; an ignored subject loses one and forgets its streak. Nothing is
 * inferred from the words themselves, a weight never changes what the companion may say, and the map is bounded.
 */
public final class TopicTaste {
    public static final int MAX_TOPICS = 64;
    public static final int ANSWERS_PER_WEIGHT = 2;

    private final Map<String, Integer> weights = new HashMap<>();
    private final Map<String, Integer> answers = new HashMap<>();

    /** The owner replied after this subject was raised. */
    public void answered(String topic) {
        String key = key(topic);
        if (key.isEmpty()) {
            return;
        }
        int streak = answers.merge(key, 1, Integer::sum);
        if (streak % ANSWERS_PER_WEIGHT == 0) {
            weights.merge(key, 1, Integer::sum);
        }
        evict();
    }

    /** The owner said nothing worth counting after this subject was raised. */
    public void ignored(String topic) {
        String key = key(topic);
        if (key.isEmpty()) {
            return;
        }
        answers.remove(key);
        int weight = weights.getOrDefault(key, 0) - 1;
        if (weight <= 0) {
            weights.remove(key);
        } else {
            weights.put(key, weight);
        }
    }

    public int weight(String topic) {
        return weights.getOrDefault(key(topic), 0);
    }

    /** The first topic in the given order that carries the highest weight, or null when everything is equal. */
    public String best(List<String> topics) {
        String best = null;
        int bestWeight = 0;
        for (String topic : topics) {
            int weight = weight(topic);
            if (weight > bestWeight) {
                best = topic;
                bestWeight = weight;
            }
        }
        return best;
    }

    public int size() {
        return weights.size();
    }

    private void evict() {
        while (weights.size() > MAX_TOPICS) {
            String weakest = null;
            int lowest = Integer.MAX_VALUE;
            for (Map.Entry<String, Integer> entry : weights.entrySet()) {
                if (entry.getValue() < lowest) {
                    lowest = entry.getValue();
                    weakest = entry.getKey();
                }
            }
            if (weakest == null) {
                return;
            }
            weights.remove(weakest);
            answers.remove(weakest);
        }
    }

    private static String key(String topic) {
        return topic == null ? "" : topic.strip().toLowerCase(Locale.ROOT);
    }
}
