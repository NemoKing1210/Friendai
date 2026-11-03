package dev.friendai.features.memory.retrieval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.friendai.execution.tool.AccessLevel;
import dev.friendai.execution.tool.Caller;
import dev.friendai.features.memory.Category;
import dev.friendai.features.memory.Fact;
import dev.friendai.features.memory.MemoryBook;
import dev.friendai.features.memory.MemoryLimits;
import dev.friendai.features.memory.MemoryRecord;
import dev.friendai.features.memory.Subject;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

class MemoryRetrievalTest {
    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID GUEST = UUID.fromString("00000000-0000-0000-0000-0000000000b2");
    private static final UUID TRUSTED = UUID.fromString("00000000-0000-0000-0000-0000000000c3");
    private static final MemoryLimits LIMITS = MemoryLimits.DEFAULTS;
    private static final Gson GSON =
            new GsonBuilder().setPrettyPrinting().serializeNulls().create();

    private static Caller owner() {
        return new Caller(OWNER, "Ann", AccessLevel.OWNER);
    }

    private static Caller guest() {
        return new Caller(GUEST, "Gus", AccessLevel.GUEST);
    }

    private static Caller trusted() {
        return new Caller(TRUSTED, "Tess", AccessLevel.TRUSTED);
    }

    private static MemoryRetrieval.Settings settings(int topK) {
        return new MemoryRetrieval.Settings(true, topK, 600);
    }

    /** The real backend, plus every text it was asked to embed. */
    private static final class Recording implements Embedder {
        private final Embedder delegate = new LexicalEmbedder();
        final List<String> seen = new ArrayList<>();

        @Override
        public float[] embed(String text) {
            seen.add(text);
            return delegate.embed(text);
        }
    }

    /** The real backend, but the first call runs a hook that mutates the world mid-build. */
    private static final class Hooking implements Embedder {
        private final Embedder delegate = new LexicalEmbedder();
        private final Runnable hook;
        private int calls;

        Hooking(Runnable hook) {
            this.hook = hook;
        }

        @Override
        public float[] embed(String text) {
            if (calls++ == 0 && hook != null) {
                hook.run();
            }
            return delegate.embed(text);
        }
    }

    private static final class Counting implements Embedder {
        private final Embedder delegate = new LexicalEmbedder();
        int calls;

        @Override
        public float[] embed(String text) {
            calls++;
            return delegate.embed(text);
        }
    }

    private static MemoryBook bookWithFacts() {
        MemoryBook book = new MemoryBook();
        book.ensurePlayer(OWNER, "Ann");
        book.ensurePlayer(GUEST, "Gus");
        book.addFact(Subject.player(OWNER), Category.PREFERENCE, "Likes oak logs", OWNER, "Ann", 0, LIMITS);
        book.addFact(Subject.player(OWNER), Category.NOTE, "Keeps diamonds under the bed", OWNER, "Ann", 0, LIMITS);
        book.addFact(Subject.WORLD, Category.PLACE, "The lake is north of the base", OWNER, "Ann", 0, LIMITS);
        book.addFact(Subject.WORLD, Category.NOTE, "The cave mouth is by the river", OWNER, "Ann", 0, LIMITS);
        book.addFact(Subject.player(GUEST), Category.PREFERENCE, "Likes stone axes", GUEST, "Gus", 0, LIMITS);
        return book;
    }

