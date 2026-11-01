package dev.friendai.foundation.diagnostics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DiagnosticBundleWriterTest {
    @TempDir
    Path root;

    @Test
    void writesRedactedFilesAndSkipsUnsafeNames() throws Exception {
        Path bundle = DiagnosticBundleWriter.write(
                root,
                Map.of(
                        "summary.txt", "key-shaped",
                        "../escape.txt", "nope",
                        "big.txt", "x".repeat(DiagnosticBundleWriter.MAX_FILE_BYTES + 50)),
                text -> text.replace("key-shaped", "***"),
                "20260101-000000-000");
        assertEquals("***", Files.readString(bundle.resolve("summary.txt")));
        assertFalse(Files.exists(root.resolve("escape.txt")));
        String big = Files.readString(bundle.resolve("big.txt"));
        assertTrue(big.contains("[truncated:"), big.substring(Math.max(0, big.length() - 80)));
        assertTrue(Files.readString(bundle.resolve("truncation.txt")).contains("../escape.txt"));
    }

    @Test
    void redactsBeforeTruncating() throws Exception {
        String secret = "sk-" + "a".repeat(200);
        Path bundle = DiagnosticBundleWriter.write(
                root,
                Map.of("log.txt", secret + "x".repeat(DiagnosticBundleWriter.MAX_FILE_BYTES)),
                text -> text.replace(secret, "***"),
                "20260101-000001-000");
        String stored = Files.readString(bundle.resolve("log.txt"));
        assertFalse(stored.contains(secret));
        assertTrue(stored.startsWith("***"));
    }

    @Test
    void keepsTheNewestBundles() throws Exception {
        for (int i = 0; i < DiagnosticBundleWriter.MAX_BUNDLES + 2; i++) {
            DiagnosticBundleWriter.write(
                    root, Map.of("summary.txt", "bundle " + i), text -> text, String.format("20260101-00000%d-000", i));
        }
        try (var entries = Files.list(root)) {
            List<String> names = entries.filter(Files::isDirectory)
                    .map(path -> path.getFileName().toString())
                    .sorted()
                    .toList();
            assertEquals(DiagnosticBundleWriter.MAX_BUNDLES, names.size(), names.toString());
            assertFalse(names.contains("diagnose-20260101-000000-000"), names.toString());
        }
    }
}
