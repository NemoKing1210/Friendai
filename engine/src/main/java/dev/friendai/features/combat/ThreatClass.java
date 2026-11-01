package dev.friendai.features.combat;

/** What a mob is, before any permission or distance is considered. */
public enum ThreatClass {
    /** Attacks on sight. */
    HOSTILE("attacks on sight"),
    /** Neutral until it is hit or its target is us. */
    PROVOKED_ONLY("neutral until provoked"),
    /** Never attacked; keep away from it. */
    AVOID("too dangerous to attack"),
    /** Not a combat threat at all. */
    IGNORE("not a threat");

    private final String reason;

    ThreatClass(String reason) {
        this.reason = reason;
    }

    public String reason() {
        return reason;
    }
}
