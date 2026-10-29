package dev.friendai.infrastructure.persistence;

import java.io.FilterInputStream;
import java.io.FilterReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** NIO seam for deterministic failures. The byte bound also covers files that grow after the size check. */
class OwnerFileOperations {
    private static final Logger LOGGER = LoggerFactory.getLogger("friendai/store");
    /** Distinguishes failed disk reads from parser exceptions that happen to extend IOException. */
    static final class ReadAttempt {
        IOException failure;

        Reader observe(Reader source) {
            return new FilterReader(source) {
                @Override
                public int read() throws IOException {
                    try {
                        return super.read();
                    } catch (IOException problem) {
                        failure = problem;
                        throw problem;
                    }
                }

                @Override
                public int read(char[] chars, int offset, int length) throws IOException {
                    try {
                        return super.read(chars, offset, length);
                    } catch (IOException problem) {
                        failure = problem;
                        throw problem;
                    }
                }

                @Override
                public long skip(long count) throws IOException {
                    try {
                        return super.skip(count);
                    } catch (IOException problem) {
                        failure = problem;
                        throw problem;
                    }
                }
            };
        }
    }

    long size(Path file) throws IOException {
        return Files.size(file);
    }

    Reader reader(Path file, long maxBytes) throws IOException {
        return new InputStreamReader(
                new FilterInputStream(Files.newInputStream(file)) {
                    private long consumed;

                    private void count(int bytes) throws IOException {
                        if (bytes > 0 && (consumed += bytes) > maxBytes) throw new OversizedInputException();
                    }

                    @Override
                    public int read() throws IOException {
                        int value = in.read();
                        if (value != -1) count(1);
                        return value;
                    }

                    @Override
                    public int read(byte[] bytes, int offset, int length) throws IOException {
                        int count =
                                in.read(bytes, offset, (int) Math.min(length, Math.max(1, maxBytes - consumed + 1)));
                        count(count);
                        return count;
                    }
                },
                StandardCharsets.UTF_8);
    }

    void backup(Path source, Path backup) throws IOException {
        Files.copy(source, backup, StandardCopyOption.REPLACE_EXISTING);
    }

    Path temporary(Path target) throws IOException {
        Path parent = target.toAbsolutePath().getParent();
        Files.createDirectories(parent);
        return Files.createTempFile(parent, target.getFileName().toString(), ".tmp");
    }

    void write(Path temporary, byte[] bytes) throws IOException {
        Files.write(temporary, bytes);
    }

    void move(Path source, Path target, boolean atomic) throws IOException {
        if (atomic) Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        else Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
    }

    /** Non-atomic replacement must not consume the only previous copy, even if move fails after removal. */
    void replace(Path source, Path target) throws IOException {
        try {
            move(source, target, true);
            return;
        } catch (AtomicMoveNotSupportedException unsupported) {
            // Preserve the previous target before the fallback may remove or partially replace it.
        }
        Path recovery = protect(target);
        boolean releaseRecovery = true;
        try {
            move(source, target, false);
        } catch (IOException | RuntimeException failure) {
            if (recovery != null) {
                releaseRecovery = false;
                try {
                    restore(recovery, target);
                } catch (IOException | RuntimeException restoreFailure) {
                    LOGGER.warn("Could not restore owner store target; recovery file retained");
                    throw restoreFailure;
                }
                releaseRecovery = true;
            }
            throw failure;
        } finally {
            if (recovery != null && releaseRecovery) cleanupRecovery(recovery);
        }
    }

    Path protect(Path target) throws IOException {
        try {
            size(target);
        } catch (NoSuchFileException absent) {
            return null;
        }
        Path recovery = Files.createTempFile(
                target.toAbsolutePath().getParent(), target.getFileName().toString(), ".recovery");
        try {
            backup(target, recovery);
            return recovery;
        } catch (IOException | RuntimeException failure) {
            cleanupRecovery(recovery);
            throw failure;
        }
    }

    /** Copying rather than moving keeps recovery bytes available if restoration itself fails. */
    void restore(Path recovery, Path target) throws IOException {
        Files.copy(recovery, target, StandardCopyOption.REPLACE_EXISTING);
    }

    private void cleanupRecovery(Path recovery) {
        try {
            cleanup(recovery);
        } catch (IOException | RuntimeException failure) {
            LOGGER.warn("Could not remove owner store recovery file");
        }
    }

    void cleanup(Path temporary) throws IOException {
        Files.deleteIfExists(temporary);
    }

    static final class OversizedInputException extends IOException {}
}
