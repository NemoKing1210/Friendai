package dev.friendai.execution.queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class PlanPathTest {
    @Test
    void aRootStepHasNoRouteAndAZeroBasedIndex() {
        PlanPath.Target target = target("step 3");
        assertEquals(List.of(), target.route());
        assertEquals(2, target.index());
    }

    @Test
    void aThenChildAddsAThenPair() {
        PlanPath.Target target = target("step 3 → then #2");
        assertEquals(List.of(2, 1), target.route());
        assertEquals(1, target.index());
    }

    @Test
    void anElseChildAddsAnElsePair() {
        PlanPath.Target target = target("step 3 → else #2");
        assertEquals(List.of(2, 2), target.route());
        assertEquals(1, target.index());
    }

    @Test
    void aRepeatBodyAddsARepeatPair() {
        PlanPath.Target target = target("step 3 → repeat → step 4");
        assertEquals(List.of(2, 3), target.route());
        assertEquals(3, target.index());
    }

    @Test
    void nestedThenAndRepeatAreBothKept() {
        PlanPath.Target target = target("step 1 → then #1 → repeat → step 2");
        assertEquals(List.of(0, 1, 0, 3), target.route());
        assertEquals(1, target.index());
    }

    @Test
    void aConditionalInsideABranchIsKept() {
        PlanPath.Target target = target("step 3 → then #2 → else #5");
        assertEquals(List.of(2, 1, 1, 2), target.route());
        assertEquals(4, target.index());
    }

    @Test
    void aConditionalInsideARepeatBodyIsKept() {
        PlanPath.Target target = target("step 3 → repeat → step 4 → then #1");
        assertEquals(List.of(2, 3, 3, 1), target.route());
        assertEquals(0, target.index());
    }

    @Test
    void threeLevelsOfNestingAreKept() {
        PlanPath.Target target = target("step 2 → then #1 → repeat → step 3 → else #7");
        assertEquals(List.of(1, 1, 0, 3, 2, 2), target.route());
        assertEquals(6, target.index());
    }

    @Test
    void surroundingWhitespaceIsIgnored() {
        PlanPath.Target target = target("  step 3 → then #2  ");
        assertEquals(List.of(2, 1), target.route());
        assertEquals(1, target.index());
    }

    @Test
    void emptyTextIsRejected() {
        assertTrue(PlanPath.parse("").isEmpty());
        assertTrue(PlanPath.parse("   ").isEmpty());
        assertTrue(PlanPath.parse(null).isEmpty());
    }

    @Test
    void unknownTextIsRejected() {
        assertRejected("plan → step 3");
        assertRejected("step");
        assertRejected("step three");
        assertRejected("step 3 → sometimes #2");
        assertRejected("step 3 → then #2 #3");
    }

    @Test
    void aDanglingBranchMarkerIsRejected() {
        assertRejected("step 3 → then");
        assertRejected("step 3 → else");
    }

    @Test
    void aDanglingRepeatMarkerIsRejected() {
        assertRejected("step 3 → repeat");
        assertRejected("step 3 → repeat → ");
    }

    @Test
    void aStepInsideAConditionalBranchIsRejected() {
        assertRejected("step 3 → then #2 → step 4");
        assertRejected("step 3 → then → #2");
    }

    @Test
    void aSecondStepInsideOneSequenceIsRejected() {
        assertRejected("step 3 → step 4");
        assertRejected("step 3 → repeat → step 4 → step 5");
    }

    @Test
    void aNonStepRepeatBodyIsRejected() {
        assertRejected("step 3 → repeat → then #2");
        assertRejected("step 3 → repeat → repeat");
    }

    @Test
    void zeroAndMalformedIndicesAreRejected() {
        assertRejected("step 0");
        assertRejected("step 3 → then #0");
        assertRejected("step 3 → repeat → step 0");
        assertRejected("step -1");
        assertRejected("step 3 → then #-1");
        assertRejected("step 3.5");
    }

    @Test
    void anOverflowingIndexIsRejected() {
        assertRejected("step 2147483648");
        assertRejected("step 3 → then #99999999999");
    }

    private static PlanPath.Target target(String path) {
        Optional<PlanPath.Target> parsed = PlanPath.parse(path);
        assertTrue(parsed.isPresent(), () -> "expected a target for " + path);
        return parsed.orElseThrow();
    }

    private static void assertRejected(String path) {
        assertFalse(PlanPath.parse(path).isPresent(), () -> "expected no target for " + path);
    }
}
