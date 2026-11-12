package dev.friendai.features.mining.tree;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

class TreeCandidateRankerTest {
    private static TreeCandidateRanker.Candidate candidate(
            int x,
            String species,
            TreeCandidateRanker.Kind kind,
            int visible,
            int safe,
            double approach,
            double climb,
            double collection,
            boolean revalidated,
            boolean collectionKnown,
            boolean protectedRoute) {
        return new TreeCandidateRanker.Candidate(
                new TreeCell(x, 2, 0),
                species,
                kind,
                visible,
                safe,
                approach,
                climb,
                collection,
                revalidated,
                collectionKnown,
                protectedRoute);
    }

    @Test
    void lowerTotalApproachClimbAndCollectionCostWinsWhenBothMeetTheNeed() {
        var farGroundTree =
                candidate(9, "oak_log", TreeCandidateRanker.Kind.STANDING_TREE, 8, 8, 9, 0, 0, true, true, false);
        var nearCostlyTree =
                candidate(4, "birch_log", TreeCandidateRanker.Kind.STANDING_TREE, 8, 8, 4, 3, 4, true, true, false);

        assertEquals(
                List.of(farGroundTree, nearCostlyTree),
                TreeCandidateRanker.rank(List.of(nearCostlyTree, farGroundTree), 8));
    }

    @Test
    void bestPartialYieldRemainsEligibleWhenNoCandidateCanMeetTheNeed() {
        var twoLogs = candidate(1, "oak_log", TreeCandidateRanker.Kind.STANDING_TREE, 4, 2, 1, 0, 0, true, true, false);
        var threeLogs =
                candidate(9, "birch_log", TreeCandidateRanker.Kind.STANDING_TREE, 3, 3, 9, 0, 0, true, true, false);

        assertEquals(List.of(threeLogs, twoLogs), TreeCandidateRanker.rank(List.of(twoLogs, threeLogs), 8));
    }

    @Test
    void equalCandidatesUseTheirTrunkBaseAsTheDeterministicTieBreak() {
        var later = candidate(7, "oak_log", TreeCandidateRanker.Kind.STANDING_TREE, 6, 6, 4, 1, 2, true, true, false);
        var earlier = candidate(3, "oak_log", TreeCandidateRanker.Kind.STANDING_TREE, 6, 6, 4, 1, 2, true, true, false);

        assertEquals(List.of(earlier, later), TreeCandidateRanker.rank(List.of(later, earlier), 5));
    }

    @Test
    void protectedStaleAndStructuralCandidatesAreNotEligible() {
        var protectedRoute =
                candidate(1, "oak_log", TreeCandidateRanker.Kind.STANDING_TREE, 8, 8, 1, 0, 0, true, true, true);
        var stale =
                candidate(2, "birch_log", TreeCandidateRanker.Kind.STANDING_TREE, 8, 8, 1, 0, 0, false, true, false);
        var structure =
                candidate(3, "spruce_log", TreeCandidateRanker.Kind.LOG_STRUCTURE, 8, 8, 1, 0, 0, true, true, false);
        var eligible =
                candidate(4, "jungle_log", TreeCandidateRanker.Kind.STANDING_TREE, 3, 3, 8, 0, 0, true, true, false);

        assertEquals(
                List.of(eligible), TreeCandidateRanker.rank(List.of(protectedRoute, stale, structure, eligible), 8));
    }

    @Test
    void unknownCollectionStaysAnUncertainCandidateBehindMeasuredPartialYield() {
        var unknown =
                candidate(1, "oak_log", TreeCandidateRanker.Kind.STANDING_TREE, 8, 0, 1, 0, 0, true, false, false);
        var measuredPartial =
                candidate(2, "birch_log", TreeCandidateRanker.Kind.STANDING_TREE, 3, 3, 4, 0, 0, true, true, false);

        assertEquals(List.of(measuredPartial, unknown), TreeCandidateRanker.rank(List.of(unknown, measuredPartial), 8));
        assertEquals(List.of(unknown), TreeCandidateRanker.rank(List.of(unknown), 8));
        assertThrows(IllegalArgumentException.class, () -> TreeCandidateRanker.rank(List.of(unknown), 0));
    }
}
