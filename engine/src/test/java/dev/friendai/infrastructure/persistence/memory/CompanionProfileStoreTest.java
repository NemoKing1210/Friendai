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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
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
}
