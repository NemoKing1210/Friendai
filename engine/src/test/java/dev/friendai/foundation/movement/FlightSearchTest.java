package dev.friendai.foundation.movement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.foundation.spatial.Pos;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class FlightSearchTest {
    @Test
    void routesUpAndDownThroughClearanceCells() {
        FlightSearch ascent = search(new Pos(0, 0, 0), new Pos(0, 5, 0), Set.of(), 100);
        assertEquals(FlightSearch.Status.FOUND, finish(ascent));
        assertEquals(new Pos(0, 5, 0), ascent.path().getLast());
        assertTrue(ascent.path().stream().anyMatch(pos -> pos.y() > 0));

        FlightSearch descent = search(new Pos(0, 5, 0), new Pos(0, 0, 0), Set.of(), 100);
        assertEquals(FlightSearch.Status.FOUND, finish(descent));
        assertEquals(new Pos(0, 0, 0), descent.path().getLast());
    }

    @Test
    void routesAroundWallWithoutCrossingBlockedCells() {
        Set<Pos> blocked = new HashSet<>();
        for (int y = -3; y <= 3; y++) {
            for (int z = -3; z <= 3; z++) blocked.add(new Pos(1, y, z));
        }
        FlightSearch search = search(new Pos(0, 0, 0), new Pos(2, 0, 0), blocked, 2_000);
        assertEquals(FlightSearch.Status.FOUND, finish(search));
        assertTrue(search.path().stream().noneMatch(blocked::contains));
        assertTrue(search.path().stream().anyMatch(pos -> Math.abs(pos.z()) > 3 || Math.abs(pos.y()) > 3));
    }

    @Test
    void rejectsDiagonalCornerCuttingAndOutOfBoundsCells() {
        Set<Pos> blocked = Set.of(new Pos(1, 0, 0), new Pos(0, 1, 0));
        Pos start = new Pos(0, 0, 0);
        Pos goal = new Pos(1, 1, 0);
        FlightSearch corner = new FlightSearch(
                start, goal, pos -> !blocked.contains(pos) && (pos.equals(start) || pos.equals(goal)), 100);
        assertEquals(FlightSearch.Status.NO_PATH, finish(corner));

        FlightSearch bounds = new FlightSearch(
                new Pos(0, 0, 0), new Pos(4, 0, 0), pos -> Math.abs(pos.x()) <= 2 && pos.y() == 0 && pos.z() == 0, 100);
        assertEquals(FlightSearch.Status.NO_PATH, finish(bounds));
    }

    @Test
    void obeysPerStepAndTotalExpansionBudgets() {
        FlightSearch sliced = search(new Pos(0, 0, 0), new Pos(8, 0, 0), Set.of(), 1_000);
        FlightSearch.Progress first = sliced.step(1);
        assertEquals(FlightSearch.Status.RUNNING, first.status());
        assertEquals(1, first.expandedThisStep());
        assertEquals(FlightSearch.Status.FOUND, finish(sliced));

        FlightSearch limited = search(new Pos(0, 0, 0), new Pos(8, 0, 0), Set.of(), 1);
        assertEquals(FlightSearch.Status.LIMIT, finish(limited));
    }

    @Test
    void refusesBlockedStartOrGoalAndReturnsNoPathForEnclosedTarget() {
        FlightSearch blockedStart = search(new Pos(0, 0, 0), new Pos(1, 0, 0), Set.of(new Pos(0, 0, 0)), 100);
        assertEquals(FlightSearch.Status.NO_PATH, finish(blockedStart));
        FlightSearch blockedGoal = search(new Pos(0, 0, 0), new Pos(1, 0, 0), Set.of(new Pos(1, 0, 0)), 100);
        assertEquals(FlightSearch.Status.NO_PATH, finish(blockedGoal));
        assertNotNull(blockedGoal);
    }

    @Test
    void aGoalInsideTheSearchBoxIsItsOwnLeg() {
        assertEquals(new Pos(3, 0, -3), FlightSearch.legToward(new Pos(0, 0, 0), new Pos(3, 0, -3)));
        assertEquals(new Pos(40, -20, 10), FlightSearch.legToward(new Pos(0, 0, 0), new Pos(40, -20, 10)));
    }

    @Test
    void aFarGoalBecomesALegInsideTheSearchBoxAndTheChainReachesIt() {
        Pos goal = new Pos(250, 0, -130);
        Pos at = new Pos(0, 0, 0);
        Pos leg = FlightSearch.legToward(at, goal);
        assertEquals(new Pos(60, 0, -60), leg);
        assertTrue(Math.abs(leg.x() - at.x()) <= FlightSearch.MAX_AXIS_DISTANCE);
        assertTrue(Math.abs(leg.z() - at.z()) <= FlightSearch.MAX_AXIS_DISTANCE);

        double previous = Double.POSITIVE_INFINITY;
        int legs = 0;
        while (!at.equals(goal) && legs < 100) {
            Pos next = FlightSearch.legToward(at, goal);
            assertTrue(
                    Math.abs(next.x() - at.x()) <= FlightSearch.MAX_AXIS_DISTANCE
                            && Math.abs(next.y() - at.y()) <= FlightSearch.MAX_AXIS_DISTANCE
                            && Math.abs(next.z() - at.z()) <= FlightSearch.MAX_AXIS_DISTANCE,
                    "leg from " + at + " to " + next + " leaves the search box");
            double remaining = Math.sqrt(Math.pow((double) goal.x() - next.x(), 2)
                    + Math.pow((double) goal.y() - next.y(), 2)
                    + Math.pow((double) goal.z() - next.z(), 2));
            assertTrue(remaining < previous, "leg " + next + " made no progress from " + at);
            previous = remaining;
            at = next;
            legs++;
        }
        assertEquals(goal, at);
        assertTrue(legs <= 5, "a 280-block flight took " + legs + " legs");
    }

    private static FlightSearch search(Pos start, Pos goal, Set<Pos> blocked, int maxExpansions) {
        return new FlightSearch(start, goal, pos -> !blocked.contains(pos), maxExpansions);
    }

    private static FlightSearch.Status finish(FlightSearch search) {
        FlightSearch.Progress progress;
        do {
            progress = search.step(512);
        } while (progress.status() == FlightSearch.Status.RUNNING);
        if (progress.status() == FlightSearch.Status.NO_PATH) assertNull(search.path());
        if (progress.status() == FlightSearch.Status.FOUND)
            assertFalse(search.path().isEmpty());
        return progress.status();
    }
}
