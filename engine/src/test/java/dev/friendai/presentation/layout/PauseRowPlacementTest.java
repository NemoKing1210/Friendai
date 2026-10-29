package dev.friendai.presentation.layout;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class PauseRowPlacementTest {
    @Test
    void appendsAfterLastIconWithSameGap() {
        List<Rect> icons = List.of(new Rect(0, 10, 20, 20), new Rect(24, 10, 20, 20), new Rect(48, 10, 20, 20));
        assertEquals(new Rect(72, 10, 20, 20), PauseRowPlacement.place(icons, 400, 20));
    }

    @Test
    void usesLargestSameYGroup() {
        List<Rect> icons = List.of(new Rect(0, 10, 20, 20), new Rect(24, 10, 20, 20), new Rect(200, 90, 20, 20));
        assertEquals(new Rect(48, 10, 20, 20), PauseRowPlacement.place(icons, 400, 20));
    }

    @Test
    void singleIconUsesDefaultGap() {
        assertEquals(new Rect(24, 10, 20, 20), PauseRowPlacement.place(List.of(new Rect(0, 10, 20, 20)), 400, 20));
    }

    @Test
    void emptyUsesTopRightFallback() {
        assertEquals(new Rect(372, 8, 20, 20), PauseRowPlacement.place(List.of(), 400, 20));
    }

    @Test
    void tiePicksSmallestY() {
        List<Rect> icons = List.of(new Rect(0, 50, 20, 20), new Rect(0, 10, 20, 20));
        assertEquals(new Rect(24, 10, 20, 20), PauseRowPlacement.place(icons, 400, 20));
    }
}
