package dev.friendai.agent.goal;

import java.util.Locale;

/**
 * How much a goal's work can change the world. The order is the severity order: work is only as safe as its
 * loudest tool, so {@link #max} keeps the highest class. Pure and never stored; {@link ToolRisk} derives it from the
 * tools a goal's plan uses every time the prompt is built.
 */
public enum RiskClass {
    /** Reading, remembering, talking, moving the body: nothing in the world changes. */
    SAFE,
    /** Ordinary, reversible work: gathering, building, farming, crafting, storing, trading. */
    CAREFUL,
    /** Fighting, creative-only powers and breaking what the owner built cannot be simply undone. */
    DESTRUCTIVE;

    /** The louder of the two classes, so one destructive tool makes the whole goal destructive. */
    public RiskClass max(RiskClass other) {
        return other != null && other.ordinal() > ordinal() ? other : this;
    }

    /** Whether the owner must be asked before this work runs. Derived, never stored. */
    public boolean needsOwner() {
        return this == DESTRUCTIVE;
    }

    /** The single prompt token, e.g. {@code risk: careful} or {@code risk: destructive; ask owner first}. */
    public String token() {
        String base = "risk: " + name().toLowerCase(Locale.ROOT);
        return needsOwner() ? base + "; ask owner first" : base;
    }
}
