package dev.friendai.agent.goal;

public enum GoalStatus {
    ACTIVE,
    BLOCKED,
    DONE,
    ABANDONED;

    public boolean terminal() {
        return this == DONE || this == ABANDONED;
    }
}
