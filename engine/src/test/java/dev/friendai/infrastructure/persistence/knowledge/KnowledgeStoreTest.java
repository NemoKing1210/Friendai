package dev.friendai.infrastructure.persistence.knowledge;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.features.knowledge.*;
import dev.friendai.features.navigation.pathing.Pos;
import dev.friendai.foundation.persistence.UnsupportedStoreVersionException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class KnowledgeStoreTest {
    @TempDir
    Path temp;

    private static ResourceObservation observation(String block, Pos pos, long tick) {
        return new ResourceObservation(block, new ResourceObservation.Location("minecraft:overworld", pos), pos, tick);
    }

    @Test
    void roundTripRetainsSitesAndAliases() {
        ResourceKnowledge original = new ResourceKnowledge(KnowledgeLimits.DEFAULTS);
        long first = original.observe(observation("minecraft:coal_ore", new Pos(0, 10, 0), 1), "coal");
        long second = original.observe(observation("minecraft:coal_ore", new Pos(2, 10, 0), 2), "coal");
        original.observe(observation("minecraft:coal_ore", new Pos(1, 10, 0), 3), "coal");

        ResourceKnowledge restored = ResourceKnowledge.restore(original.snapshot(), KnowledgeLimits.DEFAULTS);

        assertEquals(first, restored.site(first, 3).orElseThrow().id());
        assertEquals(first, restored.site(second, 3).orElseThrow().id());
        assertEquals(3, restored.historicalBlockCount(second));
    }

    @Test
    void depletedSiteSurvivesRoundTrip() {
        ResourceKnowledge original = new ResourceKnowledge(KnowledgeLimits.DEFAULTS);
        long site = original.observe(observation("minecraft:coal_ore", new Pos(0, 10, 0), 1), "coal");
        original.extracted(new ResourceObservation.Location("minecraft:overworld", new Pos(0, 10, 0)), 2);
        ResourceKnowledge restored = ResourceKnowledge.restore(original.snapshot(), KnowledgeLimits.DEFAULTS);
        assertEquals(
                ResourceSite.Availability.DEPLETED,
                restored.site(site, 2).orElseThrow().availability());
    }

    @Test
    void pruningMergedSummaryRemovesItsAliases() {
        ResourceKnowledge book = new ResourceKnowledge(new KnowledgeLimits(4, 2, 4, 2, 4));
        book.observe(observation("minecraft:coal_ore", new Pos(0, 10, 0), 1), "coal");
        long retained = book.observe(observation("minecraft:iron_ore", new Pos(10, 10, 0), 2), "iron");
        long merged = book.observe(observation("minecraft:deepslate_iron_ore", new Pos(12, 10, 0), 3), "iron");
        book.observe(observation("minecraft:iron_ore", new Pos(11, 10, 0), 4), "iron");
        book.observe(observation("minecraft:coal_ore", new Pos(0, 10, 0), 5), "coal");
        book.observe(observation("minecraft:gold_ore", new Pos(30, 10, 0), 6), "gold");

        KnowledgeFile snapshot = book.snapshot();

        assertTrue(retained < merged);
        assertTrue(snapshot.aliases().isEmpty());
        ResourceKnowledge.restore(snapshot, new KnowledgeLimits(4, 2, 4, 2, 4));
    }

    @Test
    void missingFileDoesNotWrite() {
        Path dir = temp.resolve("knowledge");
        UUID owner = UUID.randomUUID();
        try (KnowledgeStore store = new KnowledgeStore(dir, KnowledgeLimits.DEFAULTS)) {
            store.book(owner);
            assertFalse(Files.exists(dir.resolve(owner + ".json")));
        }
    }

    @Test
    void oversizedInputRejectedBeforeParsing() throws IOException {
        Path dir = temp.resolve("oversized");
        Files.createDirectories(dir);
        UUID owner = UUID.randomUUID();
        Path file = dir.resolve(owner + ".json");
        Files.write(file, new byte[8 * 1024 * 1024 + 1]);
        try (KnowledgeStore store = new KnowledgeStore(dir, KnowledgeLimits.DEFAULTS)) {
            assertTrue(store.book(owner).site(1, 0).isEmpty());
        }
    }

    @Test
    void corruptionBackedUp() throws IOException {
        Path dir = temp.resolve("corrupt");
        Files.createDirectories(dir);
        UUID owner = UUID.randomUUID();
        Path file = dir.resolve(owner + ".json");
        Files.writeString(file, "{not json}");
        try (KnowledgeStore store = new KnowledgeStore(dir, KnowledgeLimits.DEFAULTS)) {
            assertTrue(store.book(owner).site(1, 0).isEmpty());
            assertTrue(Files.exists(dir.resolve(owner + ".json.bak")));
        }
    }

    @Test
    void unsupportedOldFileVersionIsBackedUpAndStartsEmpty() throws IOException {
        Path dir = temp.resolve("old-version");
        Files.createDirectories(dir);
        UUID owner = UUID.randomUUID();
        Path file = dir.resolve(owner + ".json");
        Files.writeString(file, "{\"version\":1,\"logicalTick\":0,\"nextSiteId\":1}");
        String old = Files.readString(file);
        try (KnowledgeStore store = new KnowledgeStore(dir, KnowledgeLimits.DEFAULTS)) {
            assertThrows(UnsupportedStoreVersionException.class, () -> store.book(owner));
        }
        assertEquals(old, Files.readString(file));
    }

    @Test
    void unknownRegistryIdRetainedAsOpaqueData() {
        ResourceKnowledge book = new ResourceKnowledge(KnowledgeLimits.DEFAULTS);
        book.observe(observation("some_mod:unregistered_ore", new Pos(4, 20, -3), 7), "some_mod:ore");
        assertEquals(
                "some_mod:unregistered_ore",
                book.snapshot().records().getFirst().blockId());
    }

    @Test
    void newRevisionDuringSaveStaysDirty() throws IOException {
        Path dir = temp.resolve("dirty");
        ManualExecutor executor = new ManualExecutor();
        UUID owner = UUID.randomUUID();
        try (KnowledgeStore store = new KnowledgeStore(dir, KnowledgeLimits.DEFAULTS, executor, () -> 0L)) {
            ResourceKnowledge book = store.book(owner);
            book.observe(observation("minecraft:coal_ore", new Pos(0, 10, 0), 1), "coal");
            store.flushIfDue(5_000);
            book.observe(observation("minecraft:coal_ore", new Pos(1, 10, 0), 2), "coal");

            executor.runNext();

            assertTrue(store.dirty(owner));
            store.flushIfDue(10_000);
            executor.runNext();
            assertFalse(store.dirty(owner));
        }
    }

    @Test
    void rejectedSaveDoesNotEscapeOrClearDirtyRevision() {
        Executor rejecting = command -> {
            throw new RejectedExecutionException("closed writer");
        };
        UUID owner = UUID.randomUUID();
        try (KnowledgeStore store =
                new KnowledgeStore(temp.resolve("rejected"), KnowledgeLimits.DEFAULTS, rejecting, () -> 5_000L)) {
            store.book(owner).observe(observation("minecraft:coal_ore", new Pos(0, 10, 0), 1), "coal");
            assertDoesNotThrow(() -> store.flushIfDue(5_000));
            assertTrue(store.dirty(owner));
        }
    }

    @Test
    void ownerBooksIsolated() {
        try (KnowledgeStore store = new KnowledgeStore(temp.resolve("owners"), KnowledgeLimits.DEFAULTS)) {
            UUID first = UUID.randomUUID();
            UUID second = UUID.randomUUID();
            store.book(first).observe(observation("minecraft:coal_ore", new Pos(0, 10, 0), 1), "coal");
            assertTrue(store.book(second).site(1, 1).isEmpty());
        }
    }

    @Test
    void authorizedContainerSnapshotSurvivesKnowledgeRoundTrip() {
        ResourceKnowledge book = new ResourceKnowledge(KnowledgeLimits.DEFAULTS);
        var location = new ResourceObservation.Location("minecraft:overworld", new Pos(4, 20, -3));
        assertTrue(book.recordContainer(
                new ResourceKnowledge.ContainerObservation(location, Map.of("minecraft:iron_ingot", 12), 7)));
        ResourceKnowledge restored = ResourceKnowledge.restore(book.snapshot(), KnowledgeLimits.DEFAULTS);
        assertEquals(
                Map.of("minecraft:iron_ingot", 12),
                restored.snapshot().containers().getFirst().items());
        assertEquals(7, restored.snapshot().containers().getFirst().observedTick());
    }

    @Test
    void containerSnapshotPersistsThroughKnowledgeStore() throws IOException {
        Path dir = temp.resolve("containers");
        UUID owner = UUID.randomUUID();
        ManualExecutor executor = new ManualExecutor();
        try (KnowledgeStore store = new KnowledgeStore(dir, KnowledgeLimits.DEFAULTS, executor, () -> 0L)) {
            var location = new ResourceObservation.Location("minecraft:overworld", new Pos(4, 20, -3));
            store.book(owner)
                    .recordContainer(new ResourceKnowledge.ContainerObservation(
                            location, Map.of("minecraft:iron_ingot", 12), 7));
            store.flushIfDue(5_000);
            executor.runNext();
        }
        try (KnowledgeStore store = new KnowledgeStore(dir, KnowledgeLimits.DEFAULTS)) {
            assertEquals(
                    Map.of("minecraft:iron_ingot", 12),
                    store.book(owner).snapshot().containers().getFirst().items());
        }
    }

    @Test
    void containerSnapshotsRespectPerOwnerAndExactRecordCaps() {
        ResourceKnowledge book = new ResourceKnowledge(KnowledgeLimits.DEFAULTS);
        for (int i = 0; i < 256; i++) {
            var location = new ResourceObservation.Location("minecraft:overworld", new Pos(i, 20, 0));
            assertTrue(book.recordContainer(new ResourceKnowledge.ContainerObservation(location, Map.of(), 1)));
        }
        assertFalse(book.recordContainer(new ResourceKnowledge.ContainerObservation(
                new ResourceObservation.Location("minecraft:overworld", new Pos(300, 20, 0)), Map.of(), 1)));
        ResourceKnowledge capped = new ResourceKnowledge(KnowledgeLimits.DEFAULTS);
        for (int i = 0; i < 16_384; i++) {
            capped.observe(observation("minecraft:coal_ore", new Pos(i, 20, 0), i + 1), "coal");
        }
        assertFalse(capped.recordContainer(new ResourceKnowledge.ContainerObservation(
                new ResourceObservation.Location("minecraft:overworld", new Pos(0, 30, 0)),
                Map.of("minecraft:coal", 1),
                16_385)));
    }

    @Test
    void oldClockDoesNotMakeNegativeAge() {
        ResourceKnowledge book = new ResourceKnowledge(KnowledgeLimits.DEFAULTS);
        long id = book.observe(observation("minecraft:coal_ore", new Pos(0, 10, 0), 100), "coal");
        book.observe(observation("minecraft:iron_ore", new Pos(20, 10, 0), 50), "iron");
        assertEquals(100, book.site(id, 50).orElseThrow().lastSeen());
        ResourceQuery.Request request = new ResourceQuery.Request(
                Set.of("minecraft:coal_ore"), "minecraft:overworld", new Pos(0, 10, 0), 20, 5, true);
        assertEquals(0, ResourceQuery.find(book, request).getFirst().ageTicks());
    }

    private static final class ManualExecutor implements Executor {
        private final Queue<Runnable> tasks = new ArrayDeque<>();

        @Override
        public void execute(Runnable command) {
            tasks.add(command);
        }

        void runNext() {
            tasks.remove().run();
        }
    }
}
