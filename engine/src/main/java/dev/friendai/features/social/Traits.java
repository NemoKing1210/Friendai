package dev.friendai.features.social;

import java.util.Locale;

/**
 * How the companion's manner has drifted, one small step at a time. Five closed traits, each from 0 to
 * {@link #MAX}; a step never changes what the companion knows, may say or is allowed to do — only how it says it.
 */
public record Traits(int humour, int warmth, int curiosity, int energy, int teasing) {
    public static final int MAX = 3;
    public static final Traits NEUTRAL = new Traits(0, 0, 0, 0, 0);

    public enum Kind {
        HUMOUR,
        WARMTH,
        CURIOSITY,
        ENERGY,
        TEASING
    }

    public Traits {
        humour = clamp(humour);
        warmth = clamp(warmth);
        curiosity = clamp(curiosity);
        energy = clamp(energy);
        teasing = clamp(teasing);
    }

    public int of(Kind kind) {
        return switch (kind) {
            case HUMOUR -> humour;
            case WARMTH -> warmth;
            case CURIOSITY -> curiosity;
            case ENERGY -> energy;
            case TEASING -> teasing;
        };
    }

    /** One step in the given direction, never past the cap. */
    public Traits step(Kind kind) {
        return with(kind, Math.min(MAX, of(kind) + 1));
    }

    /** A trait that annoyed the owner drops at once, back to neutral. */
    public Traits drop(Kind kind) {
        return with(kind, 0);
    }

    /** Everything back to neutral: the owner asked for it in so many words. */
    public Traits dropAll() {
        return NEUTRAL;
    }

    /**
     * The next trait that may grow: the smallest one the owner has not rejected, so the manner stays even
     * instead of spiking and a rejected habit never returns on its own.
     */
    public java.util.Optional<Kind> nextAllowed(java.util.Set<Kind> rejected) {
        Kind best = null;
        for (Kind kind : Kind.values()) {
            if (rejected.contains(kind)) {
                continue;
            }
            if (best == null || of(kind) < of(best)) {
                best = kind;
            }
        }
        return java.util.Optional.ofNullable(best);
    }

    /** The next trait to grow: the smallest one, so the manner stays even instead of spiking. */
    public Kind next() {
        Kind best = Kind.HUMOUR;
        for (Kind kind : Kind.values()) {
            if (of(kind) < of(best)) {
                best = kind;
            }
        }
        return best;
    }

    /** True when nothing has drifted yet. */
    public boolean neutral() {
        for (Kind kind : Kind.values()) {
            if (of(kind) > 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * One short clause for the prompt, built only from the drifted traits; empty at neutral. It never claims a
     * feeling or a memory, and it is deliberately under one line so it cannot crowd out the content rules.
     */
    public String toneLine() {
        if (neutral()) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        for (Kind kind : Kind.values()) {
            int level = of(kind);
            if (level == 0) {
                continue;
            }
            if (text.length() > 0) {
                text.append(", ");
            }
            text.append(kind.name().toLowerCase(Locale.ROOT));
            if (level == MAX) {
                text.append(" above all");
            }
        }
        return "Your manner has drifted a little: " + text + ".";
    }

    private Traits with(Kind kind, int value) {
        return switch (kind) {
            case HUMOUR -> new Traits(value, warmth, curiosity, energy, teasing);
            case WARMTH -> new Traits(humour, value, curiosity, energy, teasing);
            case CURIOSITY -> new Traits(humour, warmth, value, energy, teasing);
            case ENERGY -> new Traits(humour, warmth, curiosity, value, teasing);
            case TEASING -> new Traits(humour, warmth, curiosity, energy, value);
        };
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(MAX, value));
    }
}
