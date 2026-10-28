package dev.friendai.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.execution.tool.AccessLevel;
import dev.friendai.execution.tool.Caller;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MemoryPromptRendererTest {
    private static final UUID O = UUID.fromString("00000000-0000-0000-0000-0000000000e1");
    private static final UUID T = UUID.fromString("00000000-0000-0000-0000-0000000000e2");
    private static final UUID G = UUID.fromString("00000000-0000-0000-0000-0000000000e3");
    private static final UUID X = UUID.fromString("00000000-0000-0000-0000-0000000000e4");
    private static final MemoryLimits L = MemoryLimits.DEFAULTS;

    private final MemoryBook book = new MemoryBook();
    private final Caller ownerCaller = new Caller(O, "Steve", AccessLevel.OWNER);
    private final Caller trustedCaller = new Caller(T, "Tia", AccessLevel.TRUSTED);
    private final Caller guestCaller = new Caller(G, "Gus", AccessLevel.GUEST);

    @BeforeEach
    void setUp() {
        book.touch(O, "Steve", 1);
        book.setTrusted(T, "Tia", true, 2);
        book.touch(G, "Gus", 3);
        book.touch(X, "Alex", 4);
        book.addFact(Subject.player(O), Category.PREFERENCE, "Do not move items between chests.", O, "Steve", 1, L);
        book.addFact(Subject.WORLD, Category.PLACE, "Main base at 120 64 -30, overworld.", O, "Steve", 1, L);
        book.addFact(Subject.player(T), Category.NOTE, "Likes caves.", T, "Tia", 1, L);
        book.addFact(Subject.player(G), Category.PROFILE, "Gus likes cats.", G, "Gus", 1, L);
        book.addFact(Subject.player(X), Category.PROFILE, "Alex builds.", X, "Alex", 1, L);
        book.addFact(Subject.player(X), Category.PLACE, "Alex mines at 5 6 7.", X, "Alex", 1, L);
    }

    @Test
    void ownerSeesOwnerWorldAndIndex() {
        String block = MemoryPromptRenderer.render(book, O, ownerCaller);
        assertTrue(block.contains("About Steve (owner):"));
        assertTrue(block.contains("World:"));
        assertTrue(block.contains("Also known: Alex (2 facts), Gus (1 fact), Tia (1 fact); use recall."));
        assertFalse(block.contains("About Gus"));
    }

    @Test
    void trustedSeesOwnerWorldAndSelf() {
        String block = MemoryPromptRenderer.render(book, O, trustedCaller);
        assertTrue(block.contains("About Steve (owner):"));
        assertTrue(block.contains("World:"));
        assertTrue(block.contains("About Tia (trusted):"));
        assertFalse(block.contains("Alex"));
        assertFalse(block.contains("Gus"));
    }

    @Test
    void guestSeesOnlySelf() {
        String block = MemoryPromptRenderer.render(book, O, guestCaller);
        assertTrue(block.contains("About Gus (guest):"));
        assertFalse(block.contains("Steve"));
        assertFalse(block.contains("World:"));
        assertFalse(block.contains("Alex"));
    }

    @Test
    void foreignAuthorIsMarked() {
        String block = MemoryPromptRenderer.render(book, O, trustedCaller);
        String tiaLine = block.lines()
                .filter(line -> line.contains("Likes caves"))
                .findFirst()
                .orElseThrow();
        assertTrue(tiaLine.endsWith("(from Tia)"), tiaLine);
        assertFalse(block.contains("(from Steve)"));
    }

    @Test
    void usesCurrentNameOfSubject() {
        book.touch(G, "Gustav", 9);
        assertTrue(MemoryPromptRenderer.render(book, O, guestCaller).contains("About Gustav (guest):"));
    }

    @Test
    void emptyBook() {
        assertEquals("Memory: nothing remembered yet.", MemoryPromptRenderer.render(new MemoryBook(), O, ownerCaller));
    }
}
