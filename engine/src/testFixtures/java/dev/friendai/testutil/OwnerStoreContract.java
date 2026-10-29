package dev.friendai.testutil;

import dev.friendai.foundation.persistence.StoreFailureKind;
import dev.friendai.foundation.persistence.StoreReadOnlyReason;
import dev.friendai.foundation.persistence.StoreWriteStatus;
import dev.friendai.foundation.persistence.UnsupportedStoreVersionException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Reusable observable owner-file scenarios. Adapters supply domain mutations and deterministic I/O faults. */
public final class OwnerStoreContract {
    private OwnerStoreContract() {}

    public enum Fault {
        NONE,
        BACKUP,
        ATOMIC_UNSUPPORTED,
        MOVE,
        PARTIAL_MOVE,
        WRITE_DENIED,
        READ_DENIED,
        QUEUE
    }

    public interface Harness extends AutoCloseable {
        Path file();

        String corruptInput();

        String unsupportedInput();

        String oversizedInput();

        String validInput();

        int initialMutations();

        void read();

        void mutate();

        StoreWriteStatus status();

        void flush();

        void runNext();

        int queued();

        void clearFault();

        void closeStore();

        boolean writerUsable();

        int persistedMutations() throws Exception;

        @Override
        void close();
    }

    @FunctionalInterface
    public interface Factory {
        Harness open(Path dir, Fault fault);
    }

    @FunctionalInterface
    public interface Check {
        void run(Harness harness) throws Exception;
    }

    public record Case(String name, Fault fault, Check check) {
        public void run(Factory factory, Path dir) throws Exception {
            Files.createDirectories(dir);
            try (Harness harness = factory.open(dir, fault)) {
                check.run(harness);
            }
        }
    }

