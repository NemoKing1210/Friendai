package dev.friendai.agent.turn;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.execution.tool.AccessLevel;
import dev.friendai.execution.tool.Caller;
import dev.friendai.foundation.diagnostics.DebugLog;
import dev.friendai.foundation.diagnostics.LogKind;
import java.util.Set;
import java.util.UUID;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

class LogActionsTest {
    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
    private static final Caller OWNER_CALLER = new Caller(OWNER, "Alex", AccessLevel.OWNER);

    private static DebugLog log() {
        return new DebugLog(10, 100, UnaryOperator.identity(), () -> 1_000L);
    }

    @Test
    void onlyTheOwnerReadsTheLog() {
        DebugLog log = log();
        log.add(LogKind.USER, "secret");
        assertThrows(
                IllegalArgumentException.class,
                () -> LogActions.read(
                        new Caller(OWNER, "Alex", AccessLevel.TRUSTED),
                        OWNER,
                        log,
                        Set.of(),
                        0,
                        0,
                        10,
                        true,
                        6000,
                        UnaryOperator.identity()));
        assertThrows(
                IllegalArgumentException.class,
                () -> LogActions.read(
                        new Caller(UUID.randomUUID(), "Gus", AccessLevel.GUEST),
                        OWNER,
                        log,
                        Set.of(),
                        0,
                        0,
                        10,
                        true,
                        6000,
                        UnaryOperator.identity()));
        assertThrows(
                IllegalArgumentException.class,
                () -> LogActions.read(
                        new Caller(UUID.randomUUID(), "Other", AccessLevel.OWNER),
                        OWNER,
                        log,
                        Set.of(),
                        0,
                        0,
                        10,
                        true,
                        6000,
                        UnaryOperator.identity()));
    }

    @Test
    void newestFirstCarriesIdsAndABackwardsContinuation() {
        DebugLog log = log();
        log.add(LogKind.USER, "one");
        log.add(LogKind.ASSISTANT, "two");
        log.add(LogKind.TOOL_CALL, "three");
        String text =
                LogActions.read(OWNER_CALLER, OWNER, log, Set.of(), 0, 0, 2, true, 6000, UnaryOperator.identity());
        assertTrue(text.contains("[3] TOOL_CALL: three"), text);
        assertTrue(text.contains("[2] ASSISTANT: two"), text);
        assertFalse(text.contains("[1] USER"), text);
        assertTrue(text.contains("newest first"), text);
        assertTrue(text.contains("truncated: true"), text);
        assertTrue(text.contains("continuation: read_log before=2"), text);
    }

    @Test
    void oldestFirstContinuesForwards() {
        DebugLog log = log();
        log.add(LogKind.USER, "one");
        log.add(LogKind.ASSISTANT, "two");
        log.add(LogKind.TOOL_CALL, "three");
        String text =
                LogActions.read(OWNER_CALLER, OWNER, log, Set.of(), 0, 0, 2, false, 6000, UnaryOperator.identity());
        assertTrue(text.contains("[1] USER: one"), text);
        assertTrue(text.contains("[2] ASSISTANT: two"), text);
        assertFalse(text.contains("three"), text);
        assertTrue(text.contains("continuation: read_log since=2"), text);
    }

    @Test
    void kindFilterAndEmptyAnswerNameTheBounds() {
        DebugLog log = log();
        log.add(LogKind.USER, "one");
        log.add(LogKind.ERROR, "boom");
        String filtered = LogActions.read(
                OWNER_CALLER, OWNER, log, Set.of(LogKind.ERROR), 0, 0, 10, true, 6000, UnaryOperator.identity());
        assertTrue(filtered.contains("[2] ERROR: boom"), filtered);
        assertFalse(filtered.contains("one"), filtered);

        String empty = LogActions.read(
                OWNER_CALLER, OWNER, log, Set.of(LogKind.TASK), 5, 0, 10, true, 6000, UnaryOperator.identity());
        assertTrue(empty.contains("No log entries match"), empty);
        assertTrue(empty.contains("after id 5"), empty);
        assertTrue(empty.contains("last id 2"), empty);
    }

    @Test
    void redactionRunsAgainOnRead() {
        DebugLog log = log();
        log.add(LogKind.ERROR, "key=SECRET");
        String text = LogActions.read(
                OWNER_CALLER, OWNER, log, Set.of(), 0, 0, 10, true, 6000, value -> value.replace("SECRET", "***"));
        assertTrue(text.contains("key=***"), text);
        assertFalse(text.contains("SECRET"), text);
    }

    @Test
    void aTightBudgetKeepsWholeLinesAndSaysSo() {
        DebugLog log = log();
        for (int i = 1; i <= 5; i++) {
            log.add(LogKind.USER, "message " + i);
        }
        String text = LogActions.read(OWNER_CALLER, OWNER, log, Set.of(), 0, 0, 5, false, 40, UnaryOperator.identity());
        assertTrue(text.contains("truncated: true"), text);
        assertTrue(text.contains("continuation: read_log since="), text);
        assertTrue(text.lines().anyMatch(line -> line.startsWith("[1] USER: message 1")), text);
        assertFalse(text.contains("message 5"), text);
    }
}
