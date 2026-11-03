package dev.friendai.execution.log;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.task.ProgressWatch;
import org.junit.jupiter.api.Test;

class TaskDiagnosticEpisodeTest {
    @Test
    void theFirstObservationNeverWarns() {
        assertFalse(new TaskDiagnosticEpisode().sample(0, 1L, false, "mining"));
    }

    @Test
    void oneSnapshotIsDueAfterTheIntervalWithoutProgress() {
        TaskDiagnosticEpisode episode = new TaskDiagnosticEpisode();
        episode.sample(0, 1L, false, "mining");

        assertFalse(episode.sample(199, 1L, false, "mining"));
        assertTrue(episode.sample(200, 1L, false, "mining"));
        assertFalse(episode.sample(201, 1L, false, "mining"));
        assertTrue(episode.sample(400, 1L, false, "mining"));
    }

    @Test
    void materialProgressOrANewBlockerStartsAFreshEpisode() {
        TaskDiagnosticEpisode episode = new TaskDiagnosticEpisode();
        episode.sample(0, 1L, false, "mining");

        assertFalse(episode.sample(250, 2L, false, "mining"), "progress resets the interval");
        assertFalse(episode.sample(251, 2L, false, "path_unreachable"), "a new blocker resets the interval");
        assertFalse(episode.sample(450, 2L, false, "path_unreachable"));
        assertTrue(episode.sample(451 + 200, 2L, false, "path_unreachable"));
    }

    @Test
    void aLegitimateWaitNeverWarns() {
        TaskDiagnosticEpisode episode = new TaskDiagnosticEpisode();
        episode.sample(0, ProgressWatch.UNKNOWN, true, "");

        assertFalse(episode.sample(1_000, ProgressWatch.UNKNOWN, true, ""));
        assertFalse(episode.sample(5_000, ProgressWatch.UNKNOWN, true, "waiting_drop"));
    }

    @Test
    void anUnknownStampIsNotProgress() {
        TaskDiagnosticEpisode episode = new TaskDiagnosticEpisode();
        episode.sample(0, ProgressWatch.UNKNOWN, false, "");

        assertTrue(episode.sample(200, ProgressWatch.UNKNOWN, false, ""));
    }
}
