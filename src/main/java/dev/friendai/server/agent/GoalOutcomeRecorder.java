package dev.friendai.server.agent;

import dev.friendai.agent.goal.GoalBoard;
import dev.friendai.agent.verify.Fact;
import dev.friendai.features.memory.Category;
import dev.friendai.features.memory.GoalOutcomeFact;
import dev.friendai.features.memory.MemoryBook;
import dev.friendai.features.memory.MemoryLimits;
import dev.friendai.features.memory.PlayerRecord;
import dev.friendai.features.memory.Subject;
import dev.friendai.foundation.teaching.TeachingContext;
import dev.friendai.infrastructure.config.ServerConfig;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Keeps the goal bookkeeping a turn and its tasks need: which goals the world can check, the shortest fact each
 * goal's own work proved, and the one memory line an ended goal earns. Owner state, all on the server thread.
 */
final class GoalOutcomeRecorder {
    /** The outcome source a persistent goal ends under: {@code goal:<id>}. */
    private static final String GOAL_OUTCOME = "goal:";

    private final Supplier<ServerConfig> config;
    private final Function<UUID, MemoryBook> books;

    /** Per owner, which goals the world could check when the board was last shown to the model. */
    private final Map<UUID, Map<Long, Boolean>> checkable = new HashMap<>();
    /** Per owner, the shortest fact each live goal's own work proved, so its outcome can cite it. */
    private final Map<UUID, Map<Long, String>> facts = new HashMap<>();

    GoalOutcomeRecorder(Supplier<ServerConfig> config, Function<UUID, MemoryBook> books) {
        this.config = config;
        this.books = books;
    }

    /**
     * Leaves the one memory fact an ended goal earns: what it was, whether the world checked every checkable
     * criterion, and the shortest verified fact. An abandoned goal the world verified nothing about is not
     * remembered; the book itself replaces an identical line instead of adding a second one.
     */
    void remember(UUID owner, String source, TeachingContext.OutcomeKind kind, String summary) {
        ServerConfig current = config.get();
        if (current == null || !current.memory.enabled || source == null || !source.startsWith(GOAL_OUTCOME)) {
            return;
        }
        long goalId;
        try {
            goalId = Long.parseLong(source.substring(GOAL_OUTCOME.length()));
        } catch (NumberFormatException e) {
            return;
        }
        Map<Long, Boolean> ownerCheckable = checkable.get(owner);
        Boolean worldChecked = ownerCheckable == null ? null : ownerCheckable.remove(goalId);
        boolean verified = kind == TeachingContext.OutcomeKind.GOAL_SUCCESS && Boolean.TRUE.equals(worldChecked);
        GoalOutcomeFact.End end;
        if (kind == TeachingContext.OutcomeKind.GOAL_FAILURE) {
            end = GoalOutcomeFact.End.ABANDONED;
        } else {
            end = verified ? GoalOutcomeFact.End.VERIFIED : GoalOutcomeFact.End.UNVERIFIED;
        }
        MemoryLimits limits = current.memory.limits();
        String text = GoalOutcomeFact.text(summary, end, takeFact(owner, goalId), limits.maxFactChars());
        if (text == null) {
            return;
        }
        MemoryBook book = books.apply(owner);
        try {
            book.addFact(
                    Subject.player(owner),
                    Category.NOTE,
                    text,
                    owner,
                    book.player(owner).map(PlayerRecord::lastName).orElse("owner"),
                    System.currentTimeMillis(),
                    limits);
        } catch (RuntimeException ignored) {
            // A full or refusing book must not undo the goal outcome.
        }
    }

    /** The shortest fact this goal's own work proved, if the world proved anything; read once per outcome. */
    private String takeFact(UUID owner, long goalId) {
        Map<Long, String> ownerFacts = facts.get(owner);
        return ownerFacts == null ? null : ownerFacts.remove(goalId);
    }

    /** Keeps the shortest fact a task of this goal actually proved, so the goal's memory line can cite it. */
    void noteFacts(UUID owner, long goalId, List<Fact> proven) {
        String shortest = null;
        for (Fact fact : proven) {
            String text = fact == null ? null : fact.text();
            if (text != null && !text.isBlank() && (shortest == null || text.length() < shortest.length())) {
                shortest = text;
            }
        }
        if (shortest == null) {
            return;
        }
        facts.computeIfAbsent(owner, id -> new HashMap<>())
                .merge(goalId, shortest, (held, fresh) -> fresh.length() < held.length() ? fresh : held);
    }

    /**
     * Records which goals the world can check when the model is about to see them, so a goal's memory fact
     * never claims a world check that never happened. A goal created and finished in one turn stays unverified.
     */
    void captureCheckability(UUID owner, GoalBoard board) {
        Map<Long, Boolean> ownerCheckable = new HashMap<>();
        for (var goal : board.all()) {
            ownerCheckable.put(
                    goal.id(),
                    goal.criteria().stream()
                            .anyMatch(criterion -> criterion.check().isPresent()));
        }
        checkable.put(owner, ownerCheckable);
    }

    /** Drops every trace of an owner who left, so a later join starts from the world's current state. */
    void forget(UUID owner) {
        checkable.remove(owner);
        facts.remove(owner);
    }
}
