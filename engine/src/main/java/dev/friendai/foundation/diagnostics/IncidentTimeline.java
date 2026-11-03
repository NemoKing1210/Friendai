package dev.friendai.foundation.diagnostics;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A bounded, derived chronology of one incident: which execution started and ended, what each phase said, which
 * parent is missing and which failures and outcome verdicts the files actually contain. It never invents a pause, never
 * sums overlapping intervals and never claims a task finished because an assistant said so.
 *
 * <p>Records are grouped by stream identity — the diagnostic {@code trace} when present, otherwise a conservative
 * legacy key of boot plus session — before they are ordered, so a duplicated legacy {@code seq} in two streams can
 * never be joined into one chain.
 */
public final class IncidentTimeline {
    private static final int MAX_DEPTH = 12;
    private static final int MAX_TEXT = 160;
    private static final String ELLIPSIS = "…";

    private final LogFileHeader header;
    private final List<LogRecord> records;
    private final Completeness completeness;
    private final List<Execution> executions;

    private IncidentTimeline(
            LogFileHeader header, List<LogRecord> records, Completeness completeness, List<Execution> executions) {
        this.header = header;
        this.records = records;
        this.completeness = completeness;
        this.executions = executions;
    }

    /** One activation as the records describe it; a missing terminal is reported, never assumed. */
    public record Execution(
            String group,
            long execution,
            long plan,
            long job,
            long task,
            String tool,
            long startSeq,
            long endSeq,
            String status,
            long durationMs,
            long activeTicks,
            long heldTicks,
            long lastProgressSeq,
            String lastProgress,
            List<String> phases,
            List<LogRecord> children) {
        public Execution {
            phases = List.copyOf(phases);
            children = List.copyOf(children);
        }

        public boolean open() {
            return endSeq == 0L;
        }
    }

    public static IncidentTimeline build(LogFileHeader header, List<LogRecord> source, Completeness measured) {
        List<LogRecord> records = source == null ? List.of() : List.copyOf(source);
        Map<String, List<LogRecord>> groups = new LinkedHashMap<>();
        for (LogRecord record : records) {
            groups.computeIfAbsent(groupOf(record), key -> new ArrayList<>()).add(record);
        }
        List<Execution> executions = new ArrayList<>();
        int missingParents = 0;
        int openExecutions = 0;
        for (Map.Entry<String, List<LogRecord>> group : groups.entrySet()) {
            List<LogRecord> ordered = new ArrayList<>(group.getValue());
            ordered.sort((left, right) -> Long.compare(left.seq(), right.seq()));
            Set<Long> sequences = new HashSet<>();
            for (LogRecord record : ordered) {
                sequences.add(record.seq());
            }
            for (LogRecord record : ordered) {
                if (record.cause() > 0L && !sequences.contains(record.cause())) {
                    missingParents++;
                }
            }
            List<Execution> built = executionsOf(group.getKey(), ordered);
            for (Execution execution : built) {
                if (execution.open()) openExecutions++;
            }
            executions.addAll(built);
        }
        Completeness base = measured == null ? Completeness.NONE : measured;
        Completeness completeness = new Completeness(
                records.size(),
                base.missingParents() + missingParents,
                base.truncatedRecords(),
                base.unsupportedRecords(),
                base.dropped(),
                openExecutions,
                base.scanLimited());
        return new IncidentTimeline(header, records, completeness, executions);
    }

    public Completeness completeness() {
        return completeness;
    }

    public List<Execution> executions() {
        return List.copyOf(executions);
    }

