package dev.friendai.execution.queue;

import java.util.Locale;
import java.util.Optional;

/**
 * Whether the owner's Retry belongs to the job they are looking at. Pure: the caller supplies what the runner
 * remembers about its last direct job and what is running now.
 *
 * <p>A retry replays a step exactly as it was queued, so it is only safe when that step ended without doing the work:
 * a success must never repeat, a job that is still live already has its chance, a step whose measured result says part
 * of the work already happened would repeat that part, and a step refused before it started would be refused again.
 */
public final class RetryPolicy {
    /** What the owner asked for: the job id they saw, and the outcome label the history showed. */
    public record Request(long jobId, String expectedOutcome) {
        public Request {
            expectedOutcome =
                    expectedOutcome == null ? "" : expectedOutcome.strip().toUpperCase(Locale.ROOT);
        }

        /** An older client sends no target; the remembered job is used instead. */
        public static Request untargeted() {
            return new Request(0, "");
        }

        public boolean targeted() {
            return jobId > 0;
        }
    }

    /** How the remembered direct job ended, as far as the runner saw it. */
    public enum Ending {
        /** Still queued or running. */
        RUNNING,
        /** It finished its work. */
        SUCCEEDED,
        /** It failed, timed out or was cancelled before doing its work. */
        FAILED,
        /** It did part of its measured work before it stopped. */
        PARTIAL,
        /** It never started: its authorization or factory refused it. */
        REFUSED
    }

    /** What the runner knows about the direct job it started last. */
    public record Last(long jobId, String tool, Ending end) {
        public Last {
            tool = tool == null ? "" : tool;
            if (end == null) end = Ending.RUNNING;
        }
    }

    public enum Outcome {
        /** The job was queued again. */
        QUEUED,
        /** No direct job was started in this session. */
        NOTHING,
        /** The request names a job that is not the last direct job. */
        STALE,
        /** The companion is busy with other work. */
        BUSY,
        /** The job is still queued or running. */
        LIVE,
        /** The job already succeeded; repeating it would do the work twice. */
        SUCCEEDED,
        /** The job measured part of its work; replaying it would repeat that part. */
        PARTIAL,
        /** The job never started; the same request would be refused the same way. */
        REFUSED
    }

    private RetryPolicy() {}

    /**
     * @param last the last direct activation, or empty
     * @param jobStillQueued whether that exact job is still pending or running
     * @param companionBusy whether a task other than the idle fallback is running
     */
    public static Outcome decide(Optional<Last> last, Request request, boolean jobStillQueued, boolean companionBusy) {
        if (last.isEmpty()) return Outcome.NOTHING;
        Last job = last.get();
        if (request.targeted() && request.jobId() != job.jobId()) return Outcome.STALE;
        if (jobStillQueued || job.end() == Ending.RUNNING) return Outcome.LIVE;
        if (companionBusy) return Outcome.BUSY;
        return switch (job.end()) {
            case SUCCEEDED -> Outcome.SUCCEEDED;
            case PARTIAL -> Outcome.PARTIAL;
            case REFUSED -> Outcome.REFUSED;
            case FAILED -> Outcome.QUEUED;
            case RUNNING -> Outcome.LIVE;
        };
    }

    /** True when the history label means the job is retryable as far as the client can tell. */
    public static boolean retryableOutcome(String historyStatus) {
        if (historyStatus == null) return false;
        return switch (historyStatus.strip().toUpperCase(Locale.ROOT)) {
            case "FAILED", "TIMED_OUT", "CANCELLED" -> true;
            default -> false;
        };
    }
}
