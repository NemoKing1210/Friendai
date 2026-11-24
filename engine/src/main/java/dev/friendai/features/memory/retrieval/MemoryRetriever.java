package dev.friendai.features.memory.retrieval;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;

/**
 * Pure, bounded top-k selection over facts the caller may already see. Visibility is applied **before** scoring, so a
 * hidden fact is never embedded or ranked; the redactor is applied to the query and every candidate before the
 * embedder sees them, and again to the text that is returned. A missing/throwing backend returns an empty selection,
 * which lets the caller fall back to the ordinary memory renderer rather than showing an unbounded book.
 *
 * <p>Order is deterministic: score descending, then fact id ascending. A fact is shown only when its score reaches
 * both an absolute floor and half of the best score, so an unrelated question selects nothing instead of filling the
 * budget with old facts; rendering is capped by top-k and a character budget, so a long fact cannot crowd out the
 * prompt.
 */
public final class MemoryRetriever {
    /** The least cosine score a fact needs on its own, so a shared function word is not a match. */
    public static final double MIN_SCORE = 0.15;

    /** A fact must also reach this share of the best score, so a weak incidental overlap is not shown. */
    public static final double MIN_RELATIVE_SCORE = 0.35;

    /**
     * Function words that never make a fact relevant. They are dropped from the <em>query</em> only; the fact vectors
     * keep every token, so the persisted index and its cache key stay valid.
     */
    private static final java.util.Set<String> STOPWORDS = java.util.Set.of(
            "the", "and", "for", "are", "but", "not", "you", "your", "with", "this", "that", "from", "have", "has",
            "was", "were", "what", "when", "where", "which", "who", "how", "why", "can", "could", "would", "should",
            "will", "shall", "may", "might", "must", "its", "they", "them", "their", "there", "here", "then", "than",
            "into", "onto", "about", "over", "under", "again", "just", "very", "too", "also", "all", "any", "some",
            "one", "two", "out", "off", "our", "his", "her", "him", "she", "he", "we", "us", "my", "me", "is", "be",
            "been", "being", "do", "does", "did", "don't", "dont", "of", "to", "in", "on", "at", "by", "as", "or", "if",
            "so", "no", "yes", "и", "в", "во", "не", "что", "он", "на", "я", "с", "со", "как", "а", "то", "все", "она",
            "так", "его", "но", "да", "ты", "к", "у", "же", "вы", "за", "бы", "по", "только", "ее", "её", "мне", "было",
            "вот", "от", "меня", "еще", "ещё", "нет", "о", "из", "ему", "теперь", "когда", "даже", "ну", "вдруг", "ли",
            "если", "уже", "или", "ни", "быть", "был", "него", "до", "вас", "нибудь", "опять", "уж", "вам", "ведь",
            "там", "потом", "себя", "ничего", "ей", "может", "они", "тут", "где", "есть", "надо", "ней", "для", "мы",
            "тебя", "их", "чем", "была", "сам", "чтоб", "без", "будто", "чего", "раз", "тоже", "себе", "под", "будет",
            "ж", "тогда", "кто", "этот", "того", "потому", "этого", "какой", "совсем", "ним", "здесь", "этом", "один",
            "почти", "мой", "тем", "чтобы", "нее", "были", "куда", "зачем", "всех", "никогда", "можно", "при",
            "наконец", "два", "об", "другой", "хоть", "после", "над", "больше", "тот", "через", "эти", "нас", "про",
            "всего", "них", "какая", "много", "разве", "три", "эту", "моя", "впрочем", "хорошо", "свою", "этой",
            "перед", "иногда", "лучше", "чуть", "том", "нельзя", "такой", "им", "более", "всегда", "конечно", "всю",
            "между");

    private MemoryRetriever() {}

