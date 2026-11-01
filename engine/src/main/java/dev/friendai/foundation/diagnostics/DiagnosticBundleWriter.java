package dev.friendai.foundation.diagnostics;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.UnaryOperator;

/**
 * Writes one bounded, redacted diagnostic directory. The directory is built under a temporary name and moved into
 * place, so a reader never sees a half-written bundle; the newest {@value #MAX_BUNDLES} bundles are kept.
 */
public final class DiagnosticBundleWriter {
    public static final int MAX_FILE_BYTES = 64 * 1024;
    public static final int MAX_TOTAL_BYTES = 512 * 1024;
    public static final int MAX_BUNDLES = 4;
    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS").withZone(ZoneOffset.UTC);

    private DiagnosticBundleWriter() {}

    public static Path write(Path root, Map<String, String> files, UnaryOperator<String> redactor) throws IOException {
        return write(root, files, redactor, STAMP.format(Instant.now()));
    }

    /** The stamp overload exists for deterministic tests; production uses the current UTC time. */
    public static Path write(Path root, Map<String, String> files, UnaryOperator<String> redactor, String stamp)
            throws IOException {
        Files.createDirectories(root);
        Path target = uniqueTarget(root, "diagnose-" + stamp);
        Path temporary = Files.createTempDirectory(root, "diagnose-tmp-");
        List<String> notes = new ArrayList<>();
        int total = 0;
        for (Map.Entry<String, String> file : new TreeMap<>(files).entrySet()) {
            String name = safeName(file.getKey());
            if (name.isEmpty()) {
                notes.add("skipped unsafe file name: " + file.getKey());
                continue;
            }
            String text = redactor == null ? file.getValue() : redactor.apply(file.getValue());
            if (text == null) {
                text = "";
            }
            int original = utf8Length(text);
            text = truncate(text, MAX_FILE_BYTES);
            if (utf8Length(text) < original) {
                text = text + "\n[truncated: " + original + " bytes, showing " + utf8Length(text) + "]";
            }
            int bytes = utf8Length(text);
            if (total + bytes > MAX_TOTAL_BYTES) {
                notes.add("skipped " + name + ": bundle size cap reached");
                continue;
            }
            Files.writeString(temporary.resolve(name), text, StandardCharsets.UTF_8);
            total += bytes;
        }
        if (!notes.isEmpty()) {
            Files.writeString(temporary.resolve("truncation.txt"), String.join("\n", notes), StandardCharsets.UTF_8);
        }
        move(temporary, target);
        prune(root);
        return target;
    }

    private static Path uniqueTarget(Path root, String base) {
        Path target = root.resolve(base);
        int suffix = 1;
        while (Files.exists(target)) {
            target = root.resolve(base + "-" + suffix++);
        }
        return target;
    }

    private static String safeName(String name) {
        if (name == null || name.contains("/") || name.contains("\\") || name.contains("..")) {
            return "";
        }
        String safe = name.replaceAll("[^A-Za-z0-9._-]", "_");
        return safe.equals(".") || safe.isBlank() ? "" : safe;
    }

    private static String truncate(String text, int maxBytes) {
        if (utf8Length(text) <= maxBytes) {
            return text;
        }
        int low = 0;
        int high = text.length();
        while (low < high) {
            int middle = (low + high + 1) >>> 1;
            if (utf8Length(text.substring(0, middle)) <= maxBytes) {
                low = middle;
            } else {
                high = middle - 1;
            }
        }
        return text.substring(0, low);
    }

    private static int utf8Length(String text) {
        return text.getBytes(StandardCharsets.UTF_8).length;
    }

    private static void move(Path temporary, Path target) throws IOException {
        try {
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicUnsupported) {
            Files.move(temporary, target);
        }
    }

    private static void prune(Path root) throws IOException {
        List<Path> bundles = new ArrayList<>();
        try (var entries = Files.list(root)) {
            entries.filter(Files::isDirectory)
                    .filter(path -> path.getFileName().toString().startsWith("diagnose-"))
                    .filter(path -> !path.getFileName().toString().startsWith("diagnose-tmp-"))
                    .forEach(bundles::add);
        }
        bundles.sort(Comparator.comparing(path -> path.getFileName().toString()));
        while (bundles.size() > MAX_BUNDLES) {
            delete(bundles.remove(0));
        }
    }

    private static void delete(Path directory) throws IOException {
        try (var paths = Files.walk(directory)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // Retention is best effort; a locked old bundle must not fail the new one.
                }
            });
        }
    }
}
