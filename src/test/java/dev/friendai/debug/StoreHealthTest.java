package dev.friendai.debug;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.features.memory.transcript.Speaker;
import dev.friendai.features.memory.transcript.TranscriptLimits;
import dev.friendai.foundation.persistence.StoreFailureKind;
import dev.friendai.foundation.persistence.StoreReadOnlyReason;
import dev.friendai.foundation.persistence.StoreWriteStatus;
import dev.friendai.infrastructure.persistence.OwnerStoreWriter;
import dev.friendai.infrastructure.persistence.memory.MemoryStore;
import dev.friendai.infrastructure.persistence.transcript.TranscriptStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StoreHealthTest {
    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private static Map<String, Map<UUID, StoreWriteStatus>> sources(StoreWriteStatus status) {
        return Map.of("goals", Map.of(OWNER, status));
    }

    @Test
    void aFailureIsReportedOnceAndItsRecoveryOnce() {
        StoreHealth health = new StoreHealth();
        StoreWriteStatus failed = new StoreWriteStatus(4, 3, true, StoreFailureKind.WRITE_FAILED, null);

        List<StoreHealth.Entry> first = health.transitions(sources(failed));
        assertEquals(1, first.size(), "a failure is news: " + first);
        assertTrue(first.get(0).line().contains("failure=" + StoreFailureKind.WRITE_FAILED));
        assertFalse(first.get(0).line().contains("/home/"), "no path in a health line");

        assertTrue(health.transitions(sources(failed)).isEmpty(), "the same failure is not repeated");

        List<StoreHealth.Entry> recovered = health.transitions(sources(new StoreWriteStatus(4, 4, false, null, null)));
        assertEquals(1, recovered.size(), "a recovery is reported once: " + recovered);
        assertTrue(health.transitions(sources(new StoreWriteStatus(4, 4, false, null, null)))
                .isEmpty());
    }

    @Test
    void aHealthyStoreIsNotNewsButAReadOnlyOneIs() {
        StoreHealth health = new StoreHealth();
        assertTrue(
                health.transitions(sources(new StoreWriteStatus(1, 1, false, null, null)))
                        .isEmpty(),
                "a first sighting of a healthy store says nothing");
        List<StoreHealth.Entry> protectedOnce =
                health.transitions(sources(new StoreWriteStatus(1, 1, false, null, StoreReadOnlyReason.CORRUPT)));
        assertEquals(1, protectedOnce.size());
        assertTrue(protectedOnce.get(0).line().contains("read-only=" + StoreReadOnlyReason.CORRUPT));
    }

    @Test
    void memoryAndTranscriptFailuresReachTheHealthViewIndependently(@TempDir Path temp) throws Exception {
        Path memoryDir = temp.resolve("memory");
        Path transcriptDir = temp.resolve("transcript");
        try (OwnerStoreWriter writer = new OwnerStoreWriter();
                MemoryStore memory = new MemoryStore(memoryDir, writer);
                TranscriptStore transcripts =
                        new TranscriptStore(transcriptDir, () -> TranscriptLimits.DEFAULTS, writer)) {
            memory.book(OWNER);
            transcripts.book(OWNER);
            // A directory where the target file belongs makes the atomic replacement fail for one store at a time.
            Files.createDirectories(memoryDir.resolve(OWNER + ".json"));
            Files.createDirectories(transcriptDir.resolve(OWNER + ".json"));

            memory.book(OWNER).touch(OWNER, "Ann", 1);
            transcripts.book(OWNER).add(OWNER, "Ann", Speaker.PLAYER, "hello", 1_000);
            memory.flushAll();
            transcripts.flushAll();
            await(() -> memory.status(OWNER).failure() != null
                    && transcripts.status(OWNER).failure() != null);

            java.util.function.Supplier<Map<String, Map<UUID, StoreWriteStatus>>> snapshot = () -> {
                Map<String, Map<UUID, StoreWriteStatus>> sources = new LinkedHashMap<>();
                sources.put("memory", memory.statuses());
                sources.put("transcript", transcripts.statuses());
                return sources;
            };

            StoreHealth health = new StoreHealth();
            List<StoreHealth.Entry> first = health.transitions(snapshot.get());
            Set<String> named = first.stream().map(StoreHealth.Entry::store).collect(Collectors.toSet());
            assertEquals(Set.of("memory", "transcript"), named, "both stores are news: " + first);
            assertTrue(first.stream().allMatch(StoreHealth.Entry::unhealthy));
            assertTrue(health.transitions(snapshot.get()).isEmpty(), "the same failure is not repeated");

            String summary = StoreHealth.summary(snapshot.get(), OWNER);
            assertTrue(summary.contains("memory owner") && summary.contains("transcript owner"), summary);
            assertTrue(summary.contains("failure=" + StoreFailureKind.WRITE_FAILED), summary);
            assertFalse(summary.contains(temp.toString()), "diagnostic text carries no path: " + summary);

            Files.delete(memoryDir.resolve(OWNER + ".json"));
            Files.delete(transcriptDir.resolve(OWNER + ".json"));
            memory.flushIfDue(System.currentTimeMillis() + 5_000);
            transcripts.flushIfDue(System.currentTimeMillis() + 5_000);
            await(() ->
                    !memory.status(OWNER).dirty() && !transcripts.status(OWNER).dirty());

            List<StoreHealth.Entry> recovered = health.transitions(snapshot.get());
            assertEquals(2, recovered.size(), "each recovery is reported once: " + recovered);
            assertTrue(recovered.stream().noneMatch(StoreHealth.Entry::unhealthy), recovered.toString());
        }
    }

    private static void await(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertTrue(condition.getAsBoolean(), "the store did not settle in time");
    }

    @Test
    void theSummaryNamesTheStoreTheRevisionAndTheReasonOnly() {
        Map<String, Map<UUID, StoreWriteStatus>> sources = Map.of(
                "goals",
                Map.of(OWNER, new StoreWriteStatus(7, 5, true, StoreFailureKind.WRITE_FAILED, null)),
                "terrain",
                Map.of(UUID.randomUUID(), new StoreWriteStatus(1, 1, false, null, null)));
        String summary = StoreHealth.summary(sources, OWNER);
        assertTrue(summary.contains("goals"), summary);
        assertTrue(summary.contains("saved=5"), summary);
        assertTrue(summary.contains("failure=" + StoreFailureKind.WRITE_FAILED), summary);
        assertFalse(summary.contains("terrain"), "another owner's store is not in this owner's summary: " + summary);
    }
}
