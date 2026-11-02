package dev.friendai.foundation.diagnostics;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A bounded Markdown summary of one log file: what was asked, what was done, every error with its cause chain, the
 * outcome and the counters. The digest is never the source of truth; it is regenerated from the records.
 */
public final class LogDigest {
    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss 'UTC'").withZone(ZoneOffset.UTC);
    private static final String TRUNCATED = "[truncated]";
    private static final int MAX_CHAIN = 6;

    private LogDigest() {}

    public static String render(LogFileHeader header, List<LogRecord> records, int maxChars) {
        return render(header, records, 0L, false, maxChars);
    }

    public static String render(
            LogFileHeader header, List<LogRecord> records, long dropped, boolean skippedLine, int maxChars) {
        List<LogRecord> ordered = new ArrayList<>(records == null ? List.of() : records);
        ordered.sort((left, right) -> Long.compare(left.seq(), right.seq()));
        Map<Long, LogRecord> bySeq = new HashMap<>();
        for (LogRecord record : ordered) {
            bySeq.put(record.seq(), record);
        }

        StringBuilder text = new StringBuilder();
        text.append("# Session ")
                .append(STAMP.format(Instant.ofEpochMilli(header.started())))
                .append('\n');
        text.append("owner: ")
                .append(header.owner() == null ? "server" : header.owner())
                .append('\n');
        text.append("name: ").append(header.name()).append('\n');
        text.append("boot: ").append(header.boot()).append('\n');
        text.append("mod: ").append(header.mod()).append('\n');
        text.append("minecraft: ").append(header.minecraft()).append('\n');
        text.append("level: ").append(header.level()).append('\n');
        text.append("categories: ")
                .append(String.join(", ", header.categories()))
                .append("\n\n");

        text.append("## What was asked\n");
        for (LogRecord record : ordered) {
            if (record.kind() == LogKind.USER) {
                line(text, record, 0);
            }
        }
        text.append('\n');

        text.append("## What was done\n");
        for (LogRecord record : ordered) {
            if (record.level() == LogLevel.ERROR || record.kind() == LogKind.USER) continue;
            if (LogEvents.ASSISTANT_DELIVERED.equals(record.event())
                    || LogEvents.TASK_FINISH.equals(record.event())
                    || LogEvents.TASK_CANCEL.equals(record.event())) continue;
            line(text, record, 0);
        }
        text.append('\n');

        text.append("## Errors and chains\n");
        for (LogRecord record : ordered) {
            if (record.level() != LogLevel.ERROR) continue;
            List<LogRecord> chain = chain(record, bySeq);
            LogRecord first = chain.get(0);
            if (first.cause() != 0 && (!bySeq.containsKey(first.cause()) || chain.size() == MAX_CHAIN)) {
                text.append("(root)\n");
            }
            for (int depth = 0; depth < chain.size(); depth++) {
                line(text, chain.get(depth), depth);
            }
        }
        if (ordered.stream().noneMatch(record -> record.level() == LogLevel.ERROR)) {
            text.append("- none\n");
        }
        text.append('\n');

        text.append("## Outcome\n");
        boolean anyOutcome = false;
        for (LogRecord record : ordered) {
            if (LogEvents.ASSISTANT_DELIVERED.equals(record.event())) {
                text.append("- delivered: ").append(record.message()).append('\n');
                anyOutcome = true;
            } else if (LogEvents.TASK_FINISH.equals(record.event()) || LogEvents.TASK_CANCEL.equals(record.event())) {
                text.append("- ")
                        .append(record.event())
                        .append(": ")
                        .append(record.message())
                        .append('\n');
                anyOutcome = true;
            }
        }
        if (!anyOutcome) text.append("- none\n");
        text.append('\n');

        long errors = ordered.stream()
                .filter(record -> record.level() == LogLevel.ERROR)
                .count();
        long warnings = ordered.stream()
                .filter(record -> record.level() == LogLevel.WARN)
                .count();
        text.append("## Counters\n");
        text.append("records: ").append(ordered.size()).append('\n');
        text.append("errors: ").append(errors).append('\n');
        text.append("warnings: ").append(warnings).append('\n');
        text.append("dropped: ").append(dropped).append('\n');
        text.append("skipped lines: ").append(skippedLine ? 1 : 0).append('\n');

        return bound(text.toString(), maxChars);
    }

    private static List<LogRecord> chain(LogRecord error, Map<Long, LogRecord> bySeq) {
        List<LogRecord> chain = new ArrayList<>();
        LogRecord cursor = error;
        while (cursor != null && chain.size() < MAX_CHAIN) {
            chain.add(0, cursor);
            long cause = cursor.cause();
            if (cause == 0 || cause == cursor.seq()) break;
            cursor = bySeq.get(cause);
        }
        return chain;
    }

    private static void line(StringBuilder text, LogRecord record, int depth) {
        text.append("  ".repeat(Math.max(0, depth)))
                .append("- [")
                .append(record.seq())
                .append("] ")
                .append(record.category().token())
                .append('/')
                .append(record.event())
                .append(": ")
                .append(record.message());
        if (record.durationMs() >= 0) {
            text.append(" (").append(record.durationMs()).append(" ms)");
        }
        text.append('\n');
    }

    private static String bound(String text, int maxChars) {
        if (maxChars <= 0 || text.length() <= maxChars) return text;
        if (maxChars <= TRUNCATED.length()) return TRUNCATED.substring(0, maxChars);
        return text.substring(0, maxChars - TRUNCATED.length()) + TRUNCATED;
    }
}
