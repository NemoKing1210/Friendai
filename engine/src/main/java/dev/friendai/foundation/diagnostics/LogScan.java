package dev.friendai.foundation.diagnostics;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Reads the tail of one log file without loading the file: at most {@link ScanLimits#maxBytes()}, at most
 * {@link ScanLimits#maxRecords()} decoded records, the header skipped and a torn last line reported.
 */
public final class LogScan {
    private LogScan() {}

    public static LogScanResult scan(Path file, ScanLimits limits, LogFilter filter, int limit, boolean newestFirst) {
        if (file == null || !Files.isRegularFile(file)) return LogScanResult.empty();
        long length;
        try {
            length = Files.size(file);
        } catch (IOException unreadable) {
            return new LogScanResult(List.of(), false, true, 0L, 0L);
        }
        int tailBytes = (int) Math.min(length, Math.max(1, limits.maxBytes()));
        boolean truncated = length > tailBytes;
        String tail;
        try (RandomAccessFile handle = new RandomAccessFile(file.toFile(), "r")) {
            handle.seek(length - tailBytes);
            byte[] bytes = new byte[tailBytes];
            handle.readFully(bytes);
            tail = new String(bytes, StandardCharsets.UTF_8);
        } catch (IOException unreadable) {
            return new LogScanResult(List.of(), truncated, true, 0L, 0L);
        }
        if (length > tailBytes) {
            int firstBreak = tail.indexOf('\n');
            tail = firstBreak < 0 ? "" : tail.substring(firstBreak + 1);
        }

        Deque<LogRecord> kept = new ArrayDeque<>();
        boolean skipped = false;
        int decoded = 0;
        for (String raw : tail.split("\n", -1)) {
            String line = raw.endsWith("\r") ? raw.substring(0, raw.length() - 1) : raw;
            if (line.isBlank()) continue;
            if (line.contains("\"kind\":\"header\"")) continue;
            LogRecord record = LogJson.decode(line).orElse(null);
            if (record == null) {
                skipped = true;
                continue;
            }
            decoded++;
            kept.addLast(record);
            while (kept.size() > limits.maxRecords()) {
                kept.removeFirst();
                truncated = true;
            }
        }
        if (decoded > limits.maxRecords()) {
            truncated = true;
        }

        List<LogRecord> matching = new ArrayList<>();
        for (LogRecord record : kept) {
            if (filter == null || filter.accepts(record)) matching.add(record);
        }
        int capped = Math.max(1, limit);
        if (matching.size() > capped) {
            truncated = true;
            matching = newestFirst
                    ? new ArrayList<>(matching.subList(matching.size() - capped, matching.size()))
                    : new ArrayList<>(matching.subList(0, capped));
        }
        if (newestFirst) {
            java.util.Collections.reverse(matching);
        }
        long oldest = 0L;
        long newest = 0L;
        if (!matching.isEmpty()) {
            oldest = matching.get(0).seq();
            newest = matching.get(0).seq();
            for (LogRecord record : matching) {
                oldest = Math.min(oldest, record.seq());
                newest = Math.max(newest, record.seq());
            }
        }
        return new LogScanResult(matching, truncated, skipped, oldest, newest);
    }
}