    @Test
    void theRelevantlyMatchingFactRanksIntoTheSelectionAndUnselectedFactsNeverRender() {
        MemoryBook book = bookWithFacts();
        String block = MemoryRetrieval.prompt(
                book, OWNER, owner(), "Where is the lake?", settings(1), new RetrievalCache(), null);

        assertTrue(block.contains("The lake is north of the base"), block);
        assertTrue(block.contains("word match, not meaning"), "the block labels the lexical backend honestly");
        assertFalse(block.contains("Likes oak logs"), block);
        assertFalse(block.contains("The cave mouth is by the river"), block);
        assertFalse(block.contains("Keeps diamonds"), block);
        assertFalse(block.contains("Likes stone axes"), "the owner never sees a guest's fact here");

        String two = MemoryRetrieval.prompt(
                book, OWNER, owner(), "Where is the lake?", settings(2), new RetrievalCache(), null);
        assertTrue(two.contains("The lake is north"), two);
        assertTrue(two.contains("The cave mouth is by the river"), "the second-best fact fills the second slot");
        assertTrue(two.indexOf("The lake is north") < two.indexOf("The cave mouth"), "best match first");
    }

    @Test
    void aGuestNeverRetrievesOrEmbedsTheOwnersFacts() {
        MemoryBook book = bookWithFacts();
        Recording embedding = new Recording();
        String block = MemoryRetrieval.prompt(
                book, OWNER, guest(), "diamonds under the bed", settings(4), new RetrievalCache(), null, embedding);

        assertFalse(block.contains("diamonds"), block);
        assertFalse(
                embedding.seen.stream().anyMatch(text -> text.contains("Keeps diamonds")),
                "the owner's fact was never embedded for a guest: " + embedding.seen);
        assertFalse(block.contains("The lake"), "a guest does not read world facts");
        assertFalse(block.contains("Likes oak logs"), "a guest does not read the owner's facts");
        assertTrue(block.contains("Likes stone axes"), block);
    }

    @Test
    void aTrustedPlayerSeesTheWorldAndTheirOwnFactsButNotTheOwners() {
        MemoryBook book = bookWithFacts();
        String block = MemoryRetrieval.prompt(
                book, OWNER, trusted(), "diamonds under the bed", settings(4), new RetrievalCache(), null);

        assertFalse(block.contains("diamonds"), block);
        assertFalse(block.contains("Likes oak logs"), block);
        assertTrue(block.contains("The lake is north"), "world facts are visible to a trusted player");
    }

    @Test
    void theQueryAndFactTextAreRedactedBeforeEmbeddingAndRendering() {
        MemoryBook book = new MemoryBook();
        book.addFact(Subject.player(OWNER), Category.NOTE, "My vault code is hunter2", OWNER, "Ann", 0, LIMITS);
        book.addFact(Subject.WORLD, Category.NOTE, "The anvil sits by the door", OWNER, "Ann", 0, LIMITS);
        Recording embedding = new Recording();
        UnaryOperator<String> redactor = text -> text.replace("hunter2", "[secret]");

        String block = MemoryRetrieval.prompt(
                book,
                OWNER,
                owner(),
                "what is my vault code hunter2",
                settings(2),
                new RetrievalCache(),
                redactor,
                embedding);

        assertTrue(block.contains("[secret]"), block);
        assertFalse(block.contains("hunter2"), block);
        assertFalse(embedding.seen.stream().anyMatch(text -> text.contains("hunter2")), "the backend never sees it");
        assertTrue(embedding.seen.stream().anyMatch(text -> text.contains("[secret]")));
    }

    @Test
    void aChangedRedactionRebuildsTheIndexInsteadOfReusingTheOldVectors() {
        MemoryBook book = new MemoryBook();
        book.addFact(Subject.WORLD, Category.NOTE, "The vault code is hunter2", OWNER, "Ann", 0, LIMITS);
        book.addFact(Subject.WORLD, Category.NOTE, "The anvil sits by the door", OWNER, "Ann", 0, LIMITS);
        RetrievalCache cache = new RetrievalCache();
        UnaryOperator<String> first = text -> text.replace("hunter2", "[secret]");
        String before = MemoryRetrieval.prompt(book, OWNER, owner(), "vault code", settings(2), cache, first);
        assertTrue(before.contains("[secret]"), before);
        assertFalse(before.contains("hunter2"), before);

        UnaryOperator<String> second = text -> first.apply(text).replace("anvil", "[item]");
        Counting counting = new Counting();
        String after = MemoryRetrieval.prompt(book, OWNER, owner(), "vault code", settings(2), cache, second, counting);
        assertTrue(after.contains("[item]"), after);
        assertFalse(after.contains("anvil"), after);
        assertTrue(counting.calls > 1, "a changed mask rebuilds instead of reusing the old vectors");
    }

