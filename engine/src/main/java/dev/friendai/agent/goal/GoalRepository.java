package dev.friendai.agent.goal;

import java.util.UUID;

/** Owner-scoped persistence port for goal boards. */
public interface GoalRepository {
    GoalBoard board(UUID owner, int maxBackground, int maxReplans);

    void save(UUID owner, GoalBoard board);
}
