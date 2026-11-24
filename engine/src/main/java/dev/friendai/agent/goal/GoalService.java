package dev.friendai.agent.goal;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.IntSupplier;

/**
 * One cached {@link GoalBoard} per owner, loaded and saved through {@link GoalRepository} so goals outlive the
 * companion entity (death, respawn, dismissal). Server thread only.
 */
public final class GoalService {
    public static final int MAX_BACKGROUND = 3;

    private final GoalRepository repository;
    private final IntSupplier maxReplans;

    public GoalService(GoalRepository repository, IntSupplier maxReplans) {
        this.repository = repository;
        this.maxReplans = maxReplans;
    }

    /** The owner's board, loaded by the repository on first use; a missing or broken save starts empty. */
    public GoalBoard board(UUID owner) {
        return repository.board(owner, MAX_BACKGROUND, maxReplans.getAsInt());
    }

    /** Saves the board through the repository; call after every change. */
    public void save(UUID owner) {
        repository.save(owner, repository.board(owner, MAX_BACKGROUND, maxReplans.getAsInt()));
    }

    /**
     * One board edit and its write, for every caller that mutates a goal — the model's tools and the owner's panel
     * alike. The write is requested only when the board accepted the edit, so a refused edit changes neither the
     * board nor the stored record and the caller can report the board's own reason.
     */
    public GoalResult<Goal> update(
            UUID owner,
            long goalId,
            Optional<String> text,
            Optional<List<Criterion>> criteria,
            Optional<GoalStatus> status,
            Optional<Long> deadline) {
        GoalResult<Goal> result = board(owner).update(goalId, text, criteria, status, deadline);
        if (result.ok()) {
            save(owner);
        }
        return result;
    }
}
