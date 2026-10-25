package dev.friendai.brain;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class SpeechRepeatTest {

    @Test
    void theSameStatementInOtherWordsIsARepeat() {
        assertTrue(SpeechRepeat.repeats(
                "Держи, три котлеты твои.",
                "Даю тебе три жареные котлеты — три оставлю себе."));
        assertTrue(SpeechRepeat.repeats(
                "Here, the three cutlets are yours.",
                "I'm giving you three cutlets, I'll keep three."));
        assertTrue(SpeechRepeat.repeats(
                "Камень добыт, печь готова.",
                "Добываю камень, потом сделаю печь и пожарю свинину."));
    }

    @Test
    void theSameLineWithOtherPunctuationIsARepeat() {
        assertTrue(SpeechRepeat.repeats("Done!", "done"));
        assertTrue(SpeechRepeat.repeats("  Mined   eight blocks. ", "Mined eight blocks"));
    }

    @Test
    void newInformationIsNotARepeat() {
        assertFalse(SpeechRepeat.repeats(
                "Топливо закончилось, пять котлет остались в печи.",
                "Жарю пять котлет на досках."));
        assertFalse(SpeechRepeat.repeats(
                "Ran out of fuel, five cutlets are still in the furnace.",
                "Cooking six cutlets now."));
        assertFalse(SpeechRepeat.repeats("Добыл камень.", "Иду к печи."));
    }

    @Test
    void negationChangesTheStatement() {
        assertFalse(SpeechRepeat.repeats("Печь не нашлась.", "Печь нашлась."));
    }

    @Test
    void aSharedWordIsNotEnough() {
        assertFalse(SpeechRepeat.repeats("Котлеты готовы.", "Нужно восемь котлет."));
    }

    @Test
    void nothingComparableMeansNoRepeat() {
        assertFalse(SpeechRepeat.repeats(null, "Done."));
        assertFalse(SpeechRepeat.repeats("Done.", null));
        assertFalse(SpeechRepeat.repeats(" ", "  "));
        assertFalse(SpeechRepeat.repeats("[silent]", "Done."));
    }

    @Test
    void anyOfTheRecentLinesCounts() {
        List<String> recent = List.of("Держи, три котлеты твои.", "Иду к печи.");
        assertTrue(SpeechRepeat.repeatsAny("Даю тебе три котлеты.", recent));
        assertFalse(SpeechRepeat.repeatsAny("Топливо закончилось.", recent));
        assertFalse(SpeechRepeat.repeatsAny("Топливо закончилось.", List.of()));
        assertFalse(SpeechRepeat.repeatsAny("Топливо закончилось.", null));
    }
}
