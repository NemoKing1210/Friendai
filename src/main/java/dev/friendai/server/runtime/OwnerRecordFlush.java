package dev.friendai.server.runtime;

import dev.friendai.infrastructure.persistence.autonomy.FreeTimeStore;
import dev.friendai.infrastructure.persistence.cartography.TerrainStore;
import dev.friendai.infrastructure.persistence.goals.GoalStore;
import dev.friendai.infrastructure.persistence.memory.CompanionProfileStore;
import dev.friendai.infrastructure.persistence.routines.RoutineStore;
import dev.friendai.infrastructure.persistence.social.SocialMemoryStore;

/** Submits due owner-record snapshots to their shared bounded writer on each server tick. */
public final class OwnerRecordFlush {
    private final CompanionProfileStore profiles;
    private final GoalStore goals;
    private final FreeTimeStore freeTime;
    private final SocialMemoryStore social;
    private final TerrainStore terrain;
    private final RoutineStore routines;

    public OwnerRecordFlush(
            CompanionProfileStore profiles,
            GoalStore goals,
            FreeTimeStore freeTime,
            SocialMemoryStore social,
            TerrainStore terrain) {
        this(profiles, goals, freeTime, social, terrain, null);
    }

    public OwnerRecordFlush(
            CompanionProfileStore profiles,
            GoalStore goals,
            FreeTimeStore freeTime,
            SocialMemoryStore social,
            TerrainStore terrain,
            RoutineStore routines) {
        this.profiles = profiles;
        this.goals = goals;
        this.freeTime = freeTime;
        this.social = social;
        this.terrain = terrain;
        this.routines = routines;
    }

    public void tick() {
        profiles.flushIfDue();
        goals.flushIfDue();
        freeTime.flushIfDue();
        social.flushIfDue();
        terrain.flushIfDue();
        if (routines != null) {
            routines.flushIfDue();
        }
    }
}
