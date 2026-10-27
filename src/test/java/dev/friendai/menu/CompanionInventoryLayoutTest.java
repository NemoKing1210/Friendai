package dev.friendai.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class CompanionInventoryLayoutTest {

    private static final int MIN_GAP = 2;

    private record Band(String name, int top, int bottom) {}

    /** A grid of slot rows: its frames start one pixel above and left of the content, and end with the last row. */
    private static Band slotRows(String name, int first, int rows) {
        return new Band(name,
                first - 1,
                first + (rows - 1) * CompanionInventoryLayout.PITCH - 1 + CompanionInventoryLayout.SLOT);
    }

    @Test
    void bandsDoNotOverlapAndKeepTheirSeparation() {
        List<Band> bands = List.of(
                slotRows("gear", CompanionInventoryLayout.GEAR_Y, 4),
                new Band("vitals", CompanionInventoryLayout.VITALS_Y,
                        CompanionInventoryLayout.VITALS_Y + CompanionInventoryLayout.ICON),
                new Band("experience", CompanionInventoryLayout.XP_Y,
                        CompanionInventoryLayout.XP_Y + CompanionInventoryLayout.XP_HEIGHT),
                slotRows("companion grid", CompanionInventoryLayout.ITEMS_Y, 3),
                new Band("label", CompanionInventoryLayout.LABEL_Y, CompanionInventoryLayout.LABEL_Y + 9),
                slotRows("player grid", CompanionInventoryLayout.PLAYER_Y, 3),
                slotRows("hotbar", CompanionInventoryLayout.HOTBAR_Y, 1));

        assertTrue(bands.get(0).top() >= 3, "the gear block starts inside the panel bevel");
        for (int i = 1; i < bands.size(); i++) {
            Band previous = bands.get(i - 1);
            Band band = bands.get(i);
            assertTrue(band.top() - previous.bottom() >= MIN_GAP,
                    band.name() + " starts at " + band.top() + " only " + (band.top() - previous.bottom())
                            + "px after " + previous.name() + " ends at " + previous.bottom());
        }
        Band last = bands.get(bands.size() - 1);
        assertTrue(last.bottom() <= CompanionInventoryLayout.HEIGHT - 3,
                "the hotbar reaches " + last.bottom() + " of " + CompanionInventoryLayout.HEIGHT);
    }

    @Test
    void vitalsGroupsAreSeparatedAndInsideThePanel() {
        int heartsLeft = CompanionInventoryLayout.heartsX();
        int heartsRight = heartsLeft + CompanionInventoryLayout.groupWidth();
        int foodRight = CompanionInventoryLayout.foodRight();
        int foodLeft = foodRight - CompanionInventoryLayout.groupWidth();

        assertTrue(heartsLeft > 3, "the hearts touch the panel frame");
        assertTrue(foodRight < CompanionInventoryLayout.WIDTH - 3, "the food touches the panel frame");
        assertTrue(foodLeft - heartsRight >= 4,
                "the two groups are " + (foodLeft - heartsRight) + "px apart");
        assertEquals(CompanionInventoryLayout.WIDTH - 2 * CompanionInventoryLayout.VITALS_X,
                foodRight - heartsLeft, "the vitals are not centred on the panel");
        assertTrue(CompanionInventoryLayout.VITALS_Y + CompanionInventoryLayout.ICON
                        <= CompanionInventoryLayout.XP_Y - MIN_GAP,
                "the vitals sit on the experience bar");
    }

    @Test
    void gridsFitThePanelAndClearTheModelAndHands() {
        int firstColumn = CompanionInventoryLayout.columnX(0);
        int lastColumn = CompanionInventoryLayout.columnX(8);
        assertTrue(firstColumn - 1 >= 3, "the grid frame touches the panel outline");
        assertTrue(lastColumn + CompanionInventoryLayout.SLOT - 1 <= CompanionInventoryLayout.WIDTH - 3,
                "the grid frame runs into the panel bevel");
        assertTrue(firstColumn + CompanionInventoryLayout.SLOT <= CompanionInventoryLayout.PORTRAIT_X,
                "the model overlaps the armor column");
        assertTrue(CompanionInventoryLayout.PORTRAIT_X + CompanionInventoryLayout.PORTRAIT_WIDTH
                        <= CompanionInventoryLayout.HANDS_X,
                "the hands overlap the model");
        assertTrue(CompanionInventoryLayout.PORTRAIT_Y + CompanionInventoryLayout.PORTRAIT_HEIGHT
                        <= CompanionInventoryLayout.VITALS_Y,
                "the vitals overlap the model");
        assertTrue(CompanionInventoryLayout.LEVEL_Y + 9 <= CompanionInventoryLayout.HANDS_Y - 1,
                "the level sits on the hand slots");
    }
}
