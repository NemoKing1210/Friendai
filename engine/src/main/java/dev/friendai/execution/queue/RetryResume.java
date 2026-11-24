package dev.friendai.execution.queue;

/**
 * What the next attempt of a failed queued job may do with the progress the failed attempt left behind.
 *
 * <p>A task that saved a checkpoint committed part of its work, so the next attempt continues from that checkpoint
 * instead of repeating the request. Without one the request can be repeated, except after a crash: a crash may have
 * changed the world before the task recorded its progress, and a tool that commits world effects is then not safe to
 * repeat blindly. A task that reported a clean failure without a checkpoint says that nothing was committed, so even a
 * committing tool starts over — the acknowledgement a save alone cannot give.
 */
public final class RetryResume {
    public enum Action {
        /** The saved progress is rebuilt and the attempt continues from it. */
        RESUME,
        /** The request runs again from the beginning. */
        RESTART,
        /** The next attempt needs the owner: repeating the request could apply its effects twice. */
        ASK
    }

    private RetryResume() {}

    /**
     * @param checkpointSaved the failed attempt produced a task checkpoint
     * @param crashed the attempt ended through an exception rather than a reported status
     * @param commitsWorldEffects the tool changes the world before it finishes
     */
    public static Action decide(boolean checkpointSaved, boolean crashed, boolean commitsWorldEffects) {
        if (checkpointSaved) return Action.RESUME;
        return crashed && commitsWorldEffects ? Action.ASK : Action.RESTART;
    }

    /** The saved progress could not be rebuilt into a task: a committing tool waits for the owner. */
    public static Action unreadable(boolean commitsWorldEffects) {
        return commitsWorldEffects ? Action.ASK : Action.RESTART;
    }
}
