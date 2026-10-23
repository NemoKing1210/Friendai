package dev.friendai.task.plan;

/** One step of a plan: the tool it came from (for messages) and how to build its task. */
public record PlanEntry(String tool, StepFactory factory) {}
