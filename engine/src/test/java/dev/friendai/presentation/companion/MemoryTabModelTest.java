package dev.friendai.presentation.companion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.features.memory.Category;
import dev.friendai.features.memory.MemoryLimits;
import dev.friendai.features.memory.MemoryView;
import dev.friendai.features.memory.MenuStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MemoryTabModelTest {
    private static final UUID O = UUID.fromString("00000000-0000-0000-0000-0000000000e0");
    private static final UUID B = UUID.fromString("00000000-0000-0000-0000-0000000000b1");

    private static MemoryView view(
            List<MemoryView.Group> groups, List<MemoryView.FactRow> facts, Optional<MenuStatus> status) {
        return new MemoryView(true, MemoryLimits.DEFAULTS, groups, facts, status);
    }

    private static MemoryView.Group owner() {
        return new MemoryView.Group(O.toString(), "Steve", MemoryView.Role.OWNER, 1, true);
    }

    private static MemoryView.Group world() {
        return new MemoryView.Group("world", "", MemoryView.Role.WORLD, 0, false);
    }

    private static MemoryView.Group bob() {
        return new MemoryView.Group(B.toString(), "Bob", MemoryView.Role.TRUSTED, 1, true);
    }

    private static MemoryView.FactRow fact(int id, String groupKey, String text) {
        return new MemoryView.FactRow(id, groupKey, Category.PLACE, text, "");
    }

    private static Optional<MenuStatus> status(String key) {
        return Optional.of(MenuStatus.ok(key));
    }

    @Test
    void selectingPersonaHidesGroupAndFacts() {
        MemoryTabModel model = new MemoryTabModel();
        model.onView(view(List.of(owner(), world()), List.of(fact(1, O.toString(), "Base")), Optional.empty()));
        model.selectPersona();
        assertTrue(model.personaSelected());
        assertTrue(model.group().isEmpty());
        assertTrue(model.facts().isEmpty());
    }

    @Test
    void selectingAGroupLeavesPersona() {
        MemoryTabModel model = new MemoryTabModel();
        model.onView(view(List.of(owner(), world()), List.of(), Optional.empty()));
        model.selectPersona();
        model.selectGroup(O.toString());
        assertFalse(model.personaSelected());
        assertEquals(O.toString(), model.group().orElseThrow().key());
        model.selectPersona();
        model.selectGroup("world");
        assertFalse(model.personaSelected());
        assertEquals("world", model.group().orElseThrow().key());
    }

    @Test
    void cyclingLeavesPersona() {
        MemoryTabModel model = new MemoryTabModel();
        model.onView(view(List.of(owner(), world()), List.of(), Optional.empty()));
        model.selectPersona();
        model.next();
        assertFalse(model.personaSelected());
        model.selectPersona();
        model.previous();
        assertFalse(model.personaSelected());
    }

    @Test
    void personaSelectionSurvivesANewView() {
        MemoryTabModel model = new MemoryTabModel();
        model.onView(view(List.of(owner(), world()), List.of(), Optional.empty()));
        model.selectPersona();
        model.onView(view(List.of(owner(), world()), List.of(), status("friendai.menu.description.saved")));
        assertTrue(model.personaSelected());
        assertTrue(model.status().isPresent());
    }

    @Test
    void selectingPersonaCancelsAFactEditAndClearsTheStatusLine() {
        MemoryTabModel model = new MemoryTabModel();
        model.onView(view(
                List.of(owner()), List.of(fact(1, O.toString(), "Base")), status("friendai.menu.memory.status.saved")));
        model.select(1);
        assertTrue(model.startEdit());
        model.selectPersona();
        assertTrue(model.editing().isEmpty());
        assertTrue(model.selected().isEmpty());
        assertTrue(model.status().isEmpty());
    }

    @Test
    void firstViewSelectsOwnerGroup() {
        MemoryTabModel model = new MemoryTabModel();
        model.onView(view(List.of(owner(), world()), List.of(), Optional.empty()));
        assertEquals(O.toString(), model.group().orElseThrow().key());
    }

    @Test
    void cyclingWrapsAround() {
        MemoryTabModel model = new MemoryTabModel();
        model.onView(view(List.of(owner(), world()), List.of(), Optional.empty()));
        model.next();
        assertEquals("world", model.group().orElseThrow().key());
        model.next();
        assertEquals(O.toString(), model.group().orElseThrow().key());
        model.previous();
        assertEquals("world", model.group().orElseThrow().key());
    }

    @Test
    void missingGroupFallsBackToOwner() {
        MemoryTabModel model = new MemoryTabModel();
        model.onView(view(List.of(owner(), world(), bob()), List.of(), Optional.empty()));
        model.next();
        model.next();
        assertEquals(B.toString(), model.group().orElseThrow().key());
        model.onView(view(List.of(owner(), world()), List.of(), Optional.empty()));
        assertEquals(O.toString(), model.group().orElseThrow().key());
    }

    @Test
    void selectionClearedWhenFactGone() {
        MemoryTabModel model = new MemoryTabModel();
        model.onView(view(List.of(owner()), List.of(fact(1, O.toString(), "Base")), Optional.empty()));
        model.select(1);
        assertTrue(model.selected().isPresent());
        model.onView(view(List.of(owner()), List.of(), Optional.empty()));
        assertTrue(model.selected().isEmpty());
    }

    @Test
    void editedFactDisappearingCancelsEdit() {
        MemoryTabModel model = new MemoryTabModel();
        model.onView(view(List.of(owner()), List.of(fact(1, O.toString(), "Base")), Optional.empty()));
        model.select(1);
        assertTrue(model.startEdit());
        assertEquals("Base", model.draftText());
        model.onView(view(List.of(owner()), List.of(), Optional.empty()));
        assertTrue(model.editing().isEmpty());
        assertEquals("", model.draftText());
    }

    @Test
    void startEditCopiesFact() {
        MemoryTabModel model = new MemoryTabModel();
        model.onView(view(List.of(owner()), List.of(fact(1, O.toString(), "Base")), Optional.empty()));
        model.select(1);
        assertTrue(model.startEdit());
        assertEquals(1, model.editing().orElseThrow());
        assertEquals(Category.PLACE, model.draftCategory());
        assertEquals("Base", model.draftText());
    }

    @Test
    void startEditWithoutSelectionReturnsFalse() {
        MemoryTabModel model = new MemoryTabModel();
        model.onView(view(List.of(owner()), List.of(fact(1, O.toString(), "Base")), Optional.empty()));
        assertFalse(model.startEdit());
        assertTrue(model.editing().isEmpty());
    }

    @Test
    void factsAreOnlyTheCurrentGroups() {
        MemoryTabModel model = new MemoryTabModel();
        model.onView(view(
                List.of(owner(), world()),
                List.of(fact(1, O.toString(), "own"), fact(2, "world", "shared")),
                Optional.empty()));
        assertEquals(
                List.of(1), model.facts().stream().map(MemoryView.FactRow::id).toList());
        model.next();
        assertEquals(
                List.of(2), model.facts().stream().map(MemoryView.FactRow::id).toList());
    }

    @Test
    void clearNeedsSecondClickWithin3s() {
        MemoryTabModel model = new MemoryTabModel();
        model.onView(view(List.of(owner()), List.of(), Optional.empty()));
        assertFalse(model.clickClear(0));
        assertTrue(model.clearArmed(2999));
        assertTrue(model.clickClear(2999));
        assertFalse(model.clearArmed(3000));

        MemoryTabModel slow = new MemoryTabModel();
        slow.onView(view(List.of(owner()), List.of(), Optional.empty()));
        assertFalse(slow.clickClear(0));
        assertFalse(slow.clickClear(3001));
    }

    @Test
    void clearConfirmResetsOnGroupChange() {
        MemoryTabModel model = new MemoryTabModel();
        model.onView(view(List.of(owner(), world()), List.of(), Optional.empty()));
        assertFalse(model.clickClear(0));
        model.next();
        assertFalse(model.clickClear(100));
    }

    @Test
    void statusHiddenAfterGroupChangeAndShownOnNewStatus() {
        MemoryTabModel model = new MemoryTabModel();
        model.onView(view(List.of(owner(), world()), List.of(), status("friendai.menu.memory.status.cleared")));
        assertTrue(model.statusVisible());
        model.next();
        assertFalse(model.statusVisible());
        model.onView(view(List.of(owner(), world()), List.of(), status("friendai.menu.memory.status.added")));
        assertTrue(model.statusVisible());
    }

    @Test
    void statusSurvivesAStatusFreeRefresh() {
        MemoryTabModel model = new MemoryTabModel();
        model.onView(view(List.of(owner()), List.of(), status("friendai.menu.memory.status.added")));
        model.onView(view(List.of(owner()), List.of(), Optional.empty()));
        assertTrue(model.statusVisible());
        assertEquals(
                "friendai.menu.memory.status.added",
                model.status().orElseThrow().message().key());
    }

    @Test
    void hiddenStatusIsNotRendered() {
        MemoryTabModel model = new MemoryTabModel();
        model.onView(view(List.of(owner(), world()), List.of(), status("friendai.menu.memory.status.added")));
        model.next();
        assertTrue(model.status().isEmpty());
    }

    @Test
    void clearArmedExpiresWithoutClicking() {
        MemoryTabModel model = new MemoryTabModel();
        model.onView(view(List.of(owner()), List.of(), Optional.empty()));
        assertFalse(model.clickClear(0));
        assertFalse(model.clearArmed(3001));
        assertFalse(model.clickClear(3001));
    }

    @Test
    void selectGroupSwitchesAndDropsSelection() {
        MemoryTabModel model = new MemoryTabModel();
        model.onView(view(List.of(owner(), bob()), List.of(fact(1, O.toString(), "a")), Optional.empty()));
        model.select(1);
        model.selectGroup(B.toString());
        assertEquals(B.toString(), model.group().orElseThrow().key());
        assertTrue(model.selected().isEmpty());
        model.selectGroup("nobody");
        assertEquals(B.toString(), model.group().orElseThrow().key());
    }

    private static MemoryView.TranscriptRow line(long id) {
        return new MemoryView.TranscriptRow(id, O.toString(), "Steve", false, "hi " + id, 1_000);
    }

    @Test
    void selectingTheRecentChatPaneHidesGroupAndFactsAndExposesTheLines() {
        MemoryTabModel model = new MemoryTabModel();
        model.onView(new MemoryView(
                true,
                MemoryLimits.DEFAULTS,
                List.of(owner(), world()),
                List.of(fact(1, O.toString(), "Base")),
                Optional.empty(),
                new MemoryView.PersonaView("", false),
                List.of(line(2), line(1))));
        model.selectTranscript();
        assertTrue(model.transcriptSelected());
        assertTrue(model.group().isEmpty());
        assertTrue(model.facts().isEmpty());
        assertEquals(2, model.transcript().size());

        model.selectGroup(O.toString());
        assertFalse(model.transcriptSelected());
        assertEquals(1, model.facts().size());
    }

    @Test
    void clearingTheTranscriptNeedsASecondClickInsideTheWindow() {
        MemoryTabModel model = new MemoryTabModel();
        model.onView(view(List.of(owner()), List.of(), Optional.empty()));
        assertFalse(model.clickClearTranscript(0), "nothing to clear while the pane is not shown");
        model.selectTranscript();
        assertFalse(model.clickClearTranscript(1_000));
        assertTrue(model.clearTranscriptArmed(2_000));
        assertTrue(model.clickClearTranscript(2_000));
        assertFalse(model.clearTranscriptArmed(2_000));
        assertFalse(model.clickClearTranscript(10_000));
        assertFalse(model.clickClearTranscript(10_000 + MemoryTabModel.CLEAR_CONFIRM_MILLIS + 1));
    }
}
