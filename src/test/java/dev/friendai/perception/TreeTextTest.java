package dev.friendai.perception;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class TreeTextTest {
    private static final TreeSummary OAK =
            new TreeSummary("oak_log", "standing", "12 64 -3", 14, 12, 6.0, "north-west");
    private static final TreeSummary BIRCH =
            new TreeSummary("birch_log", "stump", "18 64 5", 4, 5, 11.4, "east");
    private static final TreeSummary WALL =
            new TreeSummary("oak_log", "log structure", "4 64 -8", 9, 3, 7.0, "south");

    @Test
    void promptTextHasDistanceLogsHeightAndKind() {
        assertEquals("oak_log 6 blocks north-west (14 logs, height 12, standing)", OAK.toPromptText());
    }

    @Test
    void perceptionLineJoinsTrees() {
        assertEquals("Trees: oak_log 6 blocks north-west (14 logs, height 12, standing); "
                + "birch_log 11.4 blocks east (4 logs, height 5, stump)",
                TreeText.perceptionLine(List.of(OAK, BIRCH)));
    }

    @Test
    void toolListingListsTreesWithCoordinates() {
        assertEquals("2 trees within 16 blocks: "
                + "oak_log at 12 64 -3 (6 blocks north-west), 14 logs, height 12, standing; "
                + "birch_log at 18 64 5 (11.4 blocks east), 4 logs, height 5, stump",
                TreeText.toolListing(List.of(OAK, BIRCH), List.of(), 16));
    }

    @Test
    void loneTreeIsSingular() {
        assertEquals("1 tree within 16 blocks: oak_log at 12 64 -3 (6 blocks north-west), "
                + "14 logs, height 12, standing", TreeText.toolListing(List.of(OAK), List.of(), 16));
    }

    @Test
    void structuresAreShownWhenNoTreeMatches() {
        assertEquals("no trees within 16 blocks; nearest oak_log at 4 64 -8 (7 blocks south), "
                + "9 logs, height 3, log structure", TreeText.toolListing(List.of(), List.of(WALL), 16));
    }

    @Test
    void structuresFollowTrees() {
        assertEquals("1 tree within 16 blocks: oak_log at 12 64 -3 (6 blocks north-west), "
                + "14 logs, height 12, standing; also oak_log at 4 64 -8 (7 blocks south), "
                + "9 logs, height 3, log structure", TreeText.toolListing(List.of(OAK), List.of(WALL), 16));
    }

    @Test
    void nothingNearby() {
        assertEquals("no trees within 16 blocks", TreeText.toolListing(List.of(), List.of(), 16));
    }
}
