package dev.friendai.features.survival.selfcare;

/** What to spend on this emergency, and which item of the stock pays for it. */
public record SelfCareDecision(SelfCareKind kind, SelfCareItem item) {}
