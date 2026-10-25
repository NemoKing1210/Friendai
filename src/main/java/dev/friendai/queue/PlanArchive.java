package dev.friendai.queue;

public record PlanArchive(long id, String goal, boolean success, int stepCount, String summary, long finishedTick) {
    public PlanArchive { goal = goal == null ? "" : goal; summary = summary == null ? "" : summary; }
}
