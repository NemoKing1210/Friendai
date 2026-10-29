package dev.friendai.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.*;

import dev.friendai.features.instructions.*;
import dev.friendai.features.knowledge.*;
import dev.friendai.features.navigation.pathing.Pos;
import dev.friendai.features.placements.*;
import dev.friendai.features.places.*;
import dev.friendai.foundation.persistence.*;
import dev.friendai.infrastructure.persistence.instructions.SkillStore;
import dev.friendai.infrastructure.persistence.knowledge.KnowledgeStore;
import dev.friendai.infrastructure.persistence.placements.PlacedStore;
import dev.friendai.infrastructure.persistence.places.PlaceStore;
import dev.friendai.testutil.OwnerStoreContract;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.nio.file.*;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;
import java.util.stream.Stream;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class OwnerStoreContractTest {
    @TempDir
    Path temp;

    @TestFactory
    Stream<DynamicTest> sharedContract() {
        return Stream.of(Domain.values())
                .flatMap(domain -> OwnerStoreContract.cases().stream()
                        .map(test -> DynamicTest.dynamicTest(
                                domain + ": " + test.name(),
                                () -> test.run(
                                        (dir, fault) -> new Adapter(dir, fault, domain),
                                        temp.resolve(domain.name())
                                                .resolve(test.name().replace(' ', '-'))))));
    }

    @Test
    void failedFallbackProtectionLeavesThePreviousTargetUntouched() throws Exception {
        UUID owner = UUID.randomUUID();
        Path target = temp.resolve(owner + ".json");
        Files.writeString(target, "7");
        OwnerFileOperations files = new OwnerFileOperations() {
            @Override
            void move(Path source, Path destination, boolean atomic) throws IOException {
                if (atomic)
                    throw new AtomicMoveNotSupportedException(source.toString(), destination.toString(), "test");
                Files.deleteIfExists(destination);
                throw new IOException("replacement consumed the target");
            }

            @Override
            void backup(Path source, Path backup) throws IOException {
                throw new AccessDeniedException("protection");
            }
        };
        try (OwnerStoreWriter writer = new OwnerStoreWriter(Runnable::run)) {
            try (OwnerFileStore<Counter> store = new OwnerFileStore<>(
                    temp,
                    CODEC,
                    writer,
                    OwnerFileStore.WriterOwnership.BORROWED,
                    () -> 0,
                    files,
                    Duration.ofMillis(10))) {
                store.book(owner).increment();
                store.flushAll();
                assertEquals(StoreFailureKind.WRITE_FAILED, store.status(owner).failure());
                assertEquals("7", Files.readString(target));
            }
        }
        try (Stream<Path> filesLeft = Files.list(temp)) {
            assertEquals(1, filesLeft.count());
        }
    }

    @Test
    void failedRestorationRetainsRecoverablePreviousBytes() throws Exception {
        UUID owner = UUID.randomUUID();
        Path target = temp.resolve(owner + ".json");
        Files.writeString(target, "7");
        OwnerFileOperations files = new OwnerFileOperations() {
            @Override
            void move(Path source, Path destination, boolean atomic) throws IOException {
                if (atomic)
                    throw new AtomicMoveNotSupportedException(source.toString(), destination.toString(), "test");
                Files.deleteIfExists(destination);
                throw new IOException("replacement consumed the target");
            }

            @Override
            void restore(Path recovery, Path destination) throws IOException {
                throw new AccessDeniedException("restore");
            }
        };
        try (OwnerStoreWriter writer = new OwnerStoreWriter(Runnable::run)) {
            try (OwnerFileStore<Counter> store = new OwnerFileStore<>(
                    temp,
                    CODEC,
                    writer,
                    OwnerFileStore.WriterOwnership.BORROWED,
                    () -> 0,
                    files,
                    Duration.ofMillis(10))) {
                store.book(owner).increment();
                store.flushAll();
                assertEquals(StoreFailureKind.WRITE_FAILED, store.status(owner).failure());
            }
        }
        try (Stream<Path> filesLeft = Files.list(temp)) {
            var recovery = filesLeft.toList();
            assertEquals(1, recovery.size());
            assertTrue(recovery.getFirst().getFileName().toString().endsWith(".recovery"));
            assertEquals("7", Files.readString(recovery.getFirst()));
        }
    }

    @Test
    void wrappedStreamingReadFailureCannotBecomeWritableRecovery() throws Exception {
        UUID owner = UUID.randomUUID();
        Path target = temp.resolve(owner + ".json");
        Files.writeString(target, "7");
        StoreCodec<Counter> streaming = new CounterCodec() {
            @Override
            public Counter load(Reader reader) {
                try {
                    reader.read();
                    return new Counter();
                } catch (IOException failure) {
                    throw new java.io.UncheckedIOException(failure);
                }
            }
        };
        OwnerFileOperations files = new OwnerFileOperations() {
            @Override
            Reader reader(Path file, long maxBytes) {
                return new Reader() {
                    @Override
                    public int read(char[] chars, int offset, int length) throws IOException {
                        throw new IOException("private read failure");
                    }

                    @Override
                    public void close() {}
                };
            }
        };
        try (OwnerStoreWriter writer = new OwnerStoreWriter()) {
            try (OwnerFileStore<Counter> store = new OwnerFileStore<>(
                    temp,
                    streaming,
                    writer,
                    OwnerFileStore.WriterOwnership.BORROWED,
                    () -> 0,
                    files,
                    Duration.ofSeconds(5))) {
                store.book(owner).increment();
                store.flushAll();
                assertEquals(
                        StoreReadOnlyReason.READ_FAILED, store.status(owner).readOnlyReason());
                assertEquals(StoreFailureKind.READ_FAILED, store.status(owner).failure());
                assertFalse(store.status(owner).toString().contains("private"));
            }
        }
        assertEquals("7", Files.readString(target));
    }

    @Test
    void corruptRecordIsReadOnlyAfterBackupAndCannotBeOverwritten() throws Exception {
        UUID owner = UUID.randomUUID();
        Path target = temp.resolve(owner + ".json");
        String original = "not-a-counter";
        Files.writeString(target, original);

        try (OwnerStoreWriter writer = new OwnerStoreWriter(Runnable::run);
                OwnerFileStore<Counter> store =
                        new OwnerFileStore<>(temp, CODEC, writer, OwnerFileStore.WriterOwnership.BORROWED)) {
            store.book(owner).increment();
            assertEquals(StoreReadOnlyReason.CORRUPT, store.status(owner).readOnlyReason());
            assertEquals(original, Files.readString(target));
            assertEquals(original, Files.readString(target.resolveSibling(target.getFileName() + ".bak")));

            store.flushAll();
            assertEquals(original, Files.readString(target));
        }
    }

    @Test
    void fileGrowthIsByteBoundedBeforeStreamingDecode() throws Exception {
        UUID owner = UUID.randomUUID();
        Path target = temp.resolve(owner + ".json");
        String original = "\u00e9".repeat(33);
        Files.writeString(target, original);
        OwnerFileOperations files = new OwnerFileOperations() {
            @Override
            long size(Path file) {
                return 1;
            }
        };
        try (OwnerStoreWriter writer = new OwnerStoreWriter()) {
            try (OwnerFileStore<Counter> store = new OwnerFileStore<>(
                    temp,
                    CODEC,
                    writer,
                    OwnerFileStore.WriterOwnership.BORROWED,
                    () -> 0,
                    files,
                    Duration.ofSeconds(5))) {
                store.book(owner).increment();
                store.flushAll();
                assertEquals(StoreReadOnlyReason.OVERSIZED, store.status(owner).readOnlyReason());
            }
        }
        assertEquals(original, Files.readString(target));
    }

    @Test
    void explicitOwnedWriterClosesWithItsStore() {
        OwnerStoreWriter writer = new OwnerStoreWriter();
        OwnerFileStore<Counter> store = new OwnerFileStore<>(temp, CODEC, writer, OwnerFileStore.WriterOwnership.OWNED);
        store.close();
        assertFalse(writer.trySubmit(() -> {}));
    }

    @Test
    void wrappersPersistIndependentlyAndLeaveSharedWriterUsable() throws Exception {
        UUID owner = UUID.randomUUID();
        try (OwnerStoreWriter writer = new OwnerStoreWriter()) {
            try (PlaceStore places = new PlaceStore(temp.resolve("places"), PlaceLimits.DEFAULTS, writer);
                    PlacedStore placed = new PlacedStore(temp.resolve("placed"), PlacedLimits.DEFAULTS, writer);
                    KnowledgeStore knowledge =
                            new KnowledgeStore(temp.resolve("knowledge"), KnowledgeLimits.DEFAULTS, writer);
                    SkillStore skills =
                            new SkillStore(temp.resolve("skills"), SkillLimits.defaults(), text -> text, writer)) {
                places.book(owner)
                        .remember(
                                new PlaceValues(
                                        "home",
                                        PlaceKind.HOUSE,
                                        "",
                                        "minecraft:overworld",
                                        new Pos(1, 64, 2),
                                        8,
                                        java.util.Set.of(),
                                        PlaceSource.MANUAL,
                                        null,
                                        ""),
                                1);
                placed.book(owner)
                        .record(new PlacedBlock(
                                "minecraft:overworld", new Pos(1, 64, 2), "minecraft:oak_log", 1, "place_block"));
                knowledge
                        .book(owner)
                        .observe(
                                new ResourceObservation(
                                        "minecraft:coal_ore",
                                        new ResourceObservation.Location("minecraft:overworld", new Pos(1, 10, 2)),
                                        new Pos(1, 10, 2),
                                        1),
                                "coal");
                skills.book(owner)
                        .create(
                                SkillDraft.simple("Owner", "Inspect facts."),
                                InstructionSkill.Source.OWNER,
                                owner,
                                1,
                                false);
                skills.global()
                        .create(
                                SkillDraft.simple("Server", "Inspect support."),
                                InstructionSkill.Source.OWNER,
                                owner,
                                1,
                                false);
                assertTrue(places.status(owner).dirty());
                assertTrue(placed.status(owner).dirty());
                assertTrue(knowledge.status(owner).dirty());
                assertTrue(skills.status(owner).dirty());
                assertTrue(skills.globalStatus().dirty());
            }
            CountDownLatch usable = new CountDownLatch(1);
            assertTrue(writer.trySubmit(usable::countDown));
            assertTrue(usable.await(5, TimeUnit.SECONDS));
        }
        try (PlaceStore places = new PlaceStore(temp.resolve("places"), PlaceLimits.DEFAULTS);
                PlacedStore placed = new PlacedStore(temp.resolve("placed"), PlacedLimits.DEFAULTS);
                KnowledgeStore knowledge = new KnowledgeStore(temp.resolve("knowledge"), KnowledgeLimits.DEFAULTS);
                SkillStore skills = new SkillStore(temp.resolve("skills"), SkillLimits.defaults(), text -> text)) {
            assertEquals(1, places.book(owner).all().size());
            assertEquals(1, placed.book(owner).all().size());
            assertTrue(knowledge.book(owner).site(1, 1).isPresent());
            assertEquals("Owner", skills.book(owner).snapshot().getFirst().name());
            assertEquals("Server", skills.global().snapshot().getFirst().name());
        }
    }

    @Test
    void borrowedWriterRemainsUsableAfterIdempotentStoreClose() throws Exception {
        try (OwnerStoreWriter writer = new OwnerStoreWriter()) {
            OwnerFileStore<Counter> store =
                    new OwnerFileStore<>(temp, CODEC, writer, OwnerFileStore.WriterOwnership.BORROWED);
            UUID owner = UUID.randomUUID();
            store.book(owner).increment();
            store.flushAll();
            store.book(owner).increment();
            store.close();
            store.close();
            assertEquals("2", Files.readString(temp.resolve(owner + ".json")));
            assertEquals(2, store.status(owner).savedRevision());
            CountDownLatch accepted = new CountDownLatch(1);
            assertTrue(writer.trySubmit(accepted::countDown));
            assertTrue(accepted.await(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void boundedWriterRejectsOverflowAndClosedSubmissions() throws Exception {
        OwnerStoreWriter writer = new OwnerStoreWriter(1);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            assertTrue(writer.trySubmit(() -> {
                started.countDown();
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
            assertTrue(started.await(5, TimeUnit.SECONDS));
            assertTrue(writer.trySubmit(() -> {}));
            assertFalse(writer.trySubmit(() -> fail("rejected task ran")));
        } finally {
            release.countDown();
            writer.close();
        }
        writer.close();
        assertFalse(writer.trySubmit(() -> {}));
    }

    @Test
    void normalFlushDoesNotWaitForDiskAndCloseSettlesNewerRevisionOnCallerThread() throws Exception {
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        Thread caller = Thread.currentThread();
        AtomicReference<Thread> encoder = new AtomicReference<>();
        StoreCodec<Counter> codec = new CounterCodec() {
            @Override
            public String save(Counter value) {
                encoder.set(Thread.currentThread());
                return super.save(value);
            }
        };
        OwnerFileOperations files = new OwnerFileOperations() {
            @Override
            void write(Path temporary, byte[] bytes) throws IOException {
                started.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) throw new IOException("test write timed out");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException();
                }
                super.write(temporary, bytes);
            }
        };
        try (OwnerStoreWriter writer = new OwnerStoreWriter()) {
            OwnerFileStore<Counter> store = new OwnerFileStore<>(
                    temp,
                    codec,
                    writer,
                    OwnerFileStore.WriterOwnership.BORROWED,
                    System::currentTimeMillis,
                    files,
                    Duration.ofSeconds(5));
            UUID owner = UUID.randomUUID();
            store.book(owner).increment();
            try {
                assertTimeout(Duration.ofSeconds(1), store::flushAll);
                assertTrue(started.await(5, TimeUnit.SECONDS));
                store.book(owner).increment();
            } finally {
                release.countDown();
                store.close();
            }
            assertEquals("2", Files.readString(temp.resolve(owner + ".json")));
            assertSame(caller, encoder.get());
        }
    }

    @Test
    void closeReportsBoundedTimeoutWithoutClosingBorrowedWriter() {
        ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        try (OwnerStoreWriter writer = new OwnerStoreWriter(tasks::add)) {
            OwnerFileStore<Counter> store = new OwnerFileStore<>(
                    temp,
                    CODEC,
                    writer,
                    OwnerFileStore.WriterOwnership.BORROWED,
                    () -> 0,
                    new OwnerFileOperations(),
                    Duration.ofMillis(10));
            UUID owner = UUID.randomUUID();
            store.book(owner).increment();
            assertTimeout(Duration.ofSeconds(1), store::close);
            assertEquals(StoreFailureKind.SHUTDOWN_TIMEOUT, store.status(owner).failure());
            assertTrue(writer.trySubmit(() -> {}));
            tasks.remove().run();
        }
    }

    @Test
    void defaultReaderDecodeIsBounded() {
        assertThrows(IOException.class, () -> CODEC.load(new StringReader("1".repeat(65))));
        assertDoesNotThrow(() -> CODEC.load(new StringReader("12")));
    }

    private static class Counter {
        int value;
        long revision;

        void increment() {
            value++;
            revision++;
        }
    }

    private static class CounterCodec implements StoreCodec<Counter> {
        public Counter load(String json) {
            if ("future".equals(json)) throw new UnsupportedStoreVersionException();
            Counter value = new Counter();
            if (json != null) value.value = Integer.parseInt(json);
            return value;
        }

        public String save(Counter value) {
            return Integer.toString(value.value);
        }

        public long revision(Counter value) {
            return value.revision;
        }

        public long maxBytes() {
            return 64;
        }
    }

    private static final StoreCodec<Counter> CODEC = new CounterCodec();

    private enum Domain {
        COUNTER,
        PLACES,
        PLACED,
        KNOWLEDGE,
        OWNER_SKILLS,
        SERVER_SKILLS
    }

    private record Binding(
            Runnable read,
            Runnable mutate,
            Runnable flush,
            Supplier<StoreWriteStatus> status,
            AutoCloseable store,
            Supplier<String> input,
            ToIntFunction<String> count,
            long maxBytes,
            int initialCount) {}

    private static Binding bind(Domain domain, Path dir, UUID owner, OwnerFileStore.Factory factory) {
        return switch (domain) {
            case COUNTER -> {
                OwnerFileStore<Counter> store = factory.create(dir, CODEC);
                yield new Binding(
                        () -> store.book(owner),
                        () -> store.book(owner).increment(),
                        store::flushAll,
                        () -> store.status(owner),
                        store,
                        () -> "7",
                        Integer::parseInt,
                        64,
                        7);
            }
            case PLACES -> {
                PlaceLimits limits = PlaceLimits.DEFAULTS;
                PlaceStore store = new PlaceStore(dir, limits, factory);
                yield new Binding(
                        () -> store.book(owner),
                        () -> {
                            PlaceRegistry book = store.book(owner);
                            int index = book.all().size();
                            book.remember(
                                    new PlaceValues(
                                            "place-" + index,
                                            PlaceKind.HOUSE,
                                            "",
                                            "minecraft:overworld",
                                            new Pos(index * 100, 64, 0),
                                            8,
                                            java.util.Set.of(),
                                            PlaceSource.MANUAL,
                                            owner,
                                            ""),
                                    index + 1);
                        },
                        store::flushAll,
                        () -> store.status(owner),
                        store,
                        () -> PlaceFile.encode(new PlaceRegistry(limits)).toJson(),
                        json -> PlaceFile.decode(json, limits).all().size(),
                        limits.maxFileBytes(),
                        0);
            }
            case PLACED -> {
                PlacedLimits limits = PlacedLimits.DEFAULTS;
                PlacedStore store = new PlacedStore(dir, limits, factory);
                yield new Binding(
                        () -> store.book(owner),
                        () -> {
                            PlacedBook book = store.book(owner);
                            int index = book.all().size();
                            book.record(new PlacedBlock(
                                    "minecraft:overworld",
                                    new Pos(index, 64, 0),
                                    "minecraft:oak_log",
                                    index + 1,
                                    "place_block"));
                        },
                        store::flushAll,
                        () -> store.status(owner),
                        store,
                        () -> PlacedFile.encode(new PlacedBook(limits)).toJson(),
                        json -> PlacedFile.decode(json, limits).all().size(),
                        limits.maxFileBytes(),
                        0);
            }
            case KNOWLEDGE -> {
                KnowledgeLimits limits = KnowledgeLimits.DEFAULTS;
                KnowledgeStore store = new KnowledgeStore(dir, limits, factory);
                com.google.gson.Gson gson = new com.google.gson.Gson();
                yield new Binding(
                        () -> store.book(owner),
                        () -> {
                            ResourceKnowledge book = store.book(owner);
                            int index = book.snapshot().records().size();
                            Pos pos = new Pos(index * 100, 10, 0);
                            book.observe(
                                    new ResourceObservation(
                                            "minecraft:coal_ore",
                                            new ResourceObservation.Location("minecraft:overworld", pos),
                                            pos,
                                            index + 1),
                                    "coal");
                        },
                        store::flushAll,
                        () -> store.status(owner),
                        store,
                        () -> gson.toJson(new ResourceKnowledge(limits).snapshot()),
                        json -> ResourceKnowledge.restore(gson.fromJson(json, KnowledgeFile.class), limits)
                                .snapshot()
                                .records()
                                .size(),
                        KnowledgeStore.MAX_FILE_BYTES,
                        0);
            }
            case OWNER_SKILLS, SERVER_SKILLS -> {
                SkillLimits limits = SkillLimits.defaults();
                boolean global = domain == Domain.SERVER_SKILLS;
                InstructionSkill.Scope scope =
                        global ? InstructionSkill.Scope.SERVER : InstructionSkill.Scope.COMPANION;
                SkillFile codec = new SkillFile(limits, text -> text, scope);
                SkillStore store = new SkillStore(dir, limits, text -> text, factory);
                Supplier<SkillBook> book = global ? store::global : () -> store.book(owner);
                yield new Binding(
                        book::get,
                        () -> {
                            int index = book.get().snapshot().size();
                            book.get()
                                    .create(
                                            SkillDraft.simple("Skill " + index, "Inspect facts."),
                                            InstructionSkill.Source.OWNER,
                                            owner,
                                            index + 1,
                                            false);
                        },
                        store::flushAll,
                        global ? store::globalStatus : () -> store.status(owner),
                        store,
                        () -> codec.save(new SkillBook(limits, text -> text, scope)),
                        json -> codec.load(json).snapshot().size(),
                        codec.maxBytes(),
                        0);
            }
        };
    }

    private static final class Adapter implements OwnerStoreContract.Harness {
        private final UUID owner = UUID.randomUUID();
        private final Path dir;
        private final Domain domain;
        private OwnerStoreContract.Fault fault;
        private final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        private final OwnerStoreWriter writer;
        private final Binding binding;

        Adapter(Path dir, OwnerStoreContract.Fault fault, Domain domain) {
            this.dir = dir;
            this.domain = domain;
            this.fault = fault;
            writer = new OwnerStoreWriter(command -> {
                if (this.fault == OwnerStoreContract.Fault.QUEUE) throw new RejectedExecutionException();
                tasks.add(command);
            });
            OwnerFileOperations files = new OwnerFileOperations() {
                @Override
                void backup(Path source, Path backup) throws IOException {
                    if (Adapter.this.fault == OwnerStoreContract.Fault.BACKUP)
                        throw new AccessDeniedException("backup");
                    super.backup(source, backup);
                }

                @Override
                Reader reader(Path source, long maxBytes) throws IOException {
                    if (Adapter.this.fault == OwnerStoreContract.Fault.READ_DENIED)
                        throw new AccessDeniedException("read");
                    return super.reader(source, maxBytes);
                }

                @Override
                void write(Path temporary, byte[] bytes) throws IOException {
                    if (Adapter.this.fault == OwnerStoreContract.Fault.WRITE_DENIED)
                        throw new AccessDeniedException("write");
                    super.write(temporary, bytes);
                }

                @Override
                void move(Path source, Path target, boolean atomic) throws IOException {
                    if (Adapter.this.fault == OwnerStoreContract.Fault.PARTIAL_MOVE) {
                        if (atomic)
                            throw new AtomicMoveNotSupportedException(source.toString(), target.toString(), "test");
                        Files.deleteIfExists(target);
                        throw new IOException("replacement failed after target removal");
                    }
                    if (Adapter.this.fault == OwnerStoreContract.Fault.MOVE
                            || (atomic && Adapter.this.fault == OwnerStoreContract.Fault.ATOMIC_UNSUPPORTED))
                        throw new AtomicMoveNotSupportedException(source.toString(), target.toString(), "test");
                    super.move(source, target, atomic);
                }
            };
            OwnerFileStore.Factory factory = new OwnerFileStore.Factory() {
                @Override
                public <T> OwnerFileStore<T> create(Path path, StoreCodec<T> codec) {
                    return new OwnerFileStore<>(
                            path,
                            codec,
                            writer,
                            OwnerFileStore.WriterOwnership.BORROWED,
                            () -> 0,
                            files,
                            Duration.ofMillis(10));
                }
            };
            binding = bind(domain, dir, owner, factory);
        }

        public Path file() {
            return dir.resolve(domain == Domain.SERVER_SKILLS ? "server.json" : owner + ".json");
        }

        public String corruptInput() {
            return "broken";
        }

        public String unsupportedInput() {
            return domain == Domain.COUNTER ? "future" : "{\"version\":99}";
        }

        public String oversizedInput() {
            return "1".repeat(Math.toIntExact(binding.maxBytes() + 1));
        }

        public String validInput() {
            return binding.input().get();
        }

        public int initialMutations() {
            return binding.initialCount();
        }

        public void read() {
            binding.read().run();
        }

        public void mutate() {
            binding.mutate().run();
        }

        public StoreWriteStatus status() {
            return binding.status().get();
        }

        public void flush() {
            binding.flush().run();
        }

        public void runNext() {
            tasks.remove().run();
        }

        public int queued() {
            return tasks.size();
        }

        public void clearFault() {
            fault = OwnerStoreContract.Fault.NONE;
        }

        public int persistedMutations() throws IOException {
            return binding.count().applyAsInt(Files.readString(file()));
        }

        public void closeStore() {
            try {
                binding.store().close();
            } catch (Exception failure) {
                throw new AssertionError(failure);
            }
        }

        public boolean writerUsable() {
            java.util.concurrent.atomic.AtomicBoolean ran = new java.util.concurrent.atomic.AtomicBoolean();
            if (!writer.trySubmit(() -> ran.set(true))) return false;
            runNext();
            return ran.get();
        }

        public void close() {
            try {
                closeStore();
            } finally {
                writer.close();
            }
        }
    }
}
