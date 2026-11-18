package dev.friendai.infrastructure.persistence.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.features.memory.CompanionDescription;
import dev.friendai.features.memory.CompanionProfile;
import dev.friendai.features.memory.OwnerProfile;
import dev.friendai.foundation.persistence.UnsupportedStoreVersionException;
import dev.friendai.infrastructure.persistence.OwnerStoreWriter;
import java.io.IOException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CompanionProfileStoreTest {
    private static final UUID A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID B = UUID.fromString("00000000-0000-0000-0000-00000000000b");

    @TempDir
    Path dir;

    @Test
    void missingOwnerDefaultsDoNotWriteAndOwnersRoundTripIndependently() throws Exception {
        byte[] otherOwnerBefore;
        try (var writer = new OwnerStoreWriter();
                var store = new CompanionProfileStore(dir, writer)) {
            assertEquals(CompanionProfile.EMPTY, store.profile(A));
            assertFalse(Files.exists(dir.resolve(A + ".json")));
            store.save(A, profile("Mira", "Patient guide", "ru", "Alex"));
            store.save(B, profile("Pip", "Builder", "auto", ""));
            assertEquals(1, store.revision(A));
            assertEquals(1, store.revision(B));
        }
        otherOwnerBefore = Files.readAllBytes(dir.resolve(B + ".json"));
        try (var writer = new OwnerStoreWriter();
                var store = new CompanionProfileStore(dir, writer)) {
            store.save(A, profile("Mira II", "Patient guide", "ru", "Alex"));
        }
        assertTrue(
                java.util.Arrays.equals(otherOwnerBefore, Files.readAllBytes(dir.resolve(B + ".json"))),
                "saving A must leave B's record untouched");
        try (var writer = new OwnerStoreWriter();
                var reopened = new CompanionProfileStore(dir, writer)) {
            assertEquals(profile("Mira II", "Patient guide", "ru", "Alex"), reopened.profile(A));
            assertEquals(profile("Pip", "Builder", "auto", ""), reopened.profile(B));
        }
    }

    @Test
    void corruptAndUnsupportedRecordsRemainSafe() throws Exception {
        Path corrupt = dir.resolve(A + ".json");
        Files.createDirectories(dir);
        Files.writeString(corrupt, "{broken");
        try (var writer = new OwnerStoreWriter();
                var store = new CompanionProfileStore(dir, writer)) {
            assertEquals(CompanionProfile.EMPTY, store.profile(A));
            assertTrue(Files.exists(corrupt.resolveSibling(corrupt.getFileName() + ".bak")));
        }
        Path future = dir.resolve(B + ".json");
        String original = "{\"formatVersion\":99,\"revision\":4}";
        Files.writeString(future, original);
        try (var writer = new OwnerStoreWriter();
                var store = new CompanionProfileStore(dir, writer)) {
            assertThrows(UnsupportedStoreVersionException.class, () -> store.profile(B));
            assertEquals(original, Files.readString(future));
        }
    }

    @Test
    void semanticallyInvalidProfileIsBackedUpAndCannotBeOverwritten() throws Exception {
        Files.createDirectories(dir);
        Path original = dir.resolve(A + ".json");
        String invalid = "{\"formatVersion\":1,\"revision\":3,\"name\":null,\"summary\":\""
                + "x".repeat(301)
                + "\",\"details\":\"\",\"language\":\"auto\",\"address\":\"\"}";
        byte[] originalBytes = invalid.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(original, originalBytes);

        try (var writer = new OwnerStoreWriter();
                var store = new CompanionProfileStore(dir, writer)) {
            assertEquals(CompanionProfile.EMPTY, store.profile(A));
            assertEquals(
                    dev.friendai.foundation.persistence.StoreReadOnlyReason.CORRUPT,
                    store.status(A).readOnlyReason());
            assertTrue(Files.exists(original.resolveSibling(original.getFileName() + ".bak")));
            assertTrue(java.util.Arrays.equals(
                    originalBytes, Files.readAllBytes(original.resolveSibling(original.getFileName() + ".bak"))));

            store.save(A, profile("Mira", "Valid", "auto", ""));
            store.flushAll();
        }

        assertTrue(java.util.Arrays.equals(originalBytes, Files.readAllBytes(original)));
    }

    private static CompanionProfile profile(String name, String summary, String language, String address) {
        return new CompanionProfile(
                name, new CompanionDescription(summary, "details"), new OwnerProfile(language, address));
    }

    @RepeatedTest(32)
    void profileWriterStopsBeforeItsDirectoryIsReleased() throws Exception {
        Path records = Files.createDirectory(dir.resolve("profiles"));
        var ownerFile = records.resolve(A + ".json");
        var writer = new OwnerStoreWriter();
        var thread = new AtomicReference<Thread>();
        var store = new CompanionProfileStore(records, writer);
        try (writer;
                store) {
            store.save(A, profile("Mira", "Patient guide", "ru", "Alex"));
            store.flushAll();
            assertTrue(
                    writer.trySubmit(() -> thread.set(Thread.currentThread())), "writer accepted the shutdown probe");
        }

        Thread worker = thread.get();
        assertTrue(worker != null, "the writer drained its queued work");
        worker.join(1_000);
        assertFalse(worker.isAlive(), "writer stopped before the directory was released");
        assertFalse(store.status(A).dirty(), "no outstanding profile write");
        try {
            Files.delete(ownerFile);
            deleteReleasedDirectory(records);
        } catch (IOException failure) {
            String files;
            try (var paths = Files.list(records)) {
                files = paths.map(Path::getFileName).toList().toString();
            } catch (IOException listingFailure) {
                failure.addSuppressed(listingFailure);
                files = "unavailable";
            }
            throw new AssertionError(
                    "Profile teardown failed; writer=" + worker.getState() + ", store=" + store.status(A)
                            + ", remaining files=" + files,
                    failure);
        }
        assertFalse(writer.trySubmit(() -> {
            throw new AssertionError("a closed writer cannot publish a new file");
        }));
        assertFalse(Files.exists(records), "the closed writer did not recreate the directory");
    }

    private static void deleteReleasedDirectory(Path directory) throws IOException, InterruptedException {
        for (int attempt = 0; ; attempt++) {
            try {
                Files.delete(directory);
                return;
            } catch (DirectoryNotEmptyException failure) {
                // Windows sometimes reports an empty directory as non-empty immediately after deletion.
                // A visible file is still a fixture failure. Only retry after joining the writer.
                if (attempt == 9 || !System.getProperty("os.name", "").startsWith("Windows")) throw failure;
                try (var remaining = Files.list(directory)) {
                    if (remaining.findAny().isPresent()) throw failure;
                }
                Thread.sleep(20);
            }
        }
    }
}
