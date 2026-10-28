package dev.friendai.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.text.UserMessage;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MemoryReportTest {
    private static final UUID O = UUID.fromString("00000000-0000-0000-0000-0000000000d1");
    private static final UUID B = UUID.fromString("00000000-0000-0000-0000-0000000000d2");
    private static final MemoryLimits L = MemoryLimits.DEFAULTS;

    private final MemoryBook book = new MemoryBook();

    @BeforeEach
    void setUp() {
        book.touch(O, "Steve", 1);
        book.touch(B, "Bob", 2);
        book.addFact(Subject.player(O), Category.PREFERENCE, "Keep chests tidy", O, "Steve", 1, L);
        book.addFact(Subject.WORLD, Category.PLACE, "Base at 1 2 3", O, "Steve", 1, L);
        book.addFact(Subject.player(B), Category.PROFILE, "Builds farms", B, "Bob", 1, L);
    }

    @Test
    void listAllGroupsOwnerWorldOthers() {
        assertEquals(
                List.of(
                        UserMessage.of("friendai.command.memory.list.player", "Steve", 1),
                        UserMessage.of("friendai.command.memory.list.entry", 1, "PREFERENCE", "Keep chests tidy"),
                        UserMessage.of("friendai.command.memory.list.world", 1),
                        UserMessage.of("friendai.command.memory.list.entry", 2, "PLACE", "Base at 1 2 3"),
                        UserMessage.of("friendai.command.memory.list.player", "Bob", 1),
                        UserMessage.of("friendai.command.memory.list.entry", 3, "PROFILE", "Builds farms")),
                MemoryReport.list(book, O, null));
    }

    @Test
    void listUnknownPlayer() {
        assertEquals(
                List.of(UserMessage.of("friendai.command.memory.unknown_player", "Zed")),
                MemoryReport.list(book, O, "Zed"));
    }

    @Test
    void listEmptyBook() {
        assertEquals(
                List.of(UserMessage.of("friendai.command.memory.list.empty")),
                MemoryReport.list(new MemoryBook(), O, null));
    }

    @Test
    void listWorldOnly() {
        assertEquals(
                List.of(
                        UserMessage.of("friendai.command.memory.list.world", 1),
                        UserMessage.of("friendai.command.memory.list.entry", 2, "PLACE", "Base at 1 2 3")),
                MemoryReport.list(book, O, "world"));
    }

    @Test
    void clearWorldCountsAndKeepsPlayers() {
        assertEquals(UserMessage.of("friendai.command.memory.cleared", 1), MemoryReport.clear(book, "world"));
        assertTrue(book.facts(Subject.WORLD).isEmpty());
        assertEquals(2, book.allFacts().size());
    }

    @Test
    void clearAllEmptiesBook() {
        assertEquals(UserMessage.of("friendai.command.memory.cleared", 3), MemoryReport.clear(book, "all"));
        assertTrue(book.allFacts().isEmpty());
    }

    @Test
    void clearUnknownPlayer() {
        assertEquals(UserMessage.of("friendai.command.memory.unknown_player", "Zed"), MemoryReport.clear(book, "Zed"));
    }

    @Test
    void forgetExistingAndMissingId() {
        assertEquals(UserMessage.of("friendai.command.memory.forgot", 2), MemoryReport.forget(book, 2));
        assertTrue(book.fact(2).isEmpty());
        assertEquals(UserMessage.of("friendai.command.memory.no_fact", 9), MemoryReport.forget(book, 9));
    }
}
