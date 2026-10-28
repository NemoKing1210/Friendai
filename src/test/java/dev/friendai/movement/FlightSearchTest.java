package dev.friendai.movement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.pathing.Pos;
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
