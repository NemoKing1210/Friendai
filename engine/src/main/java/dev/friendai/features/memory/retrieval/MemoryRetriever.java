package dev.friendai.features.memory.retrieval;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.UnaryOperator;

/**
 * Pure, bounded top-k selection over facts the caller may already see. Visibility is applied **before** scoring, so a
 * hidden fact is never embedded or ranked; the redactor is applied to the query and every candidate before the
 * embedder sees them. A missing/throwing backend returns an empty selection, which lets the caller fall back to the
 * ordinary memory renderer rather than showing an unbounded book.
 *
 * <p>Order is deterministic: score descending, then fact id ascending. Rendering is capped by both top-k and a
 * character budget, so a long fact cannot crowd out the prompt.
 */
public final class MemoryRetriever {
    private MemoryRetriever() {}

    /** One fact as the retriever may see it; {@code visible} is decided by the caller's existing access switch. */
    public record Candidate(int id, String text, boolean visible) {
        public Candidate {
            if (id < 0) throw new IllegalArgumentException("id");
            text = text == null ? "" : text;
        }
    }

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
        List<Scored> scored = new ArrayList<>(candidates.size());
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
            if (vector == null || vector.length != queryVector.length) {
                continue;
            }
            scored.add(new Scored(candidate, cosine(queryVector, vector)));
        }
        scored.sort(Comparator.comparingDouble(Scored::score)
                .reversed()
                .thenComparingInt(scoredCandidate -> scoredCandidate.candidate().id()));
        List<Candidate> selected = new ArrayList<>(Math.min(topK, scored.size()));
        int chars = 0;
        for (Scored row : scored) {
            if (selected.size() >= topK) {
                break;
            }
            int length = row.candidate().text().length();
            if (chars + length > maxChars) {
                continue;
            }
            selected.add(row.candidate());
            chars += length;
        }
        return List.copyOf(selected);
    }

    private record Scored(Candidate candidate, double score) {}

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

    /** Null-safe text identity used by the cache and by tests. */
    public static long revision(String text) {
        return Objects.hash(text == null ? "" : text);
    }
}
