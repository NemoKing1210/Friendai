package dev.friendai.agent.autonomy;

import java.util.List;

/**
 * The free-time settings the agent layer reads, mirrored from the server config's {@code autonomy} section.
 *
 * @param idleMinutes how long an idle companion waits before it starts free time by itself
 * @param resumeAfterSec how long the owner must stay quiet before a new shift begins
 * @param shiftMinutes the time limit of one shift's plan
 * @param keepItems item ids or {@code #tags} a deposit never puts away
 */
public record AutonomySettings(
        boolean enabled,
        boolean idleStart,
        int idleMinutes,
        int resumeAfterSec,
        boolean whileOffline,
        int shiftMinutes,
        int maxShiftsPerHour,
        int offlineShiftsPerHour,
        int failCooldownMin,
        boolean protectPlaces,
        boolean allowBuilding,
        List<String> keepItems) {

    public static final List<String> DEFAULT_KEEP = List.of(
            "#minecraft:pickaxes", "#minecraft:axes", "#minecraft:swords", "minecraft:torch", "minecraft:shield");

    public static final AutonomySettings DEFAULTS =
            new AutonomySettings(true, true, 5, 60, true, 20, 6, 2, 10, true, true, DEFAULT_KEEP);

    public AutonomySettings {
        keepItems = keepItems == null ? List.of() : List.copyOf(keepItems);
    }
}