    /**
     * Renders the report. The identity and completeness block is reserved first, so a small budget truncates the
     * narrative and says so instead of cutting away the warnings.
     */
    public String render(int maxChars) {
        int budget = Math.max(512, maxChars);
        StringBuilder out = new StringBuilder();
        out.append("# Incident ")
                .append(header == null || header.boot().isBlank() ? "unknown boot" : header.boot())
                .append(header == null ? "" : " (" + header.mod() + ", minecraft " + header.minecraft() + ")")
                .append('\n');
        out.append("Records: ")
                .append(records.size())
                .append(records.isEmpty() ? "" : " (seq " + records.get(0).seq() + ".." + lastSeq() + ")")
                .append(completeness.complete() ? "; complete" : "; PARTIAL")
                .append(": missing parents ")
                .append(completeness.missingParents())
                .append(", truncated ")
                .append(completeness.truncatedRecords())
                .append(", unsupported ")
                .append(completeness.unsupportedRecords())
                .append(", dropped ")
                .append(completeness.dropped())
                .append(", open executions ")
                .append(completeness.openExecutions());
        if (completeness.scanLimited()) out.append(", scan limit reached");
        out.append('\n');

        appendFailures(out, budget);
        appendExecutions(out, budget);
        appendOutcomes(out, budget);
        if (out.length() >= budget) {
            String text = out.substring(0, Math.max(0, budget - 40));
            int cut = text.lastIndexOf('\n');
            if (cut > 0) text = text.substring(0, cut);
            return text + "\n… partial: last included seq " + lastIncludedSeq(text) + "\n";
        }
        return out.toString();
    }

    private void appendFailures(StringBuilder out, int budget) {
        out.append("## Failures\n");
        boolean any = false;
        for (LogRecord record : records) {
            if (record.level() != LogLevel.ERROR && record.level() != LogLevel.WARN) continue;
            if (out.length() >= budget) return;
            any = true;
            out.append("seq ")
                    .append(record.seq())
                    .append(' ')
                    .append(record.event())
                    .append(' ')
                    .append(space(record.message()))
                    .append('\n');
        }
        if (!any) out.append("none recorded\n");
    }

    private void appendExecutions(StringBuilder out, int budget) {
        out.append("## Executions\n");
        if (executions.isEmpty()) {
            out.append("none recorded\n");
            return;
        }
        for (Execution execution : executions) {
            if (out.length() >= budget) return;
            out.append("exec ")
                    .append(execution.execution())
                    .append(" trace ")
                    .append(execution.group())
                    .append(" plan ")
                    .append(execution.plan())
                    .append(" job ")
                    .append(execution.job())
                    .append(" task ")
                    .append(execution.task())
                    .append(": start seq ")
                    .append(execution.startSeq())
                    .append(execution.open() ? ", NO TERMINAL" : ", end seq " + execution.endSeq())
                    .append(execution.open() ? "" : " (" + execution.status() + ", " + execution.durationMs() + " ms")
                    .append(", active ")
                    .append(execution.activeTicks())
                    .append(", held ")
                    .append(execution.heldTicks())
                    .append(")\n");
            if (execution.lastProgressSeq() > 0L) {
                out.append("  last progress seq ")
                        .append(execution.lastProgressSeq())
                        .append(": ")
                        .append(execution.lastProgress())
                        .append('\n');
            }
            for (String phase : execution.phases) {
                if (out.length() >= budget) return;
                out.append("  ").append(phase).append('\n');
            }
        }
    }

    private void appendOutcomes(StringBuilder out, int budget) {
        out.append("## Outcomes\n");
        boolean any = false;
        for (LogRecord record : records) {
            if (!LogEvents.OUTCOME_CHECK.equals(record.event())) continue;
            if (out.length() >= budget) return;
            any = true;
            out.append("plan ")
                    .append(record.plan())
                    .append(": ")
                    .append(record.data().getOrDefault("verdict", "unknown"))
                    .append(" (")
                    .append(space(record.data().getOrDefault("facts", "")))
                    .append(')');
            String unmet = space(record.data().getOrDefault("unmet", ""));
            if (!unmet.isEmpty()) out.append(" [not met: ").append(unmet).append(']');
            out.append('\n');
        }
        if (!any) out.append("none recorded\n");
    }

