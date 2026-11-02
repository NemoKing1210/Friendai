package dev.friendai.server.runtime;

import dev.friendai.infrastructure.persistence.autonomy.FreeTimeStore;
import dev.friendai.infrastructure.persistence.goals.GoalStore;
import dev.friendai.infrastructure.persistence.memory.CompanionProfileStore;
import dev.friendai.infrastructure.persistence.social.SocialMemoryStore;

/** Submits due owner-record snapshots to their shared bounded writer on each server tick. */
public final class OwnerRecordFlush {
    private final CompanionProfileStore profiles;
    private final GoalStore goals;
    private final FreeTimeStore freeTime;
    private final SocialMemoryStore social;

    public OwnerRecordFlush(
            CompanionProfileStore profiles, GoalStore goals, FreeTimeStore freeTime, SocialMemoryStore social) {
        this.profiles = profiles;
        this.goals = goals;
        this.freeTime = freeTime;
        this.social = social;
    }

    public void tick() {
        profiles.flushIfDue();
        goals.flushIfDue();
        freeTime.flushIfDue();
        social.flushIfDue();
    }
}
