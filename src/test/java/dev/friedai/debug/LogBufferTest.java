package dev.friedai.debug;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.EnumSet;
import java.util.List;
import org.junit.jupiter.api.Test;

class LogBufferTest {
    private static LogEntry e(long id, LogKind kind) {
        return new LogEntry(id, id, kind, "t" + id);
    }

    @Test
    void skipsAlreadySeenIds() {
        LogBuffer buffer = new LogBuffer(10);
        buffer.append(List.of(e(1, LogKind.USER), e(2, LogKind.USER)));
        buffer.append(List.of(e(2, LogKind.USER), e(3, LogKind.USER)));
        assertEquals(List.of(1L, 2L, 3L), buffer.view(EnumSet.allOf(LogKind.class)).stream().map(LogEntry::id).toList());
    }

    @Test
    void evictsOldest() {
        LogBuffer buffer = new LogBuffer(2);
        buffer.append(List.of(e(1, LogKind.USER), e(2, LogKind.USER), e(3, LogKind.USER)));
        assertEquals(List.of(2L, 3L), buffer.view(EnumSet.allOf(LogKind.class)).stream().map(LogEntry::id).toList());
    }

    @Test
    void viewFiltersByKind() {
        LogBuffer buffer = new LogBuffer(10);
        buffer.append(List.of(e(1, LogKind.USER), e(2, LogKind.ERROR), e(3, LogKind.USER)));
        assertEquals(List.of(2L), buffer.view(EnumSet.of(LogKind.ERROR)).stream().map(LogEntry::id).toList());
    }

    @Test
    void clearForgetsSeenIds() {
        LogBuffer buffer = new LogBuffer(10);
        buffer.append(List.of(e(5, LogKind.USER)));
        buffer.clear();
        buffer.append(List.of(e(1, LogKind.USER)));
        assertEquals(1, buffer.view(EnumSet.allOf(LogKind.class)).size());
    }
}
