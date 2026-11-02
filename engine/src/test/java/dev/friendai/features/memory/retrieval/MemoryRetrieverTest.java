package dev.friendai.features.memory.retrieval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MemoryRetrieverTest {
    /** A deterministic bag-of-words embedder: shared words score higher. */
    private static final class BagEmbedder implements Embedder {
        final List<String> embedded = new ArrayList<>();

        @Override
        public float[] embed(String text) {
            embedded.add(text);
            float[] vector = new float[32];
            for (String word : text.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
                if (word.isBlank()) continue;
                vector[Math.floorMod(word.hashCode(), vector.length)] += 1f;
            }
            return vector;
        }
    }

    private static MemoryRetriever.Candidate fact(int id, String text) {
        return new MemoryRetriever.Candidate(id, text, true);
    }

    @Test
    void hiddenFactsAreNeverEmbeddedOrRanked() {
        BagEmbedder embedder = new BagEmbedder();
        var selected = MemoryRetriever.select(
                List.of(
                        new MemoryRetriever.Candidate(1, "the secret is under the bed", false),
                        fact(2, "the pickaxe is in the chest")),
                "pickaxe",
                embedder,
                5,
                500,
                null);

        assertEquals(1, selected.size());
        assertEquals(2, selected.getFirst().id());
        assertFalse(embedder.embedded.stream().anyMatch(text -> text.contains("secret")));
    }

    @Test
    void theMostRelevantFactRanksFirstAndTiesUseTheId() {
        var selected = MemoryRetriever.select(
                List.of(
                        fact(3, "the cow is friendly"),
                        fact(1, "I need an iron pickaxe"),
                        fact(2, "iron ore is deep"),
                        fact(4, "iron pickaxe iron pickaxe")),
                "iron pickaxe",
                new BagEmbedder(),
                3,
                500,
                null);

        assertEquals(
                List.of(4, 1, 2),
                selected.stream().map(MemoryRetriever.Candidate::id).toList());
    }

    @Test
    void topKAndCharacterBudgetAreBothEnforced() {
        var selected = MemoryRetriever.select(
                List.of(fact(1, "aaaa"), fact(2, "bbbb"), fact(3, "cccc"), fact(4, "dddd")),
                "a b c d",
                new BagEmbedder(),
                2,
                8,
                null);
        assertEquals(2, selected.size());
        assertEquals(8, selected.stream().mapToInt(c -> c.text().length()).sum());
    }

    @Test
    void theRedactorSeesTheQueryAndEveryCandidate() {
        BagEmbedder embedder = new BagEmbedder();
        MemoryRetriever.select(
                List.of(fact(1, "my password is hunter2")),
                "password?",
                embedder,
                1,
                500,
                text -> text.replace("hunter2", "[redacted]"));

        assertTrue(embedder.embedded.stream().anyMatch(text -> text.contains("[redacted]")));
        assertFalse(embedder.embedded.stream().anyMatch(text -> text.contains("hunter2")));
    }

    @Test
    void aFailingOrMissingBackendFallsBackToNothing() {
        assertTrue(MemoryRetriever.select(List.of(fact(1, "x")), "x", null, 1, 10, null)
                .isEmpty());
        assertTrue(MemoryRetriever.select(
                        List.of(fact(1, "x")),
                        "x",
                        text -> {
                            throw new IllegalStateException("provider down");
                        },
                        1,
                        10,
                        null)
                .isEmpty());
        assertTrue(MemoryRetriever.select(List.of(), "x", new BagEmbedder(), 1, 10, null)
                .isEmpty());
        assertTrue(MemoryRetriever.select(List.of(fact(1, "x")), "x", new BagEmbedder(), 0, 10, null)
                .isEmpty());
    }

    @Test
    void theCacheRejectsStaleRevisionsAndForgetsImmediately() {
        RetrievalCache cache = new RetrievalCache();
        long revision = MemoryRetriever.revision("I like oak");
        cache.put(7, revision, new float[] {1f, 0f});
        assertTrue(cache.get(7, revision).isPresent());
        assertTrue(cache.get(7, MemoryRetriever.revision("I like stone")).isEmpty(), "an edited fact misses");

        RetrievalCache.Entry entry = cache.get(7, revision).orElseThrow();
        assertTrue(RetrievalCache.stale(entry, MemoryRetriever.revision("I like stone")));
        assertTherefore(cache.remove(7));
        assertTrue(cache.get(7, revision).isEmpty(), "a forgotten fact has no vector");
    }

    @Test
    void theCacheIsBoundedAndCopiesVectors() {
        RetrievalCache cache = new RetrievalCache();
        for (int i = 0; i < RetrievalCache.MAX_ENTRIES + 1; i++) {
            cache.put(i, MemoryRetriever.revision("t" + i), new float[] {i});
        }
        assertEquals(RetrievalCache.MAX_ENTRIES, cache.size());
        assertTrue(cache.get(0, MemoryRetriever.revision("t0")).isEmpty(), "the oldest left");
        assertTrue(cache.get(RetrievalCache.MAX_ENTRIES, MemoryRetriever.revision("t" + RetrievalCache.MAX_ENTRIES))
                .isPresent());

        float[] vector = {1f, 2f};
        cache.put(99, 5L, vector);
        vector[0] = 99f;
        assertEquals(1f, cache.get(99, 5L).orElseThrow().vector()[0], "the cache must not alias the caller's array");
        Map<Integer, RetrievalCache.Entry> snapshot = cache.snapshot();
        assertEquals(RetrievalCache.MAX_ENTRIES, snapshot.size(), "snapshot is a complete copy");
        assertEquals(RetrievalCache.MAX_ENTRIES, cache.size(), "snapshot does not own the cache");
    }

    private static void assertTherefore(boolean value) {
        assertTrue(value);
    }
}