    private static List<Execution> executionsOf(String group, List<LogRecord> ordered) {
        Map<Long, LogRecord> bySeq = new HashMap<>();
        for (LogRecord record : ordered) bySeq.put(record.seq(), record);
        Map<Long, LogRecord> starts = new LinkedHashMap<>();
        Map<Long, LogRecord> ends = new LinkedHashMap<>();
        Map<Long, List<LogRecord>> children = new HashMap<>();
        for (LogRecord record : ordered) {
            long execution = executionOf(record);
            if (execution <= 0L) continue;
            if (LogEvents.TASK_START.equals(record.event())) {
                starts.putIfAbsent(execution, record);
            } else if (LogEvents.TASK_FINISH.equals(record.event())
                    || LogEvents.TASK_CANCEL.equals(record.event())
                    || LogEvents.TASK_REJECT.equals(record.event())) {
                ends.put(execution, record);
            } else {
                children.computeIfAbsent(execution, key -> new ArrayList<>()).add(record);
            }
        }
        Set<Long> ids = new LinkedHashSet<>(starts.keySet());
        ids.addAll(ends.keySet());
        List<Execution> built = new ArrayList<>();
        for (long id : ids) {
            LogRecord start = starts.get(id);
            LogRecord end = ends.get(id);
            LogRecord anchor = start != null ? start : end;
            long duration = start == null || end == null ? -1L : Math.max(0L, end.timeMillis() - start.timeMillis());
            List<LogRecord> members = children.getOrDefault(id, List.of());
            long lastProgressSeq = 0L;
            String lastProgress = "";
            List<String> phases = new ArrayList<>();
            for (LogRecord member : members) {
                if (LogEvents.TASK_PROGRESS.equals(member.event()) || LogEvents.TASK_PHASE.equals(member.event())) {
                    lastProgressSeq = Math.max(lastProgressSeq, member.seq());
                    lastProgress = space(member.message());
                    if (phases.size() < 8) {
                        int depth = Math.min(4, depthOf(member, bySeq));
                        phases.add("  ".repeat(depth) + "seq " + member.seq() + " " + member.event() + " "
                                + space(member.message()));
                    }
                }
            }
            built.add(new Execution(
                    group,
                    id,
                    anchor.plan(),
                    anchor.job(),
                    anchor.task(),
                    anchor.toolCall(),
                    start == null ? 0L : start.seq(),
                    end == null ? 0L : end.seq(),
                    end == null ? "" : end.data().getOrDefault("status", end.message()),
                    duration,
                    parse((end != null ? end : start).data().get("activeTicks")),
                    parse((end != null ? end : start).data().get("heldTicks")),
                    lastProgressSeq,
                    lastProgress,
                    phases,
                    members));
        }
        return built;
    }

    private static long executionOf(LogRecord record) {
        String value = record.data().get("execution");
        if (value == null || value.isBlank()) return 0L;
        try {
            return Long.parseLong(value.strip());
        } catch (NumberFormatException invalid) {
            return 0L;
        }
    }

    private static long parse(String value) {
        if (value == null || value.isBlank()) return 0L;
        try {
            return Math.max(0L, Long.parseLong(value.strip()));
        } catch (NumberFormatException invalid) {
            return 0L;
        }
    }

    static String groupOf(LogRecord record) {
        String trace = record.data().get("trace");
        if (trace != null && !trace.isBlank()) return trace;
        String session = record.session() == null || record.session().isBlank() ? "file" : record.session();
        return "legacy:" + session;
    }

    /** Collapses one line, removes the Markdown markers a provider or player could inject, and bounds the text. */
    static String space(String text) {
        if (text == null || text.isEmpty()) return "";
        String flat =
                text.replace('\n', ' ').replace('\r', ' ').replace('`', '\'').replace("```", "'''");
        return flat.length() <= MAX_TEXT ? flat : flat.substring(0, MAX_TEXT - 1) + ELLIPSIS;
    }

    private static int depthOf(LogRecord record, Map<Long, LogRecord> bySeq) {
        int depth = 0;
        LogRecord current = record;
        Set<Long> seen = new HashSet<>();
        while (current != null && current.cause() > 0L && depth < MAX_DEPTH) {
            if (!seen.add(current.seq())) break;
            current = bySeq.get(current.cause());
            if (current == null) break;
            depth++;
        }
        return Math.min(depth, MAX_DEPTH);
    }

    private long lastSeq() {
        long last = 0L;
        for (LogRecord record : records) last = Math.max(last, record.seq());
        return last;
    }

    private long lastIncludedSeq(String text) {
        long last = 0L;
        int at = 0;
        while ((at = text.indexOf("seq ", at)) >= 0) {
            int start = at + 4;
            int end = start;
            while (end < text.length() && Character.isDigit(text.charAt(end))) end++;
            if (end > start) {
                try {
                    last = Math.max(last, Long.parseLong(text.substring(start, end)));
                } catch (NumberFormatException ignored) {
                    // A non-numeric "seq" mention is not a sequence id.
                }
            }
            at = end;
        }
        return last;
    }
}
