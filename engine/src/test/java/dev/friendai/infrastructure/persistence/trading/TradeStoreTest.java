package dev.friendai.infrastructure.persistence.trading;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.features.trading.*;
import dev.friendai.foundation.persistence.StoreReadOnlyReason;
import dev.friendai.infrastructure.persistence.OwnerStoreWriter;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TradeStoreTest {
    @TempDir
    Path temp;

    private static final UUID ACTOR = new UUID(0, 7);

    @Test
    void readerDecodesAValidTradeSnapshot() throws IOException {
        TradeFile snapshot =
                new TradeFile(TradeFile.VERSION, 1, List.of(trader(ACTOR, 3, 30)), List.of(), java.util.Set.of());
        String json = new String(TradeFile.write(snapshot), java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(1, TradeFile.read(new java.io.StringReader(json)).traders().size());
    }

    @Test
    void malformedUtf8TradeFileIsPreservedAsCorrupt() throws IOException {
        Path directory = temp.resolve("invalid-utf8");
        Files.createDirectories(directory);
        UUID owner = new UUID(0, 92);
        byte[] prefix = "{\"version\":1,\"traders\":[],\"history\":[],\"pinned\":[],\"unknown\":\""
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] suffix = "\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] malformed = java.util.Arrays.copyOf(prefix, prefix.length + 1 + suffix.length);
        malformed[prefix.length] = (byte) 0xc3;
        System.arraycopy(suffix, 0, malformed, prefix.length + 1, suffix.length);
        Path path = directory.resolve(owner + ".json");
        Files.write(path, malformed);
        try (TradeStore store = new TradeStore(directory)) {
            assertTrue(store.book(owner).snapshot().traders().isEmpty());
            assertEquals(StoreReadOnlyReason.CORRUPT, store.status(owner).readOnlyReason());
            assertArrayEquals(malformed, Files.readAllBytes(path));
            assertArrayEquals(malformed, Files.readAllBytes(directory.resolve(owner + ".json.bak")));
        }
    }

    @Test
    void statusTracksSavedRevisionAndReadOnlyReason() throws IOException {
        Path directory = temp.resolve("status");
        Files.createDirectories(directory);
        UUID unsupported = new UUID(0, 88);
        Files.writeString(directory.resolve(unsupported + ".json"), "{\"version\":9}");
        try (TradeStore store = new TradeStore(directory)) {
            UUID writable = new UUID(0, 89);
            store.book(writable).observe(trader(writable, 1, 30));
            assertTrue(store.status(writable).dirty());
            store.flushAll();
            awaitSaved(store, writable);
            assertFalse(store.status(writable).dirty());
            store.book(unsupported).observe(trader(unsupported, 2, 30));
            assertEquals(
                    StoreReadOnlyReason.UNSUPPORTED, store.status(unsupported).readOnlyReason());
            store.flushAll();
            assertTrue(store.status(unsupported).dirty());
        }
    }

    @Test
    void borrowedWriterRemainsUsableAfterTradeStoreClose() throws IOException {
        OwnerStoreWriter writer = new OwnerStoreWriter();
        UUID first = new UUID(0, 90);
        UUID second = new UUID(0, 91);
        try {
            try (TradeStore store = new TradeStore(temp.resolve("borrowed-first"), writer)) {
                store.book(first).observe(trader(first, 1, 30));
            }
            try (TradeStore store = new TradeStore(temp.resolve("borrowed-second"), writer)) {
                store.book(second).observe(trader(second, 2, 30));
            }
            assertTrue(Files.exists(temp.resolve("borrowed-second").resolve(second + ".json")));
        } finally {
            writer.close();
        }
    }

    private static TradeOffer offer() {
        var base = List.of(
                new TradeCost("minecraft:emerald", "{\"minecraft:custom_data\":{\"grade\":\"rare\"}}", 3),
                new TradeCost("minecraft:diamond", "{\"minecraft:custom_data\":{\"mark\":\"blue\"}}", 1));
        var effective = List.of(
                new TradeCost("minecraft:emerald", "{\"minecraft:custom_data\":{\"grade\":\"rare\"}}", 2), base.get(1));
        var output = new TradeItem("minecraft:enchanted_book", "{\"minecraft:custom_data\":{\"label\":\"Ω\"}}", 1);
        return new TradeOffer(
                "temporary", TradeOffer.fingerprint(base, output, 0), base, effective, output, 2, 12, 3, ACTOR, 20);
    }

    private static TraderRecord trader(UUID id, int x, long lastSeen) {
        return new TraderRecord(
                id,
                "minecraft:villager",
                "minecraft:overworld",
                x,
                70,
                -9,
                "Librarian",
                "minecraft:librarian",
                4,
                10,
                lastSeen,
                lastSeen,
                3,
                TraderRecord.Status.VERIFIED,
                List.of(offer()));
    }

    private static TradeProgress progress(UUID id, TradeProgress.State state, List<TradeItem> received) {
        return new TradeProgress(id, state, Map.of("minecraft:emerald", 6), received, 2, "observed");
    }

    @Test
    void missingFileIsLazyAndOwnerBooksAreIsolated() throws IOException {
        Path directory = temp.resolve("trading");
        UUID first = new UUID(0, 1);
        UUID second = new UUID(0, 2);
        try (TradeStore store = new TradeStore(directory)) {
            store.book(first);
            assertFalse(Files.exists(directory.resolve(first + ".json")));
            store.book(first).observe(trader(first, 12, 30));
            assertTrue(store.book(second).snapshot().traders().isEmpty());
            store.flushAll();
        }
        try (TradeStore store = new TradeStore(directory)) {
            assertEquals(12, store.book(first).trader(first).orElseThrow().x());
            assertTrue(store.book(second).snapshot().traders().isEmpty());
        }
    }

    @Test
    void roundTripPreservesBothCostsComponentsLocationAndInformationalHistory() throws IOException {
        Path directory = temp.resolve("roundtrip");
        UUID owner = new UUID(0, 3);
        UUID traderId = new UUID(0, 4);
        UUID orderId = new UUID(0, 5);
        var received = new TradeItem("minecraft:book", "{\"minecraft:custom_data\":{\"stamp\":\"gift\"}}", 2);
        try (TradeStore store = new TradeStore(directory)) {
            TradeBook book = store.book(owner);
            book.observe(trader(traderId, -123, 45));
            book.record(progress(orderId, TradeProgress.State.PARTIAL, List.of(received)));
            store.flushAll();
        }
        try (TradeStore store = new TradeStore(directory)) {
            TradeBook restored = store.book(owner);
            TraderRecord loaded = restored.trader(traderId).orElseThrow();
            assertEquals(-123, loaded.x());
            assertEquals(-9, loaded.z());
            TradeOffer loadedOffer = loaded.offers().getFirst();
            assertEquals(2, loadedOffer.baseInputs().size());
            assertEquals(
                    "{\"minecraft:custom_data\":{\"grade\":\"rare\"}}",
                    loadedOffer.baseInputs().getFirst().predicateJson());
            assertEquals(2, loadedOffer.effectiveInputs().getFirst().count());
            assertEquals(
                    "{\"minecraft:custom_data\":{\"label\":\"Ω\"}}",
                    loadedOffer.output().componentsJson());
            TradeProgress loadedProgress = restored.snapshot().history().getFirst();
            assertEquals(orderId, loadedProgress.orderId());
            assertEquals(TradeProgress.State.PARTIAL, loadedProgress.state());
            assertEquals(received, loadedProgress.received().getFirst());
        }
    }

    @Test
    void flushUsesFiveSecondDebounceAndLeavesSiblingStoresUntouched() throws IOException {
        Path root = temp.resolve("world-friendai");
        Path directory = root.resolve("trading");
        Files.createDirectories(root.resolve("memory"));
        Files.createDirectories(root.resolve("knowledge"));
        byte[] memory = new byte[] {1, 2, 3, 4};
        byte[] knowledge = new byte[] {5, 6, 7};
        Files.write(root.resolve("memory/keep.bin"), memory);
        Files.write(root.resolve("knowledge/keep.bin"), knowledge);
        UUID owner = new UUID(0, 6);
        try (TradeStore store = new TradeStore(directory)) {
            store.book(owner).observe(trader(owner, 8, 30));
            store.flushIfDue(4_999);
            assertFalse(Files.exists(directory.resolve(owner + ".json")));
            store.flushIfDue(5_000);
        }
        assertArrayEquals(memory, Files.readAllBytes(root.resolve("memory/keep.bin")));
        assertArrayEquals(knowledge, Files.readAllBytes(root.resolve("knowledge/keep.bin")));
        assertTrue(Files.exists(directory.resolve(owner + ".json")));
    }

    @Test
    void truncatedInputIsBackedUpAndOversizedInputIsRejectedBeforeDecode() throws IOException {
        Path directory = temp.resolve("bad-input");
        Files.createDirectories(directory);
        UUID truncatedOwner = new UUID(0, 8);
        byte[] truncated = "{\"version\":1".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(directory.resolve(truncatedOwner + ".json"), truncated);
        UUID oversizedOwner = new UUID(0, 9);
        byte[] oversized = new byte[TradeFile.MAX_FILE_BYTES + 1];
        Files.write(directory.resolve(oversizedOwner + ".json"), oversized);
        try (TradeStore store = new TradeStore(directory)) {
            assertTrue(store.book(truncatedOwner).snapshot().traders().isEmpty());
            assertArrayEquals(truncated, Files.readAllBytes(directory.resolve(truncatedOwner + ".json.bak")));
            TradeBook oversizedBook = store.book(oversizedOwner);
            assertTrue(oversizedBook.snapshot().traders().isEmpty());
            assertTrue(store.writesDisabled(oversizedOwner));
            oversizedBook.observe(trader(oversizedOwner, 9, 30));
            store.flushAll();
            Path oversizedPath = directory.resolve(oversizedOwner + ".json");
            assertArrayEquals(oversized, Files.readAllBytes(oversizedPath));
            assertFalse(Files.exists(directory.resolve(oversizedOwner + ".json.bak")));
            assertTrue(store.repairOwner(oversizedOwner));
            store.flushAll();
            awaitSaved(store, oversizedOwner);
            assertFalse(store.writesDisabled(oversizedOwner));
            assertEquals(1, TradeFile.read(Files.readAllBytes(oversizedPath)).version());
        }
    }

    @Test
    void failedCorruptBackupKeepsOriginalReadOnlyUntilExplicitRepair() throws IOException {
        Path directory = temp.resolve("backup-failure");
        Files.createDirectories(directory);
        UUID owner = new UUID(0, 81);
        byte[] corrupt = "{not valid json}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Path path = directory.resolve(owner + ".json");
        Files.write(path, corrupt);
        AtomicInteger backupAttempts = new AtomicInteger();
        TradeStore.FileCopier failingCopier = (source, target, options) -> {
            if (backupAttempts.incrementAndGet() < 3) throw new IOException("injected backup copy failure");
            Files.copy(source, target, options);
        };
        try (TradeStore store = new TradeStore(directory, Files::move, failingCopier)) {
            TradeBook book = store.book(owner);
            assertTrue(book.snapshot().traders().isEmpty());
            assertFalse(store.dirty(owner));
            book.observe(trader(owner, 11, 30));
            store.flushAll();
            assertTrue(store.writesDisabled(owner));
            assertTrue(store.dirty(owner));
            assertNotNull(store.failure(owner));
            assertArrayEquals(corrupt, Files.readAllBytes(path));
            assertFalse(store.repairOwner(owner));
            assertArrayEquals(corrupt, Files.readAllBytes(path));
            assertTrue(store.repairOwner(owner));
            assertArrayEquals(corrupt, Files.readAllBytes(directory.resolve(owner + ".json.bak")));
            store.flushAll();
            awaitSaved(store, owner);
            assertFalse(store.writesDisabled(owner));
            assertEquals(
                    11,
                    TradeFile.read(Files.readAllBytes(path))
                            .traders()
                            .getFirst()
                            .x());
        }
    }

    @Test
    void deeplyNestedInputIsRejectedAndBackedUp() throws IOException {
        Path directory = temp.resolve("deep");
        Files.createDirectories(directory);
        UUID owner = new UUID(0, 10);
        String deep = "{\"version\":1,\"extra\":" + "[".repeat(80) + "0" + "]".repeat(80) + "}";
        byte[] original = deep.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(directory.resolve(owner + ".json"), original);
        try (TradeStore store = new TradeStore(directory)) {
            assertTrue(store.book(owner).snapshot().traders().isEmpty());
            assertArrayEquals(original, Files.readAllBytes(directory.resolve(owner + ".json.bak")));
        }
    }

    @Test
    void futureVersionIsPreservedAndWritesRequireExplicitRepair() throws IOException {
        Path directory = temp.resolve("future");
        Files.createDirectories(directory);
        UUID owner = new UUID(0, 11);
        byte[] future =
                "{\"version\":7,\"opaque\":{\"future\":true}}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Path path = directory.resolve(owner + ".json");
        Files.write(path, future);
        try (TradeStore store = new TradeStore(directory)) {
            store.book(owner).observe(trader(owner, 20, 30));
            store.flushAll();
            assertTrue(store.writesDisabled(owner));
            assertArrayEquals(future, Files.readAllBytes(path));
            assertTrue(store.dirty(owner));
            assertTrue(store.repairOwner(owner));
            store.flushAll();
            awaitSaved(store, owner);
            assertFalse(store.writesDisabled(owner));
            assertEquals(1, TradeFile.read(Files.readAllBytes(path)).version());
        }
    }

    @Test
    void futureVersionIsDetectedBeforeDeepUnknownFieldsAreParsed() throws IOException {
        Path directory = temp.resolve("future-deep");
        Files.createDirectories(directory);
        UUID owner = new UUID(0, 111);
        byte[] future = ("{\"opaque\":" + "[".repeat(256) + "0" + "]".repeat(256) + ",\"version\":8}")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Path path = directory.resolve(owner + ".json");
        Files.write(path, future);
        try (TradeStore store = new TradeStore(directory)) {
            store.book(owner).observe(trader(owner, 20, 30));
            store.flushAll();
            assertTrue(store.writesDisabled(owner));
            assertFalse(Files.exists(directory.resolve(owner + ".json.bak")));
            assertArrayEquals(future, Files.readAllBytes(path));
        }
    }

    @Test
    void collectionAndAggregateOfferLimitsAreCheckedWhileStreaming() {
        String excessiveTraders = "{\"version\":1,\"traders\":[" + "null,".repeat(TradeFile.MAX_TRADERS)
                + "null],\"history\":[],\"pinned\":[]}";
        assertThrows(
                IllegalArgumentException.class,
                () -> TradeFile.read(excessiveTraders.getBytes(java.nio.charset.StandardCharsets.UTF_8)));

        String sixtyFiveOffers = "{\"version\":1,\"traders\":[{\"offers\":["
                + "null,".repeat(TradeFile.MAX_OFFERS_PER_TRADER) + "null]}],\"history\":[],\"pinned\":[]}";
        assertThrows(
                IllegalArgumentException.class,
                () -> TradeFile.read(sixtyFiveOffers.getBytes(java.nio.charset.StandardCharsets.UTF_8)));

        String traderWith64Offers = "{\"offers\":[" + "null,".repeat(TradeFile.MAX_OFFERS_PER_TRADER - 1) + "null]}";
        String tooManyTotalOffers = "{\"version\":1,\"traders\":["
                + String.join(",", java.util.Collections.nCopies(TradeFile.MAX_TOTAL_OFFERS / 64, traderWith64Offers))
                + ",{\"offers\":[null]}],\"history\":[],\"pinned\":[]}";
        assertThrows(
                IllegalArgumentException.class,
                () -> TradeFile.read(tooManyTotalOffers.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    @Test
    void aggregateJsonNodeBudgetRejectsManySmallNestedArraysBeforeBuildingTheTree() {
        String inner = "[" + "[],".repeat(124) + "[]]";
        String json = "{\"version\":1,\"traders\":[],\"history\":[],\"pinned\":[],\"unknown\":["
                + String.join(",", java.util.Collections.nCopies(8_192, inner)) + "]}";
        byte[] encoded = json.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(encoded.length < TradeFile.MAX_FILE_BYTES);
        assertThrows(IllegalArgumentException.class, () -> TradeFile.read(encoded));
    }

    @Test
    void aggregateNodeBudgetAllowsLargeValidReceiptHistory() {
        TradeItem receipt = new TradeItem("minecraft:book", "{}", 1);
        List<TradeItem> receipts = java.util.Collections.nCopies(TradeProgress.MAX_RECEIPTS, receipt);
        List<TradeProgress> history = java.util.stream.IntStream.range(0, 32)
                .mapToObj(i ->
                        new TradeProgress(new UUID(3, i), TradeProgress.State.COMPLETED, Map.of(), receipts, 1, ""))
                .toList();
        byte[] encoded = TradeFile.write(new TradeFile(TradeFile.VERSION, 1, List.of(), history, java.util.Set.of()));
        assertTrue(encoded.length < TradeFile.MAX_FILE_BYTES);
        TradeFile decoded = TradeFile.read(encoded);
        assertEquals(32, decoded.history().size());
        assertEquals(
                TradeProgress.MAX_RECEIPTS,
                decoded.history().getFirst().received().size());
    }

    @Test
    void missingOptionalHistoryFieldsInVersionOneDecodeAsEmpty() {
        TradeFile legacy = TradeFile.read(
                "{\"version\":1,\"revision\":0,\"traders\":[]}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertTrue(legacy.history().isEmpty());
        assertTrue(legacy.pinned().isEmpty());
    }

    @Test
    void atomicMoveUnsupportedFallsBackToRegularReplacement() throws IOException {
        Path directory = temp.resolve("fallback");
        AtomicInteger atomicAttempts = new AtomicInteger();
        TradeStore.FileMover mover = (source, target, options) -> {
            boolean atomic = java.util.Arrays.asList(options).contains(StandardCopyOption.ATOMIC_MOVE);
            if (atomic) {
                atomicAttempts.incrementAndGet();
                throw new AtomicMoveNotSupportedException(source.toString(), target.toString(), "test fallback");
            }
            Files.move(source, target, options);
        };
        UUID owner = new UUID(0, 12);
        try (TradeStore store = new TradeStore(directory, Runnable::run, () -> 0L, mover, false)) {
            store.book(owner).observe(trader(owner, 4, 30));
            store.flushIfDue(5_000);
            assertTrue(atomicAttempts.get() > 0);
            assertTrue(Files.exists(directory.resolve(owner + ".json")));
        }
    }

    @Test
    void queuedRevisionThenNewerRevisionAreBothEventuallySaved() throws IOException {
        Path directory = temp.resolve("queued");
        ManualExecutor executor = new ManualExecutor();
        UUID owner = new UUID(0, 13);
        try (TradeStore store = new TradeStore(directory, executor, () -> 0L, Files::move, false)) {
            TradeBook book = store.book(owner);
            book.observe(trader(owner, 1, 30));
            store.flushIfDue(5_000);
            book.observe(trader(owner, 2, 31));
            assertTrue(store.dirty(owner));
            executor.runNext();
            assertTrue(store.dirty(owner));
            store.flushIfDue(10_000);
            executor.runNext();
            assertFalse(store.dirty(owner));
        }
        try (TradeStore store = new TradeStore(directory)) {
            assertEquals(2, store.book(owner).trader(owner).orElseThrow().x());
        }
    }

    @Test
    void terminalSummariesAreEvictedToFitTheEncodedLimit() throws IOException {
        Path directory = temp.resolve("bounded-write");
        UUID owner = new UUID(0, 14);
        String payload = "x".repeat(7_900);
        var many = java.util.stream.IntStream.range(0, 1_100)
                .mapToObj(i -> new TradeItem("minecraft:book", "{\"data\":\"" + payload + "\"}", 1))
                .toList();
        try (TradeStore store = new TradeStore(directory)) {
            TradeBook book = store.book(owner);
            book.observe(trader(new UUID(0, 141), 2, 30));
            book.record(progress(new UUID(0, 140), TradeProgress.State.COMPLETED, many));
            assertTrue(book.snapshot().history().size() == 1);
            store.flushAll();
            assertTrue(book.snapshot().traders().isEmpty());
            assertTrue(book.snapshot().history().isEmpty());
            awaitSaved(store, owner);
            assertTrue(Files.size(directory.resolve(owner + ".json")) <= TradeFile.MAX_FILE_BYTES);
        }
    }

    @Test
    void protectedOversizeKeepsPreviousFileAndDirtyRevision() throws IOException {
        Path directory = temp.resolve("protected-size");
        Files.createDirectories(directory);
        UUID owner = new UUID(0, 15);
        byte[] durable = TradeFile.write(new TradeFile(TradeFile.VERSION, 0, List.of(), List.of(), java.util.Set.of()));
        Path target = directory.resolve(owner + ".json");
        Files.write(target, durable);
        String payload = "y".repeat(7_900);
        var many = java.util.stream.IntStream.range(0, 1_100)
                .mapToObj(i -> new TradeItem("minecraft:book", "{\"data\":\"" + payload + "\"}", 1))
                .toList();
        try (TradeStore store = new TradeStore(directory)) {
            TradeBook book = store.book(owner);
            UUID protectedTrader = new UUID(0, 151);
            assertTrue(book.observe(trader(protectedTrader, 7, 30)));
            book.pin(protectedTrader);
            book.record(progress(new UUID(0, 150), TradeProgress.State.AUTHORIZED, many));
            book.record(progress(new UUID(0, 152), TradeProgress.State.RUNNING, many));
            store.flushAll();
            assertTrue(store.dirty(owner));
            assertNotNull(store.failure(owner));
            assertEquals(1, book.snapshot().traders().size());
            assertEquals(2, book.snapshot().history().size());
            assertArrayEquals(durable, Files.readAllBytes(target));
        }
    }

    @Test
    void historyLimitDoesNotDiscardAuthorizedOrRunningOrders() {
        TradeBook book = new TradeBook();
        for (int i = 0; i < TradeFile.MAX_HISTORY; i++) {
            book.record(progress(new UUID(1, i), TradeProgress.State.RUNNING, List.of()));
        }
        long revision = book.revision();
        assertThrows(
                IllegalStateException.class,
                () -> book.record(progress(new UUID(2, 1), TradeProgress.State.COMPLETED, List.of())));
        assertEquals(revision, book.revision());
        assertEquals(TradeFile.MAX_HISTORY, book.snapshot().history().size());
        assertTrue(book.snapshot().history().stream().allMatch(row -> row.state() == TradeProgress.State.RUNNING));
    }

    @Test
    void closeWaitsForLatestSnapshotAfterRevisionChangesWhileSaveIsQueued() throws Exception {
        Path directory = temp.resolve("close-latest");
        CountDownLatch firstMove = new CountDownLatch(1);
        CountDownLatch allowMove = new CountDownLatch(1);
        AtomicInteger moves = new AtomicInteger();
        TradeStore.FileMover mover = (source, target, options) -> {
            if (moves.incrementAndGet() == 1) {
                firstMove.countDown();
                try {
                    if (!allowMove.await(5, TimeUnit.SECONDS)) throw new IOException("test release timed out");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("interrupted", e);
                }
            }
            Files.move(source, target, options);
        };
        UUID owner = new UUID(0, 16);
        TradeStore store = new TradeStore(directory, mover);
        TradeBook book = store.book(owner);
        book.observe(trader(owner, 1, 30));
        store.flushIfDue(5_000);
        assertTrue(firstMove.await(5, TimeUnit.SECONDS));
        book.observe(trader(owner, 2, 31));
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch closed = new CountDownLatch(1);
        Thread closer = new Thread(() -> {
            try {
                store.close();
            } catch (Throwable error) {
                failure.set(error);
            } finally {
                closed.countDown();
            }
        });
        closer.start();
        allowMove.countDown();
        assertTrue(closed.await(5, TimeUnit.SECONDS));
        assertEquals(null, failure.get());
        try (TradeStore restored = new TradeStore(directory)) {
            assertEquals(2, restored.book(owner).trader(owner).orElseThrow().x());
        }
    }

    private static void awaitSaved(TradeStore store, UUID owner) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (store.status(owner).dirty() && System.nanoTime() < deadline) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError(interrupted);
            }
        }
        assertFalse(store.status(owner).dirty(), "trade snapshot was not saved within 5 seconds");
    }

    private static final class ManualExecutor implements Executor {
        private final Queue<Runnable> queued = new ArrayDeque<>();

        @Override
        public void execute(Runnable command) {
            queued.add(command);
        }

        void runNext() {
            queued.remove().run();
        }
    }
}
