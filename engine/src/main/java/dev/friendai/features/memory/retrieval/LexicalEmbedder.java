package dev.friendai.features.memory.retrieval;

import java.util.Locale;

/**
 * The shipped, dependency-free backend: a hashed bag of words. It is <b>lexical, not semantic</b> — it scores how many
 * words a fact shares with the player's current message, so "the oak chest holds my iron" matches "where is my iron",
 * and "my mine entrance is a cave mouth" does not match "mining" through meaning. The config value is named
 * {@code lexical} for exactly that reason.
 *
 * <p>A token is a run of letters or digits of at least two characters, lowercased; each token is placed in one of
 * {@link #DIMENSIONS} buckets by a stable digest of the token, weighted by {@code 1 + ln(count)} and scaled to unit
 * length, so scoring is a plain dot product. Only the bucket counts are kept: the vector cannot be read back as text.
 *
 * <p>{@link #ID} is part of every cache key and of the persisted index. Changing the tokenizer, the weights or the
 * dimension without bumping it would silently mix two vector spaces, so any such change needs a new version instead.
 */
public final class LexicalEmbedder implements Embedder {
    /** Versioned backend name persisted with the derived index and used in cache keys. */
    public static final String ID = "lexical-bow-v1";

    /** Vector width; a bucket collision costs a little precision and no correctness. */
    public static final int DIMENSIONS = 128;

    /** The most tokens one text contributes, so a pasted wall of text costs bounded work. */
    private static final int MAX_TOKENS = 512;

    @Override
    public float[] embed(String text) {
        if (text == null || text.isEmpty()) {
            return new float[0];
        }
        int[] counts = new int[DIMENSIONS];
        String lower = text.toLowerCase(Locale.ROOT);
        int tokens = 0;
        int index = 0;
        while (index < lower.length() && tokens < MAX_TOKENS) {
            int start = index;
            while (index < lower.length() && isWordChar(lower.charAt(index))) {
                index++;
            }
            if (index - start >= 2) {
                long digest = RetrievalDigest.of(lower.substring(start, index));
                counts[(int) Math.floorMod(digest, DIMENSIONS)]++;
                tokens++;
            }
            index++;
        }
        if (tokens == 0) {
            return new float[0];
        }
        float[] vector = new float[DIMENSIONS];
        double norm = 0;
        for (int i = 0; i < DIMENSIONS; i++) {
            if (counts[i] == 0) {
                continue;
            }
            vector[i] = (float) (1.0 + Math.log(counts[i]));
            norm += vector[i] * (double) vector[i];
        }
        double length = Math.sqrt(norm);
        if (length == 0) {
            return new float[0];
        }
        for (int i = 0; i < DIMENSIONS; i++) {
            vector[i] = (float) (vector[i] / length);
        }
        return vector;
    }

    /**
     * Letters and digits, in every script, so a Russian, Greek or Korean message tokenizes without a language table.
     * Scripts written without spaces between words (Japanese, Chinese) become one long token per run; that is a
     * lexical limitation of this version, not a correctness bug, and a better tokenizer must bump {@link #ID}.
     */
    private static boolean isWordChar(char value) {
        return Character.isLetterOrDigit(value);
    }
}
