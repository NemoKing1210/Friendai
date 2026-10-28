package dev.friendai.agent.goal;

import dev.friendai.memory.MemoryBook;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.IntSupplier;

/**
 * One cached {@link GoalBoard} per owner, stored as text in that owner's memory book so goals outlive the
 * companion entity (death, respawn, dismissal). Server thread only.
 */
public final class GoalService {
    public static final int MAX_BACKGROUND = 3;

    private final Function<UUID, MemoryBook> books;
    private final IntSupplier maxReplans;
    private final Map<UUID, GoalBoard> boards = new HashMap<>();

    public GoalService(Function<UUID, MemoryBook> books, IntSupplier maxReplans) {
        this.books = books;
        this.maxReplans = maxReplans;
    }

    /** The owner's board, decoded from the book on first use; a missing or broken save starts empty. */
    public GoalBoard board(UUID owner) {
        return boards.computeIfAbsent(
                owner,
                id -> books.apply(id)
                        .goals()
                        .flatMap(saved -> GoalSave.decode(saved, MAX_BACKGROUND, maxReplans.getAsInt()))
                        .orElseGet(() -> new GoalBoard(MAX_BACKGROUND, maxReplans.getAsInt())));
    }

    /** Writes the board back into the owner's book; call after every change. */
    public void save(UUID owner) {
        GoalBoard board = boards.get(owner);
        if (board != null) {
            books.apply(owner).setGoals(board.all().isEmpty() ? null : GoalSave.encode(board));
        }
    }
}
