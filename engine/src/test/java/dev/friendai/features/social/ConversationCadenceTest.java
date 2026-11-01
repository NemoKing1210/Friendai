package dev.friendai.features.social;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ConversationCadenceTest {
    /** The first tick at which the scene may speak, found by binary search over a monotone answer. */
    private static int firstAllowedTick(ConversationCadence cadence, ConversationCadence.Scene scene) {
        int low = 0;
        int high = 100_000;
        while (low < high) {
            int mid = (low + high) >>> 1;
            if (cadence.maySpeak(scene, mid)) {
                high = mid;
            } else {
                low = mid + 1;
            }
        }
        return low;
    }

    /** The silence a scene imposes after a statement spoken at {@code tick}. */
    private static int gapAt(ConversationCadence.Scene scene, int tick) {
        ConversationCadence cadence = new ConversationCadence();
        cadence.recordLine(scene, tick, false, false);
        return firstAllowedTick(cadence, scene) - tick;
    }

    @Test
    void aSceneGapAndTheOwnersQuietWindowSilenceTheCompanion() {
        ConversationCadence cadence = new ConversationCadence();
        cadence.ownerSpoke(100);
        assertFalse(cadence.maySpeak(ConversationCadence.Scene.FREE, 150));
        assertTrue(cadence.maySpeak(ConversationCadence.Scene.FREE, 1_000));
        cadence.recordLine(ConversationCadence.Scene.FREE, 1_000, false, false);
        assertFalse(cadence.maySpeak(ConversationCadence.Scene.FREE, 2_000));
        assertTrue(cadence.maySpeak(ConversationCadence.Scene.FREE, 4_000));
    }

    @Test
    void anUnansweredQuestionBlocksAnotherAndBacksOff() {
        ConversationCadence cadence = new ConversationCadence();
        cadence.recordLine(ConversationCadence.Scene.REST, 0, true, false);
        assertTrue(cadence.awaitingAnswer());
        assertFalse(cadence.mayAskQuestion());
        assertTrue(cadence.backoff() > 0);
        cadence.ownerSpoke(10);
        assertTrue(cadence.mayAskQuestion());
    }

    @Test
    void anAnsweredLineClearsTheBackoff() {
        ConversationCadence cadence = new ConversationCadence();
        cadence.recordLine(ConversationCadence.Scene.FREE, 0, true, true);
        assertFalse(cadence.awaitingAnswer());
        assertTrue(cadence.backoff() == 0);
    }

    @Test
    void theOwnersQuietWindowHasAnExactEnd() {
        ConversationCadence cadence = new ConversationCadence();
        cadence.ownerSpoke(100);
        assertFalse(cadence.maySpeak(ConversationCadence.Scene.FREE, 199), "the window is still open one tick early");
        assertTrue(cadence.maySpeak(ConversationCadence.Scene.FREE, 200), "the window does not end late");
    }

    @Test
    void everySceneHasItsOwnGapAndTheGapIsJittered() {
        int combat = gapAt(ConversationCadence.Scene.COMBAT, 0);
        int free = gapAt(ConversationCadence.Scene.FREE, 0);
        int busy = gapAt(ConversationCadence.Scene.BUSY, 0);
        int rest = gapAt(ConversationCadence.Scene.REST, 0);
        int away = gapAt(ConversationCadence.Scene.AWAY, 0);
        assertTrue(
                away > rest && rest > busy && busy > free && free > combat,
                "gaps: away=" + away + " rest=" + rest + " busy=" + busy + " free=" + free + " combat=" + combat);

        Set<Integer> jittered = new HashSet<>();
        for (int tick = 0; tick < 6; tick++) {
            jittered.add(gapAt(ConversationCadence.Scene.FREE, tick));
        }
        assertTrue(jittered.size() > 1, "the same scene always waits the same time: " + jittered);
    }

    @Test
    void combatStaysSilentWhileAQuestionIsUnanswered() {
        ConversationCadence cadence = new ConversationCadence();
        cadence.recordLine(ConversationCadence.Scene.REST, 0, true, false);
        assertTrue(cadence.maySpeak(ConversationCadence.Scene.FREE, 10_000), "a calm scene may speak again");
        assertFalse(
                cadence.maySpeak(ConversationCadence.Scene.COMBAT, 10_000),
                "combat refuses an ordinary line while an earlier question waits");
    }

    @Test
    void everyIgnoredQuestionBacksOffFurtherUntilTheCap() {
        ConversationCadence cadence = new ConversationCadence();
        cadence.recordLine(ConversationCadence.Scene.REST, 0, true, false);
        int firstGap = firstAllowedTick(cadence, ConversationCadence.Scene.REST);
        assertEquals(1, cadence.backoff());

        cadence.recordLine(ConversationCadence.Scene.REST, firstGap, true, false);
        int secondGap = firstAllowedTick(cadence, ConversationCadence.Scene.REST) - firstGap;
        assertTrue(secondGap > firstGap, "the silence did not grow: " + firstGap + " -> " + secondGap);
        assertTrue(cadence.backoff() > 1, "an ignored question did not raise the backoff");

        List<Integer> backoffs = new ArrayList<>();
        int tick = firstGap + secondGap;
        for (int i = 0; i < 10; i++) {
            cadence.recordLine(ConversationCadence.Scene.REST, tick, true, false);
            backoffs.add(cadence.backoff());
            tick += 100_000;
        }
        int plateau = backoffs.getLast();
        assertEquals(plateau, backoffs.get(backoffs.size() - 2), "the backoff stops growing: " + backoffs);
        assertTrue(plateau > 1);
    }

    @Test
    void anOrdinaryLineAfterAnIgnoredQuestionClearsTheBackoff() {
        ConversationCadence cadence = new ConversationCadence();
        cadence.recordLine(ConversationCadence.Scene.REST, 0, true, false);
        assertTrue(cadence.backoff() > 0);
        cadence.recordLine(ConversationCadence.Scene.REST, 100_000, false, false);
        assertEquals(0, cadence.backoff(), "a statement nobody had to answer clears the backoff");
    }
}
