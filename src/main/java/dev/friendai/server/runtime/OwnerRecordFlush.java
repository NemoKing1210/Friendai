package dev.friendai.server.runtime;

import dev.friendai.FriendaiMod;
import dev.friendai.infrastructure.persistence.autonomy.FreeTimeStore;
import dev.friendai.infrastructure.persistence.cartography.TerrainStore;
import dev.friendai.infrastructure.persistence.goals.GoalStore;
import dev.friendai.infrastructure.persistence.memory.CompanionProfileStore;
import dev.friendai.infrastructure.persistence.routines.RoutineStore;
import dev.friendai.infrastructure.persistence.social.SocialMemoryStore;

/** Submits due owner-record snapshots to their shared bounded writer on each server tick. */
public final class OwnerRecordFlush {
    /** The health view reads statuses, not records: every five seconds is enough and keeps the common path cheap. */
    private static final int HEALTH_EVERY_TICKS = 100;

    private final dev.friendai.debug.StoreHealth health = new dev.friendai.debug.StoreHealth();
    private int ticks;
    private final CompanionProfileStore profiles;
    private final GoalStore goals;
    private final FreeTimeStore freeTime;
    private final SocialMemoryStore social;
    private final TerrainStore terrain;
    private final RoutineStore routines;
    /** The runtime's complete health source; this class never keeps a store list of its own. */
    private final java.util.function.Supplier<
                    java.util.Map<
                            String,
                            java.util.Map<java.util.UUID, dev.friendai.foundation.persistence.StoreWriteStatus>>>
            storeStatuses;

    /**
     * @param routines the routine store, or null when the runtime does not own one
     * @param storeStatuses the runtime's single health view, read once per report interval
     */
    public OwnerRecordFlush(
            CompanionProfileStore profiles,
            GoalStore goals,
            FreeTimeStore freeTime,
            SocialMemoryStore social,
            TerrainStore terrain,
            RoutineStore routines,
            java.util.function.Supplier<
                            java.util.Map<
                                    String,
                                    java.util.Map<
                                            java.util.UUID, dev.friendai.foundation.persistence.StoreWriteStatus>>>
                    storeStatuses) {
        this.profiles = profiles;
        this.goals = goals;
        this.freeTime = freeTime;
        this.social = social;
        this.terrain = terrain;
        this.routines = routines;
        this.storeStatuses = storeStatuses;
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
        if (++ticks % HEALTH_EVERY_TICKS == 0) {
            reportHealth();
        }
    }

    /** One line per changed store health; a normal dirty write is not news. */
    private void reportHealth() {
        java.util.Map<String, java.util.Map<java.util.UUID, dev.friendai.foundation.persistence.StoreWriteStatus>>
                sources = storeStatuses.get();
        for (dev.friendai.debug.StoreHealth.Entry entry : health.transitions(sources)) {
            if (entry.unhealthy()) {
                FriendaiMod.LOGGER.warn("Owner store unhealthy: {}", entry.line());
            } else {
                FriendaiMod.LOGGER.info("Owner store healthy again: {}", entry.line());
            }
        }
    }
}