    public static List<Case> cases() {
        return List.of(
                new Case("missing file stays absent", Fault.NONE, h -> {
                    h.read();
                    h.flush();
                    require(!Files.exists(h.file()) && h.queued() == 0, "missing file was written");
                    require(h.status().currentRevision() == h.status().savedRevision(), "fresh book is dirty");
                }),
                new Case("corruption backup preserves the original as read-only", Fault.NONE, h -> {
                    Files.writeString(h.file(), h.corruptInput());
                    h.read();
                    require(Files.readString(backup(h)).equals(h.corruptInput()), "backup lost original");
                    require(
                            h.status().readOnlyReason() == StoreReadOnlyReason.CORRUPT,
                            "corrupt recovery was not marked read-only");
                    h.mutate();
                    h.flush();
                    require(h.queued() == 0, "corrupt recovery was queued for overwrite");
                    require(Files.readString(h.file()).equals(h.corruptInput()), "corrupt original was overwritten");
                }),
                new Case("oversized input stays read-only", Fault.NONE, h -> {
                    Files.writeString(h.file(), h.oversizedInput());
                    h.read();
                    h.mutate();
                    h.flush();
                    require(h.status().readOnlyReason() == StoreReadOnlyReason.OVERSIZED, "size status missing");
                    require(
                            h.queued() == 0 && Files.readString(h.file()).equals(h.oversizedInput()),
                            "oversized original replaced");
                }),
                new Case("unsupported input stays read-only", Fault.NONE, h -> {
                    Files.writeString(h.file(), h.unsupportedInput());
                    boolean rejected = false;
                    try {
                        h.read();
                    } catch (UnsupportedStoreVersionException expected) {
                        rejected = true;
                    }
                    require(rejected, "unsupported version accepted");
                    h.flush();
                    require(h.status().readOnlyReason() == StoreReadOnlyReason.UNSUPPORTED, "version status missing");
                    require(
                            !Files.exists(backup(h))
                                    && Files.readString(h.file()).equals(h.unsupportedInput()),
                            "unsupported original changed");
                }),
                new Case("backup failure protects original", Fault.BACKUP, h -> {
                    Files.writeString(h.file(), h.corruptInput());
                    h.read();
                    h.mutate();
                    h.flush();
                    require(h.status().failure() == StoreFailureKind.BACKUP_FAILED, "backup failure hidden");
                    require(
                            h.status().readOnlyReason() == StoreReadOnlyReason.BACKUP_FAILED,
                            "backup failure writable");
                    require(
                            h.queued() == 0 && Files.readString(h.file()).equals(h.corruptInput()),
                            "unbacked original replaced");
                }),
                new Case("read denial protects original", Fault.READ_DENIED, h -> {
                    Files.writeString(h.file(), h.validInput());
                    h.read();
                    h.mutate();
                    h.flush();
                    require(h.status().failure() == StoreFailureKind.READ_FAILED, "read failure hidden");
                    require(h.status().readOnlyReason() == StoreReadOnlyReason.READ_FAILED, "read failure writable");
                    require(
                            h.queued() == 0 && Files.readString(h.file()).equals(h.validInput()),
                            "unread original replaced");
                }),
                new Case("atomic replacement falls back", Fault.ATOMIC_UNSUPPORTED, h -> {
                    h.read();
                    h.mutate();
                    h.flush();
                    h.runNext();
                    require(h.persistedMutations() == 1 && h.status().failure() == null, "replacement fallback failed");
                }),
                new Case(
                        "move failure preserves last target and retries newest",
                        Fault.MOVE,
                        OwnerStoreContract::failedWrite),
                new Case(
                        "partial replacement failure restores prior target and retries newest",
                        Fault.PARTIAL_MOVE,
                        OwnerStoreContract::failedWrite),
                new Case(
                        "write denial preserves last target and retries newest",
                        Fault.WRITE_DENIED,
                        OwnerStoreContract::failedWrite),
                new Case("queue rejection retries latest revision", Fault.QUEUE, h -> {
                    h.read();
                    h.mutate();
                    h.flush();
                    require(
                            h.status().failure() == StoreFailureKind.QUEUE_FULL
                                    && !h.status().pending(),
                            "queue rejection hidden");
                    h.mutate();
                    h.clearFault();
                    h.flush();
                    h.runNext();
                    require(
                            h.persistedMutations() == 2 && h.status().failure() == null,
                            "latest rejected revision not retried");
                }),
                new Case("borrowed writer survives store close", Fault.NONE, h -> {
                    h.read();
                    h.mutate();
                    h.flush();
                    h.runNext();
                    h.closeStore();
                    h.closeStore();
                    require(h.status().savedRevision() == h.status().currentRevision(), "close lost saved revision");
                    require(h.writerUsable(), "store closed borrowed writer");
                }),
                new Case("pending writes are immutable and deduplicated", Fault.NONE, h -> {
                    h.read();
                    h.mutate();
                    h.flush();
                    h.flush();
                    require(h.queued() == 1 && h.status().pending(), "duplicate pending write");
                    h.mutate();
                    h.runNext();
                    require(h.persistedMutations() == 1, "live value reached writer");
                    require(
                            h.status().savedRevision() < h.status().currentRevision(),
                            "old completion cleared newer revision");
                    h.flush();
                    h.runNext();
                    require(
                            h.persistedMutations() == 2
                                    && h.status().savedRevision() == h.status().currentRevision(),
                            "newest revision not settled");
                }));
    }

    private static void failedWrite(Harness h) throws Exception {
        Files.writeString(h.file(), h.validInput());
        h.read();
        h.mutate();
        h.flush();
        h.runNext();
        require(h.status().failure() == StoreFailureKind.WRITE_FAILED, "write failure hidden");
        require(
                Files.exists(h.file()) && Files.readString(h.file()).equals(h.validInput()),
                "failed write replaced target");
        require(h.status().savedRevision() < h.status().currentRevision(), "failure cleared dirty revision");
        h.mutate();
        h.clearFault();
        h.flush();
        h.runNext();
        require(
                h.persistedMutations() == h.initialMutations() + 2 && h.status().failure() == null,
                "retry did not save latest value");
    }

    private static Path backup(Harness h) {
        return h.file().resolveSibling(h.file().getFileName() + ".bak");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
