package dev.friendai.presentation.companion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.features.memory.MenuStatus;
import dev.friendai.features.places.PlaceView;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class PlacesTabModelTest {
    private static PlaceView.Row row(long id, String name) {
        return new PlaceView.Row(
                id,
                name,
                "friendai.menu.place.kind.house",
                "",
                "minecraft:overworld",
                10,
                64,
                -4,
                8,
                0,
                0L,
                "friendai.menu.place.source.manual",
                "");
    }

    private static PlaceView viewWith(String... names) {
        List<PlaceView.Row> rows = new ArrayList<>();
        for (int i = 0; i < names.length; i++) {
            rows.add(row(i + 1, names[i]));
        }
        return new PlaceView(true, List.copyOf(rows), Optional.empty());
    }

    @Test
    void deleteNeedsAConfirmationWithinThreeSeconds() {
        PlacesTabModel model = new PlacesTabModel();
        model.onView(viewWith("home"));
        model.select(1);
        assertFalse(model.requestDelete(1, 0L));
        assertEquals(1, model.pendingDelete());
        assertEquals(1, model.rows().size());
        model.tick(3_001L);
        assertEquals(0, model.pendingDelete());
    }

    @Test
    void aSecondClickWithinTheWindowConfirms() {
        PlacesTabModel model = new PlacesTabModel();
        model.onView(viewWith("home"));
        assertFalse(model.requestDelete(1, 1_000L));
        assertTrue(model.requestDelete(1, 3_500L));
        assertEquals(0, model.pendingDelete());
    }

    @Test
    void aNewViewDropsStateForRemovedPlaces() {
        PlacesTabModel model = new PlacesTabModel();
        model.onView(viewWith("home", "mine"));
        model.select(2);
        model.setRenameDraft("shaft");
        model.requestDelete(2, 0L);
        model.onView(viewWith("home"));
        assertEquals(-1, model.selected());
        assertEquals("", model.renameDraft());
        assertEquals(0, model.pendingDelete());
    }

    @Test
    void selectionAndDraftSurviveWhileThePlaceRemains() {
        PlacesTabModel model = new PlacesTabModel();
        model.onView(viewWith("home"));
        model.select(1);
        model.setRenameDraft("base");
        model.onView(viewWith("home"));
        assertEquals(1, model.selected());
        assertEquals("base", model.renameDraft());
    }

    @Test
    void selectingLoadsThePlaceNameAndClearsAPendingDelete() {
        PlacesTabModel model = new PlacesTabModel();
        model.onView(viewWith("home", "mine"));
        model.select(1);
        assertEquals("home", model.renameDraft());
        model.requestDelete(1, 0L);
        model.select(2);
        assertEquals(2, model.selected());
        assertEquals("mine", model.renameDraft());
        assertEquals(0, model.pendingDelete());
    }

    @Test
    void statusKeepsTheLastMessageAcrossRefreshesWithoutOne() {
        PlacesTabModel model = new PlacesTabModel();
        model.onView(new PlaceView(
                true, List.of(row(1, "home")), Optional.of(MenuStatus.ok("friendai.menu.places.status.renamed"))));
        assertEquals(
                "friendai.menu.places.status.renamed",
                model.status().orElseThrow().message().key());
        model.onView(viewWith("home"));
        assertTrue(model.status().isPresent());
    }
}
