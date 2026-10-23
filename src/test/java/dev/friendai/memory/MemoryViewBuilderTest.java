package dev.friendai.memory;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MemoryViewBuilderTest {
    private static final UUID O = UUID.fromString("00000000-0000-0000-0000-0000000000e0");
    private static final UUID AMY = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID BOB = UUID.fromString("00000000-0000-0000-0000-0000000000b1");
    private static final UUID ZED = UUID.fromString("00000000-0000-0000-0000-0000000000d1");
    private static final MemoryLimits L = MemoryLimits.DEFAULTS;

    private final MemoryBook book = new MemoryBook();

    private MemoryView build(List<PlayerRef> online, boolean enabled, Optional<MenuStatus> status) {
        return MemoryViewBuilder.build(book, O, "Steve", online, enabled, L, status);
    }

    private static List<String> keys(MemoryView view) {
        return view.groups().stream().map(MemoryView.Group::key).toList();
    }

    private static MemoryView.Group group(MemoryView view, UUID uuid) {
        return view.groups().stream()
                .filter(g -> g.key().equals(uuid.toString()))
                .findFirst()
                .orElseThrow();
    }

    @Test
    void groupOrderIsOwnerWorldThenByName() {
        book.ensurePlayer(ZED, "zed");
        book.addFact(Subject.player(ZED), Category.NOTE, "z", O, "Steve", 0, L);
        book.setTrusted(AMY, "Amy", true, 1);
        MemoryView view = build(List.of(new PlayerRef(BOB, "Bob")), true, Optional.empty());
        assertEquals(
                List.of(O.toString(), "world", AMY.toString(), BOB.toString(), ZED.toString()), keys(view));
        assertEquals(List.of("Steve", "", "Amy", "Bob", "zed"), view.groups().stream().map(MemoryView.Group::name).toList());
    }

    @Test
    void ownerListedOnceEvenIfOnline() {
        book.setTrusted(AMY, "Amy", true, 1);
        MemoryView view = build(List.of(new PlayerRef(O, "Steve"), new PlayerRef(AMY, "Amy")), true, Optional.empty());
        assertEquals(1, view.groups().stream().filter(g -> g.role() == MemoryView.Role.OWNER).count());
        assertEquals(keys(view), view.groups().stream().map(MemoryView.Group::key).distinct().toList());
        assertTrue(group(view, O).online());
        assertEquals("Steve", group(view, O).name());
    }

    @Test
    void rolesAndCounts() {
        book.ensurePlayer(ZED, "zed");
        book.setTrusted(AMY, "Amy", true, 1);
        book.addFact(Subject.player(AMY), Category.NOTE, "a1", O, "Steve", 0, L);
        book.addFact(Subject.player(AMY), Category.NOTE, "a2", O, "Steve", 0, L);
        book.addFact(Subject.player(ZED), Category.NOTE, "z", O, "Steve", 0, L);
        book.addFact(Subject.WORLD, Category.NOTE, "w1", O, "Steve", 0, L);
        book.addFact(Subject.WORLD, Category.NOTE, "w2", O, "Steve", 0, L);
        MemoryView view = build(List.of(), true, Optional.empty());
        assertEquals(MemoryView.Role.OWNER, group(view, O).role());
        assertEquals(MemoryView.Role.TRUSTED, group(view, AMY).role());
        assertEquals(MemoryView.Role.GUEST, group(view, ZED).role());
        assertEquals(2, group(view, AMY).count());
        assertEquals(1, group(view, ZED).count());
        assertEquals(2, view.groups().get(1).count());
        assertFalse(group(view, ZED).online());
    }

    @Test
    void authorNameOnlyForForeignAuthors() {
        book.addFact(Subject.player(ZED), Category.NOTE, "z", O, "Steve", 0, L);
        book.addFact(Subject.player(O), Category.PROFILE, "p", ZED, "zed", 0, L);
        MemoryView view = build(List.of(), true, Optional.empty());
        MemoryView.FactRow own = view.facts().stream().filter(f -> f.text().equals("z")).findFirst().orElseThrow();
        MemoryView.FactRow foreign =
                view.facts().stream().filter(f -> f.text().equals("p")).findFirst().orElseThrow();
        assertEquals("", own.authorName());
        assertEquals("zed", foreign.authorName());
        assertEquals(List.of(1, 2), view.facts().stream().map(MemoryView.FactRow::id).toList());
    }

    @Test
    void disabledViewHasGroupsButNoFacts() {
        book.setTrusted(AMY, "Amy", true, 1);
        book.addFact(Subject.WORLD, Category.NOTE, "w", O, "Steve", 0, L);
        MemoryView view = build(List.of(), false, Optional.empty());
        assertFalse(view.enabled());
        assertTrue(view.groups().size() >= 3);
        assertTrue(view.facts().isEmpty());
    }

    @Test
    void statusIsCarried() {
        MenuStatus status = MenuStatus.ok("friendai.menu.memory.status.cleared", 3);
        assertEquals(Optional.of(status), build(List.of(), true, Optional.of(status)).status());
    }

    @Test
    void trustedPlayerWithoutFactsIsListed() {
        book.setTrusted(AMY, "Amy", true, 1);
        MemoryView view = build(List.of(), true, Optional.empty());
        assertEquals(0, group(view, AMY).count());
        assertEquals(MemoryView.Role.TRUSTED, group(view, AMY).role());
    }

    @Test
    void limitsAreCarried() {
        MemoryLimits limits = new MemoryLimits(100, 5, 7);
        MemoryView view = MemoryViewBuilder.build(book, O, "Steve", List.of(), true, limits, Optional.empty());
        assertEquals(limits, view.limits());
    }

    @Test
    void factRowsCarryTheGroupKeyAndCategory() {
        book.addFact(Subject.WORLD, Category.PLACE, "Base", O, "Steve", 0, L);
        MemoryView view = build(List.of(), true, Optional.empty());
        assertEquals(new MemoryView.FactRow(1, "world", Category.PLACE, "Base", ""), view.facts().get(0));
    }

    @Test
    void statusIsNotInventedWhenMissing() {
        assertTrue(build(List.of(), true, Optional.empty()).status().isEmpty());
    }

    @Test
    void factsAreCappedAtTheCodecLimit() {
        for (int i = 1; i <= MemoryViewCodec.MAX_FACTS + 1; i++) {
            book.addFact(Subject.WORLD, Category.NOTE, "fact " + i, O, "Steve", 0, new MemoryLimits(160, 5000, 5000));
        }
        MemoryView view = MemoryViewBuilder.build(
                book, O, "Steve", List.of(), true, new MemoryLimits(160, 5000, 5000), Optional.empty());
        assertEquals(MemoryViewCodec.MAX_FACTS, view.facts().size());
        assertEquals(1, view.facts().get(0).id());
        assertDoesNotThrow(() -> MemoryViewCodec.decode(MemoryViewCodec.encode(view)));
    }
}
