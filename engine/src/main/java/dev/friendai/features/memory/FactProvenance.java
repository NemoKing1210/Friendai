package dev.friendai.features.memory;

import java.util.UUID;

/**
 * How a remembered fact was established, and whether its claim can still be trusted. Derived entirely from what
 * is already stored: a goal outcome line written by {@link GoalOutcomeFact} carries its own verified marker, the
 * author uuid says whether the owner told it, and the category says whether the claim is a durable owner
 * statement or a perishable observation. Nothing new is persisted.
 *
 * <p>Only a place or a note can go stale, because only those describe world state that changes on its own. A
 * profile, preference or promise is a durable owner statement and its age alone never invalidates it.
 */
public final class FactProvenance {
    /** How long a place or note keeps its claim before the audit calls it stale. */
    public static final long STALE_AFTER_MILLIS = 30L * 86_400_000L;

    /** The token the audit shows before the age for a fact whose claim can go stale. */
    public enum Source {
        /** A task the world checked produced the fact; see {@link GoalOutcomeFact}. */
        VERIFIED("verified"),
        /** The owner stated it about themselves, as a preference, promise or profile fact. */
        OWNER("owner"),
        /** Anything the companion worked out on its own, including an unchecked goal outcome. */
        INFERRED("inferred");

        private final String token;

        Source(String token) {
            this.token = token;
        }

        /** The short lowercase word this source renders as. */
        public String token() {
            return token;
        }
    }

    /**
     * The exact verdict {@link GoalOutcomeFact} writes before its evidence, built from that helper so a wording
     * change cannot leave this test behind. Only a fully verified outcome matches; "unverified" and
     * "abandoned, unverified" do not.
     */
    private static final String VERIFIED_MARKER = "\" \u2014 " + GoalOutcomeFact.End.VERIFIED.word;

    private FactProvenance() {}

    /** Where the fact came from, judging a task-verified goal line first, then the owner's own statement. */
    public static Source source(Fact fact, UUID owner) {
        if (fact == null) {
            return Source.INFERRED;
        }
        String text = fact.text();
        if (text != null && text.contains(VERIFIED_MARKER)) {
            return Source.VERIFIED;
        }
        if (owner != null && owner.equals(fact.authorUuid()) && ownerStatement(fact.category())) {
            return Source.OWNER;
        }
        return Source.INFERRED;
    }

    /** Whether the category describes a claim that can go stale: a remembered place or a note. */
    public static boolean perishable(Fact fact) {
        return fact != null && (fact.category() == Category.PLACE || fact.category() == Category.NOTE);
    }

    /** Whether a perishable claim was recorded longer than {@link #STALE_AFTER_MILLIS} ago. */
    public static boolean expired(Fact fact, long now) {
        return perishable(fact) && now - fact.createdAt() >= STALE_AFTER_MILLIS;
    }

    /**
     * The short token the audit shows before the age: the source, plus {@code stale} once the claim has expired.
     * Empty for the categories that keep their existing rendering, because their age alone never invalidates them.
     */
    public static String marker(Fact fact, UUID owner, long now) {
        if (!perishable(fact)) {
            return "";
        }
        String token = source(fact, owner).token();
        return expired(fact, now) ? token + " stale" : token;
    }

    private static boolean ownerStatement(Category category) {
        return category == Category.PROFILE || category == Category.PREFERENCE || category == Category.PROMISE;
    }
}
