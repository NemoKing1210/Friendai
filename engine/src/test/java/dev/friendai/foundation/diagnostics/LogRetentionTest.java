package dev.friendai.foundation.diagnostics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LogRetentionTest {
    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID OTHER = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final long DAY = 86_400_000L;

    private static LogFileInfo file(String name, long bytes, long modified, UUID owner, LogFileKind kind) {
        return new LogFileInfo(Path.of(name), bytes, modified, owner, kind);
    }

    private static LogRetention.Limits limits(long total, int days, int sessions) {
        return new LogRetention.Limits(total, days, sessions);
    }

    @Test
    void dayDirAndFileNamesAreUtc() {
        long instant = Instant.parse("2026-11-03T00:30:00Z").toEpochMilli();
        assertEquals("2026-11-03", LogNames.dayDir(instant));
        assertEquals("boot-7f3c.jsonl", LogNames.file(LogFileKind.BOOT, null, "7f3c", instant));
        assertEquals("session-003000-7f3c.jsonl", LogNames.file(LogFileKind.SESSION, OWNER, "7f3c", instant));
        assertEquals("client-7f3c.jsonl", LogNames.file(LogFileKind.CLIENT, null, "7f3c", instant));
        assertEquals("boot-7f3c.jsonl", LogNames.part("boot-7f3c.jsonl", 1));
        assertEquals("boot-7f3c-2.jsonl", LogNames.part("boot-7f3c.jsonl", 2));
        assertEquals("session-003000-7f3c.md", LogNames.digestName("session-003000-7f3c.jsonl"));
    }

    @Test
    void deletesOldestUntilUnderTheSizeCap() {
        long now = 1_000_000_000L;
        List<LogFileInfo> files = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            files.add(file(
                    "server-2026-11-0" + (i + 1) + ".jsonl",
                    1_000_000L,
                    now - (5 - i) * 1000L,
                    null,
                    LogFileKind.BOOT));
        }
        List<Path> deleted = LogRetention.plan(files, limits(3_000_000L, 7, 20), now, Set.of());
        assertEquals(List.of(files.get(0).path(), files.get(1).path()), deleted);
    }

    @Test
    void deletesFilesOlderThanTheWindow() {
        long now = 10 * DAY;
        LogFileInfo old = file("old.jsonl", 10L, now - 11 * DAY, null, LogFileKind.BOOT);
        LogFileInfo recent = file("recent.jsonl", 10L, now - 5 * DAY, null, LogFileKind.BOOT);
        List<Path> deleted = LogRetention.plan(List.of(old, recent), limits(1_000_000L, 7, 20), now, Set.of());
        assertEquals(List.of(old.path()), deleted);
    }

    @Test
    void keepsTheConfiguredSessionsPerOwner() {
        long now = 10 * DAY;
        List<LogFileInfo> files = new ArrayList<>();
        List<LogFileInfo> ownerA = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            LogFileInfo info = file("a" + i + ".jsonl", 10L, now - (10 - i) * 1000L, OWNER, LogFileKind.SESSION);
            files.add(info);
            ownerA.add(info);
        }
        LogFileInfo other = file("b.jsonl", 10L, now - 1000L, OTHER, LogFileKind.SESSION);
        files.add(other);
        List<Path> deleted = LogRetention.plan(files, limits(1_000_000L, 30, 2), now, Set.of());
        assertEquals(List.of(ownerA.get(0).path(), ownerA.get(1).path()), deleted);
        assertTrue(!deleted.contains(other.path()));
    }

    @Test
    void neverDeletesAnActiveFile() {
        long now = 1_000_000_000L;
        List<LogFileInfo> files = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            files.add(file("f" + i + ".jsonl", 1_000_000L, now - (4 - i) * 1000L, null, LogFileKind.BOOT));
        }
        List<Path> deleted = LogRetention.plan(
                files, limits(0L, 7, 20), now, Set.of(files.get(0).path()));
        assertEquals(
                List.of(files.get(1).path(), files.get(2).path(), files.get(3).path()), deleted);
    }

    @Test
    void keepsAFileExactlyAtTheCap() {
        long now = 1_000_000_000L;
        List<LogFileInfo> files = List.of(
                file("a.jsonl", 1_000_000L, now - 3000L, null, LogFileKind.BOOT),
                file("b.jsonl", 1_000_000L, now - 2000L, null, LogFileKind.BOOT),
                file("c.jsonl", 1_000_000L, now - 1000L, null, LogFileKind.BOOT));
        assertEquals(List.of(), LogRetention.plan(files, limits(3_000_000L, 7, 20), now, Set.of()));
    }

    @Test
    void tiesBreakByName() {
        long now = 1_000_000_000L;
        LogFileInfo b = file("b.jsonl", 1_000_000L, now, null, LogFileKind.BOOT);
        LogFileInfo a = file("a.jsonl", 1_000_000L, now, null, LogFileKind.BOOT);
        List<Path> deleted = LogRetention.plan(List.of(b, a), limits(1_000_000L, 7, 20), now, Set.of());
        assertEquals(List.of(a.path()), deleted);
    }

    @Test
    void emptyInputReturnsEmpty() {
        assertEquals(List.of(), LogRetention.plan(List.of(), limits(1, 1, 1), 1L, Set.of()));
    }
}