    @Test
    void forgettingAFactInvalidatesTheIndexAndTheVectorsAtOnce() {
        MemoryBook book = bookWithFacts();
        RetrievalCache cache = new RetrievalCache();
        String first = MemoryRetrieval.prompt(book, OWNER, owner(), "where is the lake", settings(3), cache, null);
        assertTrue(first.contains("The lake is north"), first);
        assertEquals(1, cache.size());
        assertTrue(book.retrievalIndex(OWNER).isPresent());

        int id = book.allFacts().stream()
                .filter(fact -> fact.text().startsWith("The lake"))
                .findFirst()
                .orElseThrow()
                .id();
        assertTrue(book.removeFact(id));

        assertTrue(book.retrievalIndex(OWNER).isEmpty(), "a forgotten fact leaves no index behind");
        String second = MemoryRetrieval.prompt(book, OWNER, owner(), "where is the lake", settings(3), cache, null);
        assertFalse(second.contains("The lake is north"), second);
        assertEquals(1, cache.size(), "the stale slot was pruned, not kept beside the new one");
    }

    @Test
    void editingAFactReplacesItsVectorInsteadOfKeepingTheOldText() {
        MemoryBook book = bookWithFacts();
        RetrievalCache cache = new RetrievalCache();
        String first = MemoryRetrieval.prompt(book, OWNER, owner(), "where is the lake", settings(2), cache, null);
        assertTrue(first.contains("north"), first);

        Fact lake = book.allFacts().stream()
                .filter(fact -> fact.text().startsWith("The lake"))
                .findFirst()
                .orElseThrow();
        book.editFact(lake.id(), Category.PLACE, "The lake is south of the base", OWNER, "Ann", 1, LIMITS);
        assertTrue(book.retrievalIndex(OWNER).isEmpty(), "an edited fact makes the stored index stale");

        String second = MemoryRetrieval.prompt(book, OWNER, owner(), "where is the lake", settings(2), cache, null);
        assertTrue(second.contains("south"), second);
        assertFalse(second.contains("north"), "the replaced text is gone from the block");
    }

    @Test
    void aPersistedIndexIsReusedAfterARestartAndRebuiltWhenFactsChanged() {
        MemoryBook book = bookWithFacts();
        String first = MemoryRetrieval.prompt(book, OWNER, owner(), "where is the lake", settings(2), null, null);
        assertEquals("", first, "a missing cache is not a crash");

        RetrievalCache cache = new RetrievalCache();
        String built = MemoryRetrieval.prompt(book, OWNER, owner(), "where is the lake", settings(2), cache, null);
        assertTrue(built.contains("The lake is north"), built);
        RetrievalIndex stored = book.retrievalIndex(OWNER).orElseThrow();

        // A restart, as MemoryStore performs it: the record round-trips through JSON into a new book.
        MemoryRecord reloaded = GSON.fromJson(GSON.toJson(MemoryRecord.capture(book)), MemoryRecord.class);
        MemoryBook restored = MemoryBook.fromRecord(reloaded);
        assertEquals(stored, restored.retrievalIndex(OWNER).orElseThrow(), "the derived index survives the restart");

        Counting counting = new Counting();
        String afterRestart = MemoryRetrieval.prompt(
                restored, OWNER, owner(), "where is the lake", settings(2), new RetrievalCache(), null, counting);
        assertTrue(afterRestart.contains("The lake is north"), afterRestart);
        assertEquals(1, counting.calls, "only the query is embedded when the persisted index still covers the facts");

        Fact lake = restored.allFacts().stream()
                .filter(fact -> fact.text().startsWith("The lake"))
                .findFirst()
                .orElseThrow();
        restored.editFact(lake.id(), Category.PLACE, "The lake is east of the base", OWNER, "Ann", 2, LIMITS);
        assertTrue(restored.retrievalIndex(OWNER).isEmpty(), "a changed fact makes the persisted index stale");

        Counting rebuilt = new Counting();
        String afterEdit = MemoryRetrieval.prompt(
                restored, OWNER, owner(), "where is the lake", settings(2), new RetrievalCache(), null, rebuilt);
        assertTrue(afterEdit.contains("east"), afterEdit);
        assertTrue(rebuilt.calls > 1, "the index was rebuilt from the current facts");
    }

