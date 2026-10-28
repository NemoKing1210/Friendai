package dev.friendai.agent.turn;

public record BrainSettings(int maxToolSteps, int historyMessages, long cooldownMs, long guestCooldownMs) {}
