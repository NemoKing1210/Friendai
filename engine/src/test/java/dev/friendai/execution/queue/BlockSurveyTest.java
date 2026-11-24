package dev.friendai.execution.queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** A block survey answers only about the cube it read, and only when it read all of it. */
class BlockSurveyTest {
    /** A growable cube of matching cells addressed by world coordinates. */
    private static final class World implements BlockSurvey.Cells {
        final Set<String> solid = new HashSet<>();
        final Set<String> unloaded = new HashSet<>();
        int inspected;

        void block(int x, int y, int z) {
            solid.add(x + "," + y + "," + z);
        }

        void unload(int x, int y, int z) {
            unloaded.add(x + "," + y + "," + z);
        }

        @Override
        public boolean loaded(int x, int y, int z) {
            inspected++;
            return !unloaded.contains(x + "," + y + "," + z);
        }

        @Override
        public boolean matches(int x, int y, int z) {
            return solid.contains(x + "," + y + "," + z);
        }
    }

    @Test
    void findsAMatchEarlyAndRemembersWhereItWas() {
        World world = new World();
        world.block(1, 0, 0);
        BlockSurvey survey = new BlockSurvey(0, 0, 0, 1, 1);

        BlockSurvey.Result result = survey.advance(world, 1000);

        assertEquals(BlockSurvey.Outcome.MATCHED, result.outcome());
        assertTrue(survey.complete());
        assertTrue(survey.stillMatches(world));
        world.solid.clear();
        assertFalse(survey.stillMatches(world), "a broken block invalidates the remembered match");
    }

    @Test
    void anEmptyCubeIsAbsentOnlyAfterEveryCellWasRead() {
        World world = new World();
        BlockSurvey survey = new BlockSurvey(0, 0, 0, 1, 1);
        world.unload(1, 1, 1);

        assertEquals(BlockSurvey.Outcome.INCOMPLETE, survey.advance(world, 1000).outcome());

        world.unloaded.clear();
        assertEquals(BlockSurvey.Outcome.ABSENT, survey.advance(world, 1000).outcome());
    }

    @Test
    void aSpentBudgetResumesWhereItStopped() {
        World world = new World();
        // The match sits at the very end of a radius-2 cube: nothing is found before the budget is spent.
        world.block(2, 2, 2);
        BlockSurvey survey = new BlockSurvey(0, 0, 0, 2, 1);
        int volume = 5 * 5 * 5;

        BlockSurvey.Result first = survey.advance(world, 10);
        assertEquals(BlockSurvey.Outcome.INCOMPLETE, first.outcome());
        assertEquals(10, first.inspected());

        int total = first.inspected();
        BlockSurvey.Result next;
        do {
            next = survey.advance(world, 10);
            total += next.inspected();
        } while (next.outcome() == BlockSurvey.Outcome.INCOMPLETE && total < volume + 10);

        assertEquals(BlockSurvey.Outcome.MATCHED, next.outcome());
        assertEquals(volume, total, "the cube is walked once, not restarted after each budget");
    }

    @Test
    void twoMatchingCellsSatisfyACountOfTwo() {
        World world = new World();
        world.block(0, 0, 0);
        world.block(1, 0, 0);
        BlockSurvey survey = new BlockSurvey(0, 0, 0, 1, 2);

        assertEquals(BlockSurvey.Outcome.MATCHED, survey.advance(world, 1000).outcome());

        BlockSurvey tooFew = new BlockSurvey(0, 0, 0, 1, 3);
        assertEquals(BlockSurvey.Outcome.ABSENT, tooFew.advance(world, 1000).outcome());
    }

    @Test
    void resetMeasuresTheCubeAgain() {
        World world = new World();
        BlockSurvey survey = new BlockSurvey(0, 0, 0, 1, 1);
        assertEquals(BlockSurvey.Outcome.ABSENT, survey.advance(world, 1000).outcome());

        world.block(0, 0, 0);
        survey.reset();

        assertEquals(BlockSurvey.Outcome.MATCHED, survey.advance(world, 1000).outcome());
    }
}
