package dev.friendai.brain;

public record BrainSettings(int maxToolSteps, int historyMessages, long cooldownMs, long guestCooldownMs) {}