    @Test
    void aForgetDuringTheBuildIsRejectedAndNothingIsPublished() {
        MemoryBook book = bookWithFacts();
        RetrievalCache cache = new RetrievalCache();
        int last = book.allFacts().getLast().id();

        String block = MemoryRetrieval.prompt(
                book,
                OWNER,
                owner(),
                "where is the lake",
                settings(3),
                cache,
                null,
                new Hooking(() -> book.removeFact(last)));

        assertEquals("", block, "a selection built across a forget is dropped");
        assertEquals(0, cache.size(), "the late result was never cached");
        assertTrue(book.retrievalIndex(OWNER).isEmpty(), "and never persisted");
    }

    @Test
    void anOwnerSwitchDuringTheBuildIsRejected() {
        MemoryBook book = bookWithFacts();
        RetrievalCache cache = new RetrievalCache();

        String block = MemoryRetrieval.prompt(
                book, OWNER, owner(), "where is the lake", settings(3), cache, null, new Hooking(cache::invalidate));

        assertEquals("", block, "an index built for the old principal is dropped");
        assertEquals(0, cache.size());
        assertTrue(book.retrievalIndex(OWNER).isEmpty());
        assertTrue(cache.get(new RetrievalCache.Key(
                        OWNER.toString(), "OWNER:" + OWNER, 1L, 1L, LexicalEmbedder.ID, LexicalEmbedder.DIMENSIONS))
                .isEmpty());
    }

    @Test
    void aMissingOrUnusableIndexRebuildsAndABrokenBackendFallsBackToTheBook() {
        MemoryBook book = bookWithFacts();
        // A structurally valid index from another backend: the book trusts it, retrieval must not.
        RetrievalIndex foreign = new RetrievalIndex(
                RetrievalIndex.FORMAT_VERSION,
                "lexical-bow-v0",
                LexicalEmbedder.DIMENSIONS,
                bookDigest(book),
                bookMask(book),
                List.of(new RetrievalIndex.Entry(1, RetrievalIndex.revision("Likes oak logs"), unitVector())));
        assertTrue(book.putRetrievalIndex(OWNER, foreign));

        String block = MemoryRetrieval.prompt(
                book, OWNER, owner(), "where is the lake", settings(2), new RetrievalCache(), null);
        assertTrue(block.contains("The lake is north"), block);
        assertEquals(
                LexicalEmbedder.ID,
                book.retrievalIndex(OWNER).orElseThrow().backend(),
                "the rebuilt index replaced the foreign one");

        // A corrupt index (a vector of the wrong width) is dropped at construction, so the book refuses it.
        RetrievalIndex corrupt = new RetrievalIndex(
                RetrievalIndex.FORMAT_VERSION,
                LexicalEmbedder.ID,
                LexicalEmbedder.DIMENSIONS,
                bookDigest(book),
                bookMask(book),
                List.of(new RetrievalIndex.Entry(1, 5L, new float[] {1f})));
        assertFalse(corrupt.isUsable());
        assertFalse(
                new RetrievalIndex(
                                0,
                                LexicalEmbedder.ID,
                                LexicalEmbedder.DIMENSIONS,
                                1L,
                                1L,
                                List.of(new RetrievalIndex.Entry(1, 1L, unitVector())))
                        .isUsable(),
                "an older derived format is a miss");

        // No backend at all: the caller renders the ordinary book instead of an empty or partial one.
        String failed = MemoryRetrieval.prompt(
                book, OWNER, owner(), "where is the lake", settings(2), new RetrievalCache(), null, text -> null);
        assertEquals("", failed);
        String thrown = MemoryRetrieval.prompt(
                book, OWNER, owner(), "where is the lake", settings(2), new RetrievalCache(), null, text -> {
                    throw new IllegalStateException("backend down");
                });
        assertEquals("", thrown);
    }