    /**
     * The tokens of a text that can make a fact relevant: runs of letters or digits of at least two characters with
     * the function words removed. A fact that shares no such token with the message is never selected, so a hashed
     * bucket collision or a shared "the" cannot dress up an unrelated fact as a match.
     */
    public static java.util.Set<String> contentTokens(String text) {
        if (text == null || text.isBlank()) {
            return java.util.Set.of();
        }
        java.util.Set<String> tokens = new java.util.LinkedHashSet<>();
        for (String token : text.toLowerCase(java.util.Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
            if (token.length() >= 2 && !STOPWORDS.contains(token)) {
                tokens.add(token);
            }
        }
        return java.util.Set.copyOf(tokens);
    }

    /** One fact as the retriever may see it; {@code visible} is decided by the caller's existing access switch. */
    public record Candidate(int id, String text, boolean visible) {
        public Candidate {
            if (id < 0) throw new IllegalArgumentException("id");
            text = text == null ? "" : text;
        }
    }

    /**
     * Selects from scratch: redacts and embeds the query and every visible candidate, then ranks. Used when no derived
     * index is available and by the pure tests.
     */
    public static List<Candidate> select(
            List<Candidate> candidates,
            String query,
            Embedder embedder,
            int topK,
            int maxChars,
            UnaryOperator<String> redactor) {
        if (candidates == null || candidates.isEmpty() || embedder == null || topK < 1 || maxChars < 1) {
            return List.of();
        }
        UnaryOperator<String> clean = redactor == null ? text -> text : redactor;
        float[] queryVector;
        try {
            queryVector = embedder.embed(clean.apply(query == null ? "" : query));
        } catch (RuntimeException failed) {
            return List.of();
        }
        if (queryVector == null || queryVector.length == 0) {
            return List.of();
        }
        Map<Integer, float[]> vectors = new LinkedHashMap<>();
        for (Candidate candidate : candidates) {
            if (candidate == null || !candidate.visible() || candidate.text().isBlank()) {
                continue;
            }
            float[] vector;
            try {
                vector = embedder.embed(clean.apply(candidate.text()));
            } catch (RuntimeException failed) {
                continue;
            }
            if (vector == null) {
                continue;
            }
            vectors.put(candidate.id(), vector);
        }
        return rank(candidates, clean.apply(query == null ? "" : query), queryVector, vectors, topK, maxChars, clean);
    }

    /**
     * Ranks already-embedded candidates against a query vector. The same function backs {@link #select} and the
     * derived index, so a cached vector and a freshly embedded one are scored by exactly one rule. The returned
     * candidates carry the redacted text the character budget was measured on, so a caller renders what was counted.
     */
    public static List<Candidate> rank(
            List<Candidate> candidates,
            float[] queryVector,
            Map<Integer, float[]> vectors,
            int topK,
            int maxChars,
            UnaryOperator<String> redactor) {
        return rank(candidates, null, queryVector, vectors, topK, maxChars, redactor);
    }

    /**
     * The same ranking with the query text, so a candidate must share a content token with it. Pass {@code null} only
     * when the caller has no query text; the score rule then stands alone.
     */
    public static List<Candidate> rank(
            List<Candidate> candidates,
            String query,
            float[] queryVector,
            Map<Integer, float[]> vectors,
            int topK,
            int maxChars,
            UnaryOperator<String> redactor) {
        if (candidates == null
                || candidates.isEmpty()
                || queryVector == null
                || queryVector.length == 0
                || topK < 1
                || maxChars < 1) {
            return List.of();
        }
        UnaryOperator<String> clean = redactor == null ? text -> text : redactor;
        Set<String> queryTokens = contentTokens(clean.apply(query == null ? "" : query));
        boolean tokenRule = query != null;
        List<Scored> scored = new ArrayList<>(Math.min(candidates.size(), topK * 4));
        for (Candidate candidate : candidates) {
            if (candidate == null || !candidate.visible() || candidate.text().isBlank()) {
                continue;
            }
            float[] vector = vectors == null ? null : vectors.get(candidate.id());
            if (vector == null || vector.length != queryVector.length) {
                continue;
            }
            String text = clean.apply(candidate.text());
            if (text.isBlank()) {
                continue;
            }
            if (tokenRule && (queryTokens.isEmpty() || !sharesAny(contentTokens(text), queryTokens))) {
                continue;
            }
            double score = cosine(queryVector, vector);
            if (score < MIN_SCORE) {
                continue;
            }
            scored.add(new Scored(candidate.id(), text, score));
        }
        double best = scored.stream().mapToDouble(Scored::score).max().orElse(0);
        double floor = best * MIN_RELATIVE_SCORE;
        scored.removeIf(row -> row.score() < floor);
        scored.sort(Comparator.comparingDouble(Scored::score).reversed().thenComparingInt(Scored::id));
        List<Candidate> selected = new ArrayList<>(Math.min(topK, scored.size()));
        int chars = 0;
        for (Scored row : scored) {
            if (selected.size() >= topK) {
                break;
            }
            if (chars + row.text().length() > maxChars) {
                continue;
            }
            selected.add(new Candidate(row.id(), row.text(), true));
            chars += row.text().length();
        }
        return List.copyOf(selected);
    }

    private static boolean sharesAny(Set<String> left, Set<String> right) {
        for (String token : left) {
            if (right.contains(token)) {
                return true;
            }
        }
        return false;
    }

    private record Scored(int id, String text, double score) {}

    private static double cosine(float[] left, float[] right) {
        double dot = 0, leftNorm = 0, rightNorm = 0;
        for (int i = 0; i < left.length; i++) {
            dot += left[i] * right[i];
            leftNorm += left[i] * left[i];
            rightNorm += right[i] * right[i];
        }
        if (leftNorm == 0 || rightNorm == 0) {
            return 0;
        }
        return dot / (Math.sqrt(leftNorm) * Math.sqrt(rightNorm));
    }
}
