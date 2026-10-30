package dev.friendai.foundation.text;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class TextBudgetTest {
    @Test
    void keepsWholeLinesUntilTheCap() {
        TextBudget.Fit fit = TextBudget.fit(List.of("aaaa", "bbbb", "cccc"), 10);
        assertEquals(List.of("aaaa", "bbbb"), fit.lines());
        assertTrue(fit.truncated());
    }

    @Test
    void anExactFitIsNotMarkedTruncated() {
        TextBudget.Fit fit = TextBudget.fit(List.of("aaaa", "bbbb"), 9);
        assertEquals(List.of("aaaa", "bbbb"), fit.lines());
        assertFalse(fit.truncated());
    }

    @Test
    void duplicateLinesAreDropped() {
        TextBudget.Fit fit = TextBudget.fit(List.of("x", "x", "y"), 100);
        assertEquals(List.of("x", "y"), fit.lines());
        assertFalse(fit.truncated());
    }

    @Test
    void aSingleTooLongLineIsCutButStillReturned() {
        TextBudget.Fit fit = TextBudget.fit(List.of("abcdefgh"), 4);
        assertEquals(List.of("abc…"), fit.lines());
        assertTrue(fit.truncated());
    }

    @Test
    void aNonPositiveCapMeansUnlimited() {
        TextBudget.Fit fit = TextBudget.fit(List.of("a", "b"), 0);
        assertEquals(List.of("a", "b"), fit.lines());
        assertFalse(fit.truncated());
    }
}
