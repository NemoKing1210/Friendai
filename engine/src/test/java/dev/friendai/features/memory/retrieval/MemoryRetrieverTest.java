package dev.friendai.features.memory.retrieval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
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
        var selected = MemoryRetriever.select(
                List.of(fact(1, "my password is hunter2")),
                "password?",
                embedder,
                1,
                500,
                text -> text.replace("hunter2", "[redacted]"));

        assertTrue(embedder.embedded.stream().anyMatch(text -> text.contains("[redacted]")));
        assertFalse(embedder.embedded.stream().anyMatch(text -> text.contains("hunter2")));
        assertEquals(
                "[redacted]",
                selected.getFirst().text().substring(selected.getFirst().text().indexOf('[')),
                "the caller renders the text the budget was measured on");
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
    void rankingAgainstKnownVectorsMatchesEmbeddingFromScratch() {
        List<MemoryRetriever.Candidate> candidates =
                List.of(fact(1, "the lake is north"), fact(2, "likes oak logs"), fact(3, "the lake is deep"));
        BagEmbedder embedder = new BagEmbedder();
        float[] query = embedder.embed("where is the lake");
        Map<Integer, float[]> vectors = new LinkedHashMap<>();
        for (MemoryRetriever.Candidate candidate : candidates) {
            vectors.put(candidate.id(), embedder.embed(candidate.text()));
        }

        assertEquals(
                MemoryRetriever.select(candidates, "where is the lake", embedder, 2, 500, null),
                MemoryRetriever.rank(candidates, query, vectors, 2, 500, null),
                "a cached vector and a fresh one must be scored by one rule");
    }

    @Test
    void rankSkipsVisibleFactsWithoutAVectorAndHiddenFacts() {
        var selected = MemoryRetriever.rank(
                List.of(
                        fact(1, "the lake is north"),
                        new MemoryRetriever.Candidate(2, "the lake is secret", false),
                        fact(3, "the lake is east")),
                new BagEmbedder().embed("lake"),
                Map.of(
                        1,
                        new BagEmbedder().embed("the lake is north"),
                        2,
                        new BagEmbedder().embed("the lake is secret")),
                5,
                500,
                null);
        assertEquals(
                List.of(1), selected.stream().map(MemoryRetriever.Candidate::id).toList());
    }

    @Test
    void theCacheKeyCoversOwnerPartitionFactsBackendAndWidth() {
        RetrievalCache cache = new RetrievalCache();
        long facts = 7L;
        RetrievalCache.Key key =
                key("owner", "OWNER:owner", facts, 11L, LexicalEmbedder.ID, LexicalEmbedder.DIMENSIONS);
        RetrievalIndex index = index(facts, 11L, LexicalEmbedder.DIMENSIONS);
        assertTrue(cache.publish(cache.captureEpoch(), key, index));
        assertTrue(cache.get(key).isPresent());

        assertTrue(
                cache.get(key("other", "OWNER:owner", facts, 11L, LexicalEmbedder.ID, LexicalEmbedder.DIMENSIONS))
                        .isEmpty(),
                "another owner is another slot");
        assertTrue(
                cache.get(key("owner", "GUEST:g", facts, 11L, LexicalEmbedder.ID, LexicalEmbedder.DIMENSIONS))
                        .isEmpty(),
                "another principal never reuses the owner's index");
        assertTrue(
                cache.get(key("owner", "OWNER:owner", 8L, 11L, LexicalEmbedder.ID, LexicalEmbedder.DIMENSIONS))
                        .isEmpty(),
                "an edited fact moves the key");
        assertTrue(
                cache.get(key("owner", "OWNER:owner", facts, 11L, "lexical-bow-v2", LexicalEmbedder.DIMENSIONS))
                        .isEmpty(),
                "another backend never reuses the vectors");
        assertTrue(
                cache.get(key("owner", "OWNER:owner", facts, 11L, LexicalEmbedder.ID, 64))
                        .isEmpty(),
                "another width never reuses the vectors");
        assertTrue(
                cache.get(key("owner", "OWNER:owner", facts, 12L, LexicalEmbedder.ID, LexicalEmbedder.DIMENSIONS))
                        .isEmpty(),
                "a changed redaction mask never reuses the vectors");
        assertEquals(64, key.digest().length(), "a full SHA-256 digest, not a 32-bit hash");
    }

    @Test
    void aPublishAcrossAnInvalidationIsRejected() {
        RetrievalCache cache = new RetrievalCache();
        RetrievalCache.Key key = key("owner", "OWNER:owner", 7L, 11L, LexicalEmbedder.ID, LexicalEmbedder.DIMENSIONS);
        long epoch = cache.captureEpoch();
        cache.invalidate();
        assertFalse(
                cache.publish(epoch, key, index(7L, 11L, LexicalEmbedder.DIMENSIONS)), "an owner switch rejects it");
        assertEquals(0, cache.size());
    }

    @Test
    void theCacheIsBoundedAndPrunesOnlyTheForgetfulOwner() {
        RetrievalCache cache = new RetrievalCache();
        for (int i = 0; i < RetrievalCache.MAX_SCOPES + 1; i++) {
            RetrievalCache.Key key =
                    key("owner" + i, "OWNER:owner" + i, i, 11L, LexicalEmbedder.ID, LexicalEmbedder.DIMENSIONS);
            assertTrue(cache.publish(cache.captureEpoch(), key, index(i, 11L, LexicalEmbedder.DIMENSIONS)));
        }
        assertEquals(RetrievalCache.MAX_SCOPES, cache.size(), "the oldest slot left");

        RetrievalCache.Key current =
                key("owner8", "OWNER:owner8", 8L, 11L, LexicalEmbedder.ID, LexicalEmbedder.DIMENSIONS);
        assertTrue(cache.get(current).isPresent());
        cache.prune("owner8", "OWNER:owner8", 9L, 11L);
        assertTrue(cache.get(current).isEmpty(), "a forget drops the principal's slot at once");

        RetrievalCache.Key untouched =
                key("owner7", "OWNER:owner7", 7L, 11L, LexicalEmbedder.ID, LexicalEmbedder.DIMENSIONS);
        cache.prune("owner6", "OWNER:owner6", 11L, 11L);
        assertTrue(cache.get(untouched).isPresent(), "another owner's forget leaves this one alone");

        RetrievalCache.Key otherPartition =
                key("owner7", "TRUSTED:someone", 7L, 11L, LexicalEmbedder.ID, LexicalEmbedder.DIMENSIONS);
        cache.publish(cache.captureEpoch(), otherPartition, index(7L, 11L, LexicalEmbedder.DIMENSIONS));
        cache.prune("owner7", "OWNER:owner7", 12L, 11L);
        assertTrue(cache.get(untouched).isEmpty(), "the principal's own stale slot is dropped");
        assertTrue(
                cache.get(otherPartition).isPresent(),
                "another principal of the same owner keeps its index, so alternating turns do not rebuild");
    }

    @Test
    void theDigestIsStableAndWiderThanAnInt() {
        assertEquals(RetrievalDigest.of("I like oak"), RetrievalIndex.revision("I like oak"));
        assertEquals(RetrievalIndex.revision("I like oak"), RetrievalIndex.revision("I like oak"));
        assertNotEquals(RetrievalIndex.revision("I like oak"), RetrievalIndex.revision("I like stone"));
        assertEquals(RetrievalDigest.of(""), RetrievalIndex.revision(null));
        assertNotEquals(RetrievalDigest.key("a", "bc"), RetrievalDigest.key("ab", "c"), "parts do not run together");
        assertEquals(64, RetrievalDigest.key("a", "b").length());
    }

    private static RetrievalCache.Key key(
            String owner, String partition, long facts, long masked, String backend, int dimensions) {
        return new RetrievalCache.Key(owner, partition, facts, masked, backend, dimensions);
    }

    private static RetrievalIndex index(long facts, long masked, int dimensions) {
        float[] vector = new float[dimensions];
        vector[0] = 1f;
        return new RetrievalIndex(
                RetrievalIndex.FORMAT_VERSION,
                LexicalEmbedder.ID,
                dimensions,
                facts,
                masked,
                List.of(new RetrievalIndex.Entry(7, RetrievalIndex.revision("I like oak"), vector)));
    }
}
