package dev.friendai.agent.turn;

import dev.friendai.execution.tool.AccessLevel;
import dev.friendai.execution.tool.Caller;
import dev.friendai.foundation.diagnostics.DebugLog;
import dev.friendai.foundation.diagnostics.LogEntry;
import dev.friendai.foundation.diagnostics.LogKind;
import dev.friendai.foundation.text.TextBudget;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.function.UnaryOperator;

/**
 * The logic behind {@code read_log}: an owner-only, redacted, bounded window of the brain's own activity. The
 * result carries entry ids and a continuation so the model can page instead of guessing.
 */
public final class LogActions {
    private LogActions() {}

    public static String read(
            Caller caller,
            UUID owner,
            DebugLog log,
            Set<LogKind> kinds,
            long since,
            long before,
            int limit,
            boolean newestFirst,
            int maxChars,
            UnaryOperator<String> redactor) {
        if (owner == null) {
            throw new IllegalArgumentException("The companion has no owner to read a log for.");
        }
        if (caller == null || caller.access() != AccessLevel.OWNER || !owner.equals(caller.uuid())) {
            throw new IllegalArgumentException("Only the owner may read the brain log.");
        }
        int capped = Math.max(1, Math.min(200, limit));
        List<LogEntry> entries = log.query(new DebugLog.Query(kinds, since, before, capped + 1, newestFirst));
        boolean more = entries.size() > capped;
        if (more) {
            entries = new ArrayList<>(entries.subList(0, capped));
        }
        if (entries.isEmpty()) {
            return "No log entries match"
                    + (kinds.isEmpty() ? "" : " kinds " + names(kinds))
                    + (since > 0 ? " after id " + since : "")
                    + (before > 0 ? " before id " + before : "")
                    + "; last id " + log.lastId() + ", " + log.size() + " buffered.";
        }
        UnaryOperator<String> clean = redactor == null ? text -> text : redactor;
        List<String> lines = new ArrayList<>();
        for (LogEntry entry : entries) {
            lines.add("[" + entry.id() + "] " + entry.kind().name() + ": " + clean.apply(entry.text()));
        }
        TextBudget.Fit fit = TextBudget.fit(lines, maxChars);
        long oldest = newestFirst
                ? entries.get(entries.size() - 1).id()
                : entries.get(0).id();
        long newest = newestFirst
                ? entries.get(0).id()
                : entries.get(entries.size() - 1).id();
        StringBuilder text = new StringBuilder("Log: ")
                .append(fit.lines().size())
                .append(" shown, ")
                .append(newestFirst ? "newest first" : "oldest first")
                .append(", ids ")
                .append(oldest)
                .append("..")
                .append(newest)
                .append("; last id ")
                .append(log.lastId())
                .append(", ")
                .append(log.size())
                .append('/')
                .append(log.capacity())
                .append(" buffered.");
        text.append('\n').append(TextBudget.join(fit));
        boolean truncated = fit.truncated() || more || fit.lines().size() < entries.size();
        text.append("\ntruncated: ").append(truncated);
        if (truncated) {
            if (newestFirst) {
                text.append("\ncontinuation: read_log before=")
                        .append(
                                fit.lines().isEmpty()
                                        ? entries.get(entries.size() - 1).id()
                                        : lastId(fit.lines()));
            } else {
                text.append("\ncontinuation: read_log since=")
                        .append(
                                fit.lines().isEmpty()
                                        ? entries.get(entries.size() - 1).id()
                                        : lastId(fit.lines()));
            }
        }
        return text.toString();
    }

    /** The id prefix of a rendered line, or 0 when the line was not an entry. */
    static long lastId(List<String> lines) {
        if (lines.isEmpty()) {
            return 0;
        }
        String line = lines.get(lines.size() - 1);
        if (!line.startsWith("[")) {
            return 0;
        }
        int close = line.indexOf(']');
        if (close < 2) {
            return 0;
        }
        try {
            return Long.parseLong(line.substring(1, close));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String names(Set<LogKind> kinds) {
        List<String> names = new ArrayList<>();
        for (LogKind kind : kinds) {
            names.add(kind.name());
        }
        names.sort(String.CASE_INSENSITIVE_ORDER);
        return String.join(", ", names).toLowerCase(Locale.ROOT);
    }
}
