package dev.friendai.agent.turn;

import dev.friendai.execution.tool.AccessLevel;
import dev.friendai.execution.tool.Caller;
import dev.friendai.foundation.diagnostics.LogFilter;
import dev.friendai.foundation.diagnostics.LogNames;
import dev.friendai.foundation.diagnostics.LogRecord;
import dev.friendai.foundation.diagnostics.LogScan;
import dev.friendai.foundation.diagnostics.LogScanResult;
import dev.friendai.foundation.diagnostics.ScanLimits;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

/**
 * The logic behind {@code logs_search}: an owner-only, redacted, bounded window over the durable JSONL files of the
 * owner and the two most recent UTC days. The result carries sequence ids and a continuation.
 */
public final class LogSearch {
    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC);
    private static final long DAY_MILLIS = 86_400_000L;

    private LogSearch() {}

    public static String request(
            Caller caller,
            UUID owner,
            Path ownerLogRoot,
            LogFilter filter,
            long before,
            int limit,
            boolean newestFirst,
            int maxChars,
            UnaryOperator<String> redactor,
            long nowMillis) {
        if (owner == null) {
            throw new IllegalArgumentException("The companion has no owner to read logs for.");
        }
        if (caller == null || caller.access() != AccessLevel.OWNER || !owner.equals(caller.uuid())) {
            throw new IllegalArgumentException("Only the owner may search the log files.");
        }
        if (ownerLogRoot == null || !Files.isDirectory(ownerLogRoot)) {
            return "No files yet";
        }
        List<Path> files = files(ownerLogRoot, nowMillis);
        if (files.isEmpty()) {
            return "File logging is off";
        }
        int capped = Math.max(1, Math.min(200, limit));
        List<LogRecord> records = new ArrayList<>();
        boolean truncated = false;
        for (Path file : files) {
            LogScanResult result = LogScan.scan(
                    file, ScanLimits.DEFAULT, filter == null ? LogFilter.all() : filter, capped + 1, false);
            records.addAll(result.records());
            truncated |= result.truncated();
        }
        records.sort(Comparator.comparingLong(LogRecord::seq));
        if (before > 0) {
            List<LogRecord> older = new ArrayList<>();
            for (LogRecord record : records) {
                if (record.seq() < before) older.add(record);
                else truncated = true;
            }
            records = older;
        }
        if (records.size() > capped) {
            truncated = true;
            records = newestFirst
                    ? new ArrayList<>(records.subList(records.size() - capped, records.size()))
                    : new ArrayList<>(records.subList(0, capped));
        }
        if (newestFirst) {
            java.util.Collections.reverse(records);
        }
        if (records.isEmpty()) {
            return "No records match";
        }
        UnaryOperator<String> clean = redactor == null ? text -> text : redactor;
        List<String> lines = new ArrayList<>();
        long oldest = Long.MAX_VALUE;
        long newest = Long.MIN_VALUE;
        for (LogRecord record : records) {
            oldest = Math.min(oldest, record.seq());
            newest = Math.max(newest, record.seq());
            lines.add(clean.apply("[" + record.seq() + "] "
                    + STAMP.format(Instant.ofEpochMilli(record.timeMillis())) + " "
                    + record.level().token() + " " + record.category().token() + " " + record.event() + " "
                    + record.message()));
        }
        StringBuilder text = new StringBuilder("Log files: ")
                .append(records.size())
                .append(" shown, ")
                .append(newestFirst ? "newest first" : "oldest first")
                .append(", seq ")
                .append(oldest)
                .append("..")
                .append(newest)
                .append("; truncated: ")
                .append(truncated);
        int used = text.length();
        boolean cut = false;
        for (String line : lines) {
            if (used + line.length() + 1 > maxChars) {
                cut = true;
                break;
            }
            text.append('\n').append(line);
            used += line.length() + 1;
        }
        if (cut || truncated) {
            text.append("\ntruncated: ").append(true);
            text.append("\ncontinuation: logs_search before=").append(oldest);
        }
        return text.toString();
    }

    private static List<Path> files(Path ownerLogRoot, long nowMillis) {
        List<Path> files = new ArrayList<>();
        for (long day : new long[] {nowMillis, nowMillis - DAY_MILLIS}) {
            Path dir = ownerLogRoot.resolve(LogNames.dayDir(day));
            if (!Files.isDirectory(dir)) continue;
            try (Stream<Path> paths = Files.list(dir)) {
                paths.filter(path -> path.toString().endsWith(".jsonl"))
                        .sorted()
                        .forEach(files::add);
            } catch (IOException ignored) {
                // An unreadable day is skipped; another day may still answer.
            }
        }
        return files;
    }
}
