package dev.friendai.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.foundation.persistence.StoreCodec;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** One flush serializes a bounded population; repeated flushes still save everyone. */
class OwnerFileStoreBudgetTest {
    @TempDir
    Path dir;

    @Test
    void oneFlushCapturesAtMostTheCountBudgetAndTheRestWaitForTheNext() throws IOException {
        CountingCodec codec = new CountingCodec(64);
        long[] clock = {0};
        try (OwnerFileStore<List<String>> store =
                new OwnerFileStore<>(dir, codec, Runnable::run, () -> clock[0], true)) {
            List<UUID> owners = owners(200);
            for (UUID owner : owners) store.book(owner).add("fact");

            store.flushAll();
            assertEquals(64, store.lastCaptureCount(), "one flush takes the bounded batch");
            assertTrue(store.lastCaptureBytes() > 0);
            assertTrue(owners.stream().anyMatch(store::dirty), "the rest must stay dirty for the next flush");

            for (int pass = 0; pass < 4; pass++) store.flushAll();
            assertFalse(owners.stream().anyMatch(store::dirty), "repeated flushes must save every owner");
            assertEquals(owners.size(), countFiles(dir));
        }
    }

    @Test
    void theByteBudgetStopsBeforeTheCountBudget() throws IOException {
        CountingCodec codec = new CountingCodec(100_000);
        long[] clock = {0};
        try (OwnerFileStore<List<String>> store =
                new OwnerFileStore<>(dir, codec, Runnable::run, () -> clock[0], true)) {
            List<UUID> owners = owners(200);
            for (UUID owner : owners) store.book(owner).add("fact");

            store.flushAll();

            assertEquals(21, store.lastCaptureCount(), "2 MiB of 100 kB records is 21 records");
            assertTrue(store.lastCaptureBytes() >= 2L * 1024 * 1024);
        }
    }

    @Test
    void aSingleRecordLargerThanTheBudgetIsStillCaptured() throws IOException {
        CountingCodec codec = new CountingCodec(3 * 1024 * 1024);
        long[] clock = {0};
        try (OwnerFileStore<List<String>> store =
                new OwnerFileStore<>(dir, codec, Runnable::run, () -> clock[0], true)) {
            UUID owner = new UUID(0, 1);
            store.book(owner).add("fact");

            store.flushAll();

            assertEquals(1, store.lastCaptureCount(), "a flush always makes progress");
            assertFalse(store.dirty(owner));
            assertEquals(1, countFiles(dir));
        }
    }

    private static List<UUID> owners(int count) {
        List<UUID> owners = new ArrayList<>();
        for (int index = 0; index < count; index++) owners.add(new UUID(0, index));
        return owners;
    }

    private static long countFiles(Path root) throws IOException {
        try (var files = Files.list(root)) {
            return files.filter(Files::isRegularFile).count();
        }
    }

    /** A tiny record whose payload size is fixed, so the byte budget is predictable. */
    private static final class CountingCodec implements StoreCodec<List<String>> {
        private final int payloadBytes;

        private CountingCodec(int payloadBytes) {
            this.payloadBytes = payloadBytes;
        }

        @Override
        public List<String> load(String json) {
            return new ArrayList<>();
        }

        @Override
        public String save(List<String> value) {
            return "x".repeat(payloadBytes);
        }

        @Override
        public long revision(List<String> value) {
            return value.size();
        }

        @Override
        public long maxBytes() {
            return 8 * 1024 * 1024;
        }
    }
}
