package dev.friendai.foundation.diagnostics;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Decides which files to delete. Pure: the writer supplies the file list, the clock and its open files. Age, the
 * per-owner session count and the total size are applied in that order. An active file is never selected, but its bytes
 * still count toward the cap: a set of open files larger than the budget cannot be bounded by deleting others.
 */
public final class LogRetention {
    public record Limits(long maxTotalBytes, int retentionDays, int maxSessionsPerOwner) {}

    /** The deletion plan plus what would remain, so the caller can report an overflow it cannot fix by deleting. */
    public record Result(List<Path> delete, long retainedBytes, long cap) {}

    private static final long DAY_MILLIS = 86_400_000L;

    private LogRetention() {}

    public static Result plan(List<LogFileInfo> files, Limits limits, long nowMillis, Set<Path> activeFiles) {
        Set<Path> active = activeFiles == null ? Set.of() : activeFiles;
        List<LogFileInfo> candidates = new ArrayList<>();
        for (LogFileInfo file : files) {
            if (file != null && !active.contains(file.path())) {
                candidates.add(file);
            }
        }
        candidates.sort(Comparator.comparingLong(LogFileInfo::modifiedMillis)
                .thenComparing(file -> file.path().toString()));

        Map<Path, LogFileInfo> deleted = new LinkedHashMap<>();
        long window = Math.max(1, limits.retentionDays()) * DAY_MILLIS;
        long cutoff = nowMillis - window;
        List<LogFileInfo> remaining = new ArrayList<>();
        for (LogFileInfo file : candidates) {
            if (file.modifiedMillis() < cutoff) {
                deleted.putIfAbsent(file.path(), file);
            } else {
                remaining.add(file);
            }
        }

        Map<UUID, List<LogFileInfo>> sessions = new LinkedHashMap<>();
        for (LogFileInfo file : remaining) {
            if (file.kind() == LogFileKind.SESSION && file.owner() != null) {
                sessions.computeIfAbsent(file.owner(), ignored -> new ArrayList<>())
                        .add(file);
            }
        }
        int keep = Math.max(1, limits.maxSessionsPerOwner());
        for (List<LogFileInfo> owned : sessions.values()) {
            owned.sort(Comparator.comparingLong(LogFileInfo::modifiedMillis)
                    .reversed()
                    .thenComparing(file -> file.path().toString()));
            for (int index = keep; index < owned.size(); index++) {
                deleted.putIfAbsent(owned.get(index).path(), owned.get(index));
            }
        }
        if (!deleted.isEmpty()) {
            Set<Path> gone = new LinkedHashSet<>(deleted.keySet());
            remaining.removeIf(file -> gone.contains(file.path()));
        }

        // Active files cannot be deleted, but their bytes are on disk: the budget covers every retained file.
        long total = 0L;
        for (LogFileInfo file : files) {
            if (file != null && active.contains(file.path())) {
                total += file.bytes();
            }
        }
        for (LogFileInfo file : remaining) {
            total += file.bytes();
        }
        long cap = Math.max(0L, limits.maxTotalBytes());
        for (LogFileInfo file : remaining) {
            if (total <= cap) break;
            deleted.putIfAbsent(file.path(), file);
            total -= file.bytes();
        }

        List<LogFileInfo> ordered = new ArrayList<>(deleted.values());
        ordered.sort(Comparator.comparingLong(LogFileInfo::modifiedMillis)
                .thenComparing(file -> file.path().toString()));
        List<Path> paths = new ArrayList<>(ordered.size());
        for (LogFileInfo file : ordered) {
            paths.add(file.path());
        }
        long retained = 0L;
        Set<Path> deleting = new LinkedHashSet<>(paths);
        for (LogFileInfo file : files) {
            if (file != null && !deleting.contains(file.path())) {
                retained += file.bytes();
            }
        }
        return new Result(paths, retained, cap);
    }
}
