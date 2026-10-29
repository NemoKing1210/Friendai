package dev.friendai.server.runtime;

import dev.friendai.infrastructure.persistence.autonomy.FreeTimeStore;
import dev.friendai.infrastructure.persistence.goals.GoalStore;
import dev.friendai.infrastructure.persistence.memory.CompanionProfileStore;

/** Submits due owner-record snapshots to their shared bounded writer on each server tick. */
public final class OwnerRecordFlush {
    private final CompanionProfileStore profiles;
    private final GoalStore goals;
    private final FreeTimeStore freeTime;

    public OwnerRecordFlush(CompanionProfileStore profiles, GoalStore goals, FreeTimeStore freeTime) {
        this.profiles = profiles;
        this.goals = goals;
        this.freeTime = freeTime;
    }

    public void tick() {
        profiles.flushIfDue();
        goals.flushIfDue();
        freeTime.flushIfDue();
    }
}