    @Test
    void theSelectionIsBoundedByTopKAndTheCharacterCap() {
        MemoryBook book = new MemoryBook();
        for (int i = 0; i < 6; i++) {
            book.addFact(
                    Subject.WORLD,
                    Category.NOTE,
                    "Lake note " + i + " with a long descriptive tail",
                    OWNER,
                    "Ann",
                    0,
                    LIMITS);
        }
        String block = MemoryRetrieval.prompt(
                book,
                OWNER,
                owner(),
                "lake note",
                new MemoryRetrieval.Settings(true, 2, 600),
                new RetrievalCache(),
                null);
        long rows = block.lines().filter(line -> line.startsWith(" [")).count();
        assertEquals(2, rows, block);

        String capped = MemoryRetrieval.prompt(
                book,
                OWNER,
                owner(),
                "lake note",
                new MemoryRetrieval.Settings(true, 6, 12),
                new RetrievalCache(),
                null);
        assertEquals("", capped, "no fact fits the character cap, so the ordinary block renders instead");
    }

    @Test
    void disabledRetrievalAndAMissingCallerOrBookReturnNothing() {
        MemoryBook book = bookWithFacts();
        assertEquals(
                "",
                MemoryRetrieval.prompt(
                        book, OWNER, owner(), "lake", MemoryRetrieval.Settings.off(), new RetrievalCache(), null));
        assertEquals("", MemoryRetrieval.prompt(book, null, owner(), "lake", settings(2), new RetrievalCache(), null));
        assertEquals("", MemoryRetrieval.prompt(book, OWNER, null, "lake", settings(2), new RetrievalCache(), null));
        assertEquals(
                "",
                MemoryRetrieval.prompt(
                        new MemoryBook(), OWNER, owner(), "lake", settings(2), new RetrievalCache(), null));
    }

    @Test
    void settingsDeeplyBoundTheShapeTheyDescribe() {
        MemoryRetrieval.Settings clamped = new MemoryRetrieval.Settings(true, 99, 1);
        assertEquals(MemoryRetrieval.MAX_TOP_K, clamped.topK());
        assertEquals(1, clamped.maxChars());
        assertEquals(MemoryLimits.DEFAULTS, clamped.limits());
        assertEquals(90, clamped.compactAtPercent());
        assertFalse(MemoryRetrieval.Settings.off().enabled());
        assertEquals(new MemoryRetrieval.Settings(true, 3, 100), new MemoryRetrieval.Settings(true, 3, 100));
        assertNotEquals(new MemoryRetrieval.Settings(true, 3, 100), new MemoryRetrieval.Settings(true, 4, 100));
    }

    private static float[] unitVector() {
        float[] vector = new float[LexicalEmbedder.DIMENSIONS];
        vector[0] = 1f;
        return vector;
    }

    /** The mask a build under no redactor records: the digest of the fact texts in id order. */
    private static long bookMask(MemoryBook book) {
        List<String> texts = new ArrayList<>();
        for (Fact fact : book.allFacts()) {
            texts.add(fact.text());
        }
        return RetrievalDigest.of(texts);
    }

    private static long bookDigest(MemoryBook book) {
        List<RetrievalIndex.Source> sources = new ArrayList<>();
        for (Fact fact : book.allFacts()) {
            sources.add(new RetrievalIndex.Source(fact.id(), fact.text()));
        }
        return RetrievalIndex.digest(sources);
    }
}
