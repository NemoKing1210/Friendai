package dev.friendai.infrastructure.config;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/** Package-local disk boundary for deterministic backup and replacement failures in store tests. */
class ConfigFileOperations {
    static final int MAX_BYTES = 8 * 1024 * 1024;

    byte[] read(Path file) throws IOException {
        try (var input = Files.newInputStream(file)) {
            byte[] bytes = input.readNBytes(MAX_BYTES + 1);
            if (bytes.length > MAX_BYTES) throw new IOException("configuration exceeds size limit");
            return bytes;
        }
    }

    Path backup(Path file, byte[] bytes, int version) throws IOException {
        Path backup = Files.createTempFile(
                file.toAbsolutePath().getParent(), file.getFileName() + ".v" + version + ".", ".bak");
        try {
            writeAndForce(backup, bytes);
            return backup;
        } catch (IOException | RuntimeException failure) {
            try {
                Files.deleteIfExists(backup);
            } catch (IOException cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    /** Requires atomic replacement: an unsupported filesystem fails without a destructive move fallback. */
    void replace(Path file, byte[] bytes) throws IOException {
        Path directory = file.toAbsolutePath().getParent();
        Files.createDirectories(directory);
        Path temp = Files.createTempFile(directory, file.getFileName().toString(), ".tmp");
        try {
            writeAndForce(temp, bytes);
            Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static void writeAndForce(Path file, byte[] bytes) throws IOException {
        try (var channel = FileChannel.open(file, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) channel.write(buffer);
            channel.force(true);
        }
    }
}
