package dev.friendai.agent.goal;

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
}
