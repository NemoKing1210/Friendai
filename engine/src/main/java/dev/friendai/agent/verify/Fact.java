package dev.friendai.agent.verify;

/** One verified observation, already phrased for the prompt (for example {@code +12 minecraft:oak_log}). */
public record Fact(String text) {}
